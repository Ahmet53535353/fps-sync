package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kesinti teşhisi: park neden bazı karelerde uyumadan dönüyor?
 *
 * <h2>Neden bu önemli</h2>
 * Üç ardışık koşuda park ikiye bölünmüştü: 24.149 çağrı istenen 9,21 ms'nin tamamını
 * uyumuş, 23.759 çağrı yalnız 2,79 ms uyumuş. Ve üçünde de {@code p05 = 0.0 µs}:
 * en az %5'i <em>hiç uyumadan</em> dönüyor.
 *
 * <p>Bu, şu varsayıma işaret ediyor:
 * <pre>
 *   } catch (InterruptedException e) {
 *       Thread.currentThread().interrupt();   // bayrak yeniden SET ediliyor
 *   }
 * </pre>
 * {@code LockSupport.parkNanos}, interrupt durumu set ise <b>anında döner</b> ve
 * bayrağı temizlemez. Biz de temizlemiyoruz. Bu tam olarak gözlenen davranışı
 * üretir: bayrak temizken park tam istenen süreyi uyur, setken anında döner ve
 * kalanı spin'e bırakır.
 *
 * <h2>Bu bir teşhistir, düzeltme değil</h2>
 * Hiçbir davranış değişmez. Bayrak okunur, <b>temizlenmez</b>
 * ({@code isInterrupted()} kullanılır, {@code Thread.interrupted()} değil) ve
 * yalnızca sayılır. İki sayaç şunu söyleyecek:
 * <ul>
 *   <li>kesintiler gerçekten oluyor mu</li>
 *   <li>park anında bayrak set miydi — yani varsayım doğru mu</li>
 * </ul>
 *
 * <p>Sayaçlar sıfır çıkarsa bu varsayım yanlıştır ve park-tekrarı (A) tek başına
 * yeterli bir çözümdür.
 */
class FrameLimiterInterruptDiagnosticTest {

    private static final long BUDGET_NS = 1_000_000_000L / 60L;

    /** Sanal saatle kurulmuş sınırlayıcı ve saati. */
    private static final class Rig {
        final FrameLimiter limiter;
        final long[] clock;
        final long deadlineNs;

        Rig() {
            final long startNs = 1_000_000L;
            deadlineNs = startNs + BUDGET_NS;
            clock = new long[] {startNs};
            limiter = FrameLimiter.INSTANCE;
            limiter.reset();
            limiter.resetFrameStats();
            limiter.nanoTime = () -> clock[0];
            limiter.sleeper = ns -> clock[0] += ns;
            limiter.spinHook = () -> { clock[0] = deadlineNs; };
            limiter.onSpin = spin -> { };
            limiter.setEnabled(true);
            limiter.setMonitorRefreshRate(60);
            // İlk çağrı yalnızca ızgarayı kurar (lastFrameTime), park yapmaz.
            // Testlerin gerçekten park eden bir kare ölçmesi için burada yapılır.
            limiter.limitFrame();
        }

        /** Sınırlayıcının gerçekten park ettiği bir kare çalıştırır. */
        void frame() {
            clock[0] += BUDGET_NS / 2;    // bütçenin yarısına kadar ilerle
            limiter.limitFrame();
        }

        /**
         * Sınırlayıcının <em>park etmediği</em> kare: oyun bütçeyi aşmış.
         *
         * <p>Başlangıç karesi değil — orada henüz ızgara kurulmamıştır. Burada ızgara
         * kurulmuş ama saat hedefi geçtiği için sınırlayıcı hiç beklemiyor.
         */
        void frameWithoutWaiting() {
            clock[0] += BUDGET_NS;        // hedefi aş
            limiter.limitFrame();
        }
    }

    @Test
    @DisplayName("park anında interrupt bayrağı setse sayılır")
    void interruptFlagIsCounted() {
        Rig rig = new Rig();
        try {
            Thread.currentThread().interrupt();
            rig.frame();

            assertEquals(1, rig.limiter.interruptFlagSetFrames,
                    "bayrak setken park anında sayılmalı");
        } finally {
            Thread.interrupted();   // testin kirletmemesi için temizle
        }
    }

    @Test
    @DisplayName("teşhis bayrağı TEMİZLEMEZ")
    void diagnosticDoesNotClearTheFlag() {
        Rig rig = new Rig();
        try {
            Thread.currentThread().interrupt();
            rig.frame();

            assertTrue(Thread.currentThread().isInterrupted(),
                    "bayrak okunmalı ama temizlenmemeli; temizlemek oyunun davranışını "
                            + "değiştirirdi ve teşhis olmaktan çıkardı");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("bayrak temizken sayaç artmaz")
    void cleanFlagIsNotCounted() {
        Rig rig = new Rig();
        rig.frame();

        assertEquals(0, rig.limiter.interruptFlagSetFrames,
                "bayrak yokken sayılmamalı");
    }

    @Test
    @DisplayName("park yapılmayan karede sayaç artmaz")
    void noParkNoCount() {
        Rig rig = new Rig();
        try {
            Thread.currentThread().interrupt();
            rig.frameWithoutWaiting();
            assertEquals(0, rig.limiter.parkCallsLastFrame,
                    "bu karede park çağrısı olmamalı");
            assertEquals(0, rig.limiter.interruptFlagSetFrames,
                    "park edilmeyen karede teşhis yapılmamalı");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("sıfırlama teşhis sayaçlarını da temizler")
    void resetClearsDiagnostics() {
        Rig rig = new Rig();
        try {
            Thread.currentThread().interrupt();
            rig.frame();
            assertEquals(1, rig.limiter.interruptFlagSetFrames);
        } finally {
            Thread.interrupted();
        }

        rig.limiter.resetFrameStats();

        assertEquals(0, rig.limiter.interruptFlagSetFrames);
        assertEquals(0, rig.limiter.interruptsCaught);
    }

    @Test
    @DisplayName("yakalanan InterruptedException sayılır")
    void caughtInterruptIsCounted() {
        Rig rig = new Rig();
        // Yalnız İLK çağrı kesinti atar. Böylece sayı, tekrar politikasından bağımsız
        // olarak tam 1 olur: bu test "kesinti sayılıyor" davranışını doğrular, kaç kez
        // tekrar denendiğini değil (o ParkRetryAfterFailTest'in konusu).
        int[] calls = {0};
        rig.limiter.sleeper = ns -> {
            if (calls[0]++ == 0) {
                throw new InterruptedException("test");
            }
            // Sonraki çağrı normal uyur: kesinti "başarısız çağrı" sayılır ve
            // sınırlayıcı tam bir kez daha dener (bkz. ParkRetryAfterFailTest).
            rig.clock[0] += ns;
        };
        try {
            rig.frame();

            assertEquals(1, rig.limiter.interruptsCaught,
                    "yakalanan kesinti sayılmalı");
        } finally {
            Thread.interrupted();   // catch bloğu bayrağı set eder
        }
    }

    @Test
    @DisplayName("teşhis kayıtçıya ulaşır")
    void diagnosticReachesTheRecorder() {
        Rig rig = new Rig();
        FramePacingRecorder recorder = new FramePacingRecorder();
        try {
            Thread.currentThread().interrupt();
            rig.frame();
        } finally {
            Thread.interrupted();
        }

        FrameLimiter limiter = rig.limiter;
        recorder.recordFrame(BUDGET_NS, BUDGET_NS, true, limiter.parkCallsLastFrame,
                limiter.parkRequestedNsLastFrame, limiter.parkOvershootNsLastFrame,
                limiter.spinNsLastFrame, limiter.parkElapsedNsLastFrame,
                limiter.interruptFlagSetFrames, limiter.interruptsCaught);

        assertEquals(1, recorder.totals().interruptFlagFrames(),
                "teşhis kayıtçıya ulaşmalı");
    }
}