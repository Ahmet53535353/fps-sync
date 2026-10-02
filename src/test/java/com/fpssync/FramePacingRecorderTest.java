package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Kare zamanlaması ölçüm motorunu kilitler.
 *
 * <p>Kütüphane bağımlılığı yoktur; Minecraft sınıflarına da dokunmaz. Bu yüzden her şey
 * burada mikrosaniye ölçümünden bağımsız ve deterministik test edilebilir.
 *
 * <h2>Ölçülen şey: gecikme, kare süresi değil</h2>
 * Ham kare süresi hedefi bilmeyi gerektirir — "p99 = 7.41 ms" dediğimizde karşılaştıracak
 * bütçeyi hatırlamamız gerekir. Gecikme ({@code kare − bütçe}) ise hedef 60 Hz de olsa
 * 240 Hz de olsa aynı ölçekte ve "geç kare" doğrudan {@code gecikme > 0} sayımı olur.
 *
 * <h2>Neden iki rejim</h2>
 * Oyun başlangıcında FPS düşüktür ve sınırlayıcı hiç beklemez; oyun ısındıkça FPS yükselir
 * ve sınırlayıcı devreye girer. Tek blok hâlinde biriktirilseydi istatistiğin çoğunluğu
 * sınırlayıcının <em>çalışmadığı</em> karelerden gelirdi.
 */
class FramePacingRecorderTest {

    private static final long BUDGET_NS = 16_666_667L; // 60 Hz

    private static FramePacingRecorder newRecorder() {
        return new FramePacingRecorder();
    }

    /** Sınırlayıcı bekleyen bir kare kaydeder (hedefi aşarak bitti). */
    private static void late(FramePacingRecorder r, long latenessNs) {
        r.recordFrame(BUDGET_NS + latenessNs, BUDGET_NS, true, 0, 0, 0, 0);
    }

    /** Sınırlayıcı bekleyen, hedefinde biten kare. */
    private static void onTime(FramePacingRecorder r) {
        r.recordFrame(BUDGET_NS, BUDGET_NS, true, 0, 0, 0, 0);
    }

    /** Sınırlayıcı hiç beklemedi — oyun hedefin altında kaldı. */
    private static void idle(FramePacingRecorder r, long frameNs) {
        r.recordFrame(frameNs, BUDGET_NS, false, 0, 0, 0, 0);
    }

    @Nested
    @DisplayName("Rejim ayrımı")
    class Regimes {

        @Test
        @DisplayName("bekleyen ve boşta kareler ayrı sayılır")
        void countsAreSeparate() {
            FramePacingRecorder r = newRecorder();
            onTime(r);
            late(r, 200_000);
            idle(r, 200_000_000);

            assertEquals(2, r.activeFrames());
            assertEquals(1, r.idleFrames());
        }

        @Test
        @DisplayName("bekleme yapılmayan kareler park maliyeti taşımaz")
        void idleFramesCarryNoWaitCost() {
            FramePacingRecorder r = newRecorder();
            r.recordFrame(200_000_000, BUDGET_NS, false, 0, 0, 0, 0);

            assertEquals(0, r.activeParkCalls());
            assertEquals(0, r.idleParkCalls());
        }

        @Test
        @DisplayName("ilk bekleme anı kaydedilir — '60 FPS'i ne zaman geçti' sorusunun cevabı")
        void recordsWhenLimiterFirstWaited() {
            FramePacingRecorder r = newRecorder();
            idle(r, 200_000_000);
            assertEquals(-1, r.firstWaitAtNs(), "henüz beklemedi");

            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 0, 0, 0, 0);
            assertTrue(r.firstWaitAtNs() >= 0, "ilk bekleme kaydedilmeli");
        }
    }

    @Nested
    @DisplayName("Gecikme istatistiği")
    class Lateness {

        @Test
        @DisplayName("hedefinde biten kare geç sayılmaz")
        void exactlyOnTimeIsNotLate() {
            FramePacingRecorder r = newRecorder();
            onTime(r);

            assertEquals(0, r.activeLateFrames());
        }

        @Test
        @DisplayName("hedefi aşan kare geç sayılır")
        void overBudgetCountsAsLate() {
            FramePacingRecorder r = newRecorder();
            late(r, 1);

            assertEquals(1, r.activeLateFrames());
        }

        @Test
        @DisplayName("medyan kovaların ortasındadır")
        void medianIsMiddleBucket() {
            FramePacingRecorder r = newRecorder();
            // 1 kare 0.05 ms (1. kova), 9 kare 0.10 ms (2. kova) -> medyan 2. kova
            late(r, 50_000);
            for (int i = 0; i < 9; i++) {
                late(r, 100_000);
            }

            long median = r.activeLatenessMedianNs();
            assertTrue(median >= 100_000 && median <= 150_000,
                    "medyan 2. kovada olmalıydı, oldu: " + median);
        }

        @Test
        @DisplayName("p95 yüksek gecikmeleri yakalar")
        void p95CatchesTheTail() {
            FramePacingRecorder r = newRecorder();
            // 90/10: p95 kuyruğa düşmeli. 95/5 olsaydı p95 tam sınırda kalırdı
            // (95. örnek küçük olan), yani "p95 kuyruğu yakalar" iddiası yanlış olurdu.
            for (int i = 0; i < 90; i++) {
                late(r, 100_000); // 0.10 ms
            }
            for (int i = 0; i < 10; i++) {
                late(r, 10_000_000); // 10 ms
            }

            long p95 = r.activeLatenessPercentileNs(0.95);
            assertTrue(p95 >= 10_000_000, "p95 10 ms'lik kuyruğu yakalamalı, oldu: " + p95);
        }

        @Test
        @DisplayName("en kötü gecikme tam değerle raporlanır")
        void maxIsExact() {
            FramePacingRecorder r = newRecorder();
            late(r, 100_000);
            late(r, 7_300_000);

            assertEquals(7_300_000, r.activeLatenessMaxNs());
        }

        @Test
        @DisplayName("kova sınırının üstündeki değerler taşma sayacına düşer")
        void overflowIsCounted() {
            FramePacingRecorder r = newRecorder();
            // 50 ms'lik ince histogram sınırı
            late(r, FramePacingRecorder.LATE_FINE_MAX_NS + 1_000_000);
            late(r, FramePacingRecorder.LATE_FINE_MAX_NS + 5_000_000);

            assertEquals(2, r.activeLatenessOverflowFrames());
            assertEquals(FramePacingRecorder.LATE_FINE_MAX_NS + 5_000_000, r.activeLatenessMaxNs());
        }

        @Test
        @DisplayName("hiç kare yoksa istatistik patlamaz")
        void emptyIsSafe() {
            FramePacingRecorder r = newRecorder();

            assertEquals(0, r.activeLatenessMedianNs());
            assertEquals(0, r.activeLatenessPercentileNs(0.95));
            assertEquals(0, r.activeLatenessMaxNs());
        }
    }

    @Nested
    @DisplayName("Park aşımı")
    class ParkOvershoot {

        @Test
        @DisplayName("aşım median ve p95 raporlanır")
        void reportsPercentiles() {
            FramePacingRecorder r = newRecorder();
            for (int i = 0; i < 50; i++) {
                r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 16_666_667, 96_000, 4_400);
            }
            for (int i = 0; i < 50; i++) {
                r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 16_666_667, 300_000, 4_400);
            }

            assertEquals(100, r.activeParkCalls(), "50 + 50 kare = 100 park çağrısı");
            assertTrue(r.activeOvershootMedianNs() <= 96_000,
                    "medyan alt grupta olmalı, oldu: " + r.activeOvershootMedianNs());
            assertTrue(r.activeOvershootPercentileNs(0.95) >= 300_000,
                    "p95 üst grupta olmalı");
        }

        @Test
        @DisplayName("park çağrısı yoksa aşım sıfırdır")
        void noParkNoOvershoot() {
            FramePacingRecorder r = newRecorder();
            onTime(r);

            assertEquals(0, r.activeOvershootMedianNs());
            assertEquals(0, r.activeOvershootMaxNs());
        }

        @Test
        @DisplayName("bir karede birden çok park olabilir")
        void multipleParkCallsPerFrame() {
            FramePacingRecorder r = newRecorder();
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 3, 50_000_000, 120_000, 0);

            assertEquals(3, r.activeParkCalls());
            assertEquals(50_000_000, r.activeParkNsTotal());
        }
    }

    @Nested
    @DisplayName("Spin")
    class Spin {

        @Test
        @DisplayName("spin süresi ve giriş sayısı toplanır")
        void accumulatesSpin() {
            FramePacingRecorder r = newRecorder();
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 16_000_000, 100_000, 4_400);
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 16_000_000, 100_000, 4_400);

            assertEquals(8_800, r.activeSpinNsTotal(), "iki kare x 4.4 µs spin");
            assertEquals(2, r.activeSpinEntries());
        }
    }

    @Nested
    @DisplayName("Duraklatma (rapor sırasında)")
    class Pause {

        @Test
        @DisplayName("duraklatılan dönemde kare sayılmaz")
        void pausedFramesAreIgnored() {
            FramePacingRecorder r = newRecorder();
            onTime(r);
            assertEquals(1, r.activeFrames());

            r.setPaused(true);
            onTime(r);
            late(r, 500_000);
            r.setPaused(false);

            assertEquals(1, r.activeFrames(), "duraklatılan kareler sayılmamalı");
            assertEquals(0, r.activeLateFrames());
        }

        @Test
        @DisplayName("devam ettirilince yeniden sayar")
        void resumesAfterPause() {
            FramePacingRecorder r = newRecorder();
            r.setPaused(true);
            onTime(r);
            r.setPaused(false);
            onTime(r);

            assertEquals(1, r.activeFrames());
        }
    }

    @Nested
    @DisplayName("Sıfırlama")
    class Reset {

        @Test
        @DisplayName("sıfırlama tüm sayaçları ve histogramları temizler")
        void resetClearsEverything() {
            FramePacingRecorder r = newRecorder();
            late(r, 200_000);
            idle(r, 200_000_000);
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 2, 30_000_000, 150_000, 0);

            r.reset();

            assertEquals(0, r.activeFrames());
            assertEquals(0, r.idleFrames());
            assertEquals(0, r.activeLateFrames());
            assertEquals(0, r.activeParkCalls());
            assertEquals(0, r.activeSpinNsTotal());
            assertEquals(0, r.activeLatenessMaxNs());
            assertEquals(-1, r.firstWaitAtNs());
        }

        @Test
        @DisplayName("sıfırlama gecikme geçmişini de siler")
        void resetClearsHistogram() {
            FramePacingRecorder r = newRecorder();
            late(r, 5_000_000);
            r.reset();
            onTime(r);

            assertEquals(0, r.activeLatenessPercentileNs(0.99),
                    "eski yüksek gecikme yüzdeye taşınmamalı");
        }
    }

    @Nested
    @DisplayName("Gerçek FPS")
    class RealFps {

        @Test
        @DisplayName("FPS kare sayısı / geçen süredir — anlık FPS ortalaması değil")
        void fpsIsFramesOverElapsed() {
            FramePacingRecorder r = newRecorder();
            // recordFrame zaten geçen süreyi biriktiriyor; ayrıca advance etmek
            // süreyi iki kez sayardı (60 kare / 2 sn = 30 FPS çıkardı).
            for (int i = 0; i < 60; i++) {
                idle(r, 16_666_667); // 60 kare x 16.67 ms = 1 sn
            }

            assertEquals(60.0, r.actualFps(), 0.01);
        }

        @Test
        @DisplayName("hiç kare yokken FPS sıfırdır, bölme hatası fırlatmaz")
        void zeroElapsedIsSafe() {
            FramePacingRecorder r = newRecorder(); // hiç kare yok -> geçen süre 0

            assertEquals(0.0, r.actualFps(), 0.0001);
        }
    }
}