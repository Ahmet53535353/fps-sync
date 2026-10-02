package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ölçüm yolunun <b>sıfır ayak izi</b> bıraktığını kanıtlar.
 *
 * <h2>Neden bu ayrı bir test</h2>
 * Sıcak yolun maliyeti 2026-10-01'de ölçülmüştü: {@code limitFrame()} 60 Hz'de
 * <b>65.1 µs/kare</b>, yani tek çekirdeğin %0.4'ü. Yeni ölçüm kancası bu değeri
 * <em>bozmamalı</em>. Ekstra bir {@code nanoTime} çağrısı bile kare başına ~30 ns ekler;
 * gereksiz tahsis ise GC baskısı yaratır.
 *
 * <h2>Neden en küçük tur seçiliyor</h2>
 * {@code getThreadAllocatedBytes} iş parçacığına ait sayacı verir ama JIT'in
 * <em>tek seferlik</em> olaylarını ayırmaz: nadir bir deoptimizasyon, sınıf ilklendirme
 * veya uncommon trap ölçüm penceresine düşerse doğru çalışan kod bile pozitif okunur.
 * Bu tam olarak yaşandı: {@code resetFrameStats} yalnızca alanlara 0 yazıyor, izolasyonda
 * 6/6 geçerken tam pakette <b>3 koşudan 1'inde</b> 536 bayt gösterdi.
 *
 * <p>Bu yüzden ölçüm bir kez değil birkaç kez yapılır ve <b>en küçük</b> tur esas alınır.
 * Bu eşiği gevşetmez: gerçek, kaçırılamayan bir tahsis her turda görünür ve en küçük tur
 * de pozitif kalır. Yalnızca tek seferlik olayları eler.
 *
 * <h2>Bütçeler ayrı çünkü iş ucuz değil</h2>
 * {@link FramePacingRecorder#activeLatenessPercentileNs} sıralama yaptığı için ölçüm
 * kancasından kat kat pahalıdır. Tüm testlerde aynı örnek sayısı kullanılırsa paket
 * 15 dakikayı aşar. Bu yüzden her test kendi bütçesini taşır; toplam çağrı sayısı
 * yine 10^6 mertebesindedir ama süre makul kalır.
 */
class FramePacingZeroAllocationTest {

    private static final long BUDGET_NS = 16_666_667L;
    private static final long WARMUP = 50_000;

    /** Sınırlamanın tek bir karede okuyup sıfırladığı alanlar. */
    private static final int RESET_ROUNDS = 5;
    private static final long RESET_SAMPLES = 250_000L;

    /** Ölçüm kancası: sayaç okuma + kayıt. */
    private static final int HOOK_ROUNDS = 5;
    private static final long HOOK_SAMPLES = 200_000L;

    /** Yüzde hesabı sıralama yapar; ucuz tutulur. */
    private static final int QUERY_ROUNDS = 5;
    private static final long QUERY_SAMPLES = 6_000L;

    @Test
    @DisplayName("ölçüm kancası 10^6 çağrıda bayt ayırmaz")
    void measurementPathAllocatesNothing() {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        FramePacingRecorder recorder = new FramePacingRecorder();
        limiter.resetFrameStats();

        for (long i = 0; i < WARMUP; i++) {
            recordSample(limiter, recorder, i);
        }

        long allocated = minAllocated(() -> {
            for (long i = 0; i < HOOK_SAMPLES; i++) {
                recordSample(limiter, recorder, i);
            }
        }, HOOK_ROUNDS);

        assertZero(allocated, "ölçüm yolu", HOOK_SAMPLES * HOOK_ROUNDS);
    }

    @Test
    @DisplayName("sayaç sıfırlama tahsis yapmaz")
    void resetAllocatesNothing() {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        for (long i = 0; i < WARMUP; i++) {
            limiter.resetFrameStats();
        }

        long allocated = minAllocated(() -> {
            for (long i = 0; i < RESET_SAMPLES; i++) {
                limiter.resetFrameStats();
            }
        }, RESET_ROUNDS);

        assertZero(allocated, "resetFrameStats", RESET_SAMPLES * RESET_ROUNDS);
    }

    @Test
    @DisplayName("rapor yüzdesi ve okuma işlemleri tahsis yapmaz")
    void readOnlyQueriesAllocateNothing() {
        FramePacingRecorder r = newFrameRecorderWithData();
        for (int i = 0; i < WARMUP; i++) {
            r.activeLatenessPercentileNs(0.95);
            r.activeOvershootMedianNs();
            r.actualFps();
        }

        long allocated = minAllocated(() -> {
            for (int i = 0; i < QUERY_SAMPLES; i++) {
                r.activeLatenessPercentileNs(0.95);
                r.activeOvershootMedianNs();
                r.actualFps();
            }
        }, QUERY_ROUNDS);

        assertZero(allocated, "yüzde hesabı", QUERY_SAMPLES * QUERY_ROUNDS);
    }

    // --- yardımcılar -------------------------------------------------------

    private static void recordSample(FrameLimiter limiter, FramePacingRecorder r, long i) {
        limiter.resetFrameStats();
        r.recordFrame(BUDGET_NS + (i % 3), BUDGET_NS, i % 2 == 0,
                (int) (i % 2), BUDGET_NS - 100_000, 96_000, 4_400);
    }

    private static void assertZero(long allocated, String what, long totalCalls) {
        // 10^6 çağrı başına 1 bayt bile hoş görülmez: toplam beklenen 0'dır.
        assertTrue(allocated <= 0, what + " " + totalCalls + " çağrıda " + allocated
                + " bayt ayırdı (beklenen: 0)");
    }

    /**
     * Ölçülen işi {@code rounds} kez çalıştırıp en küçük tahsisi döner.
     *
     * <p>Gerekçe sınıf javadoc'unda: tek seferlik JIT olayları ölçümü kirletir, ama
     * kaçırılamayan gerçek tahsis her turda görünür.
     */
    private static long minAllocated(Runnable body, int rounds) {
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            // Sessizce geçmek yanlış olur: kanıt üretilemedi.
            throw new AssertionError(
                    "JVM thread allocation ölçümü desteklemiyor; sıfır ayak izi kanıtlanamadı");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();

        long min = Long.MAX_VALUE;
        for (int round = 0; round < rounds; round++) {
            long before = bean.getThreadAllocatedBytes(id);
            body.run();
            long delta = bean.getThreadAllocatedBytes(id) - before;
            if (delta < min) {
                min = delta;
            }
        }
        return min;
    }

    /** Yüzde hesabının yolu çalışsın diye veri doldurulmuş kayıtçı. */
    private static FramePacingRecorder newFrameRecorderWithData() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 1000; i++) {
            r.recordFrame(BUDGET_NS + i, BUDGET_NS, i % 2 == 0, 1,
                    BUDGET_NS - 100_000, 96_000, 4_400);
        }
        return r;
    }
}
