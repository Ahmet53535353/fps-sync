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
 * <p>Ölçüm kancası iki parçadan oluşur: sınırlayıcının kare sayaclarını okuması ve
 * {@link FramePacingRecorder#recordFrame} çağrısı. İkisi de testte gerçek yoldan
 * koşulur.
 */
class FramePacingZeroAllocationTest {

    private static final long BUDGET_NS = 16_666_667L;
    private static final long WARMUP = 200_000;
    private static final long SAMPLES = 1_000_000;

    @Test
    @DisplayName("ölçüm kancası 10^6 çağrıda bayt ayırmaz")
    void measurementPathAllocatesNothing() {
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            // Ölçüm imkânı yoksa testi sessizce geçirmek yanlış olur; açıkça bildir.
            throw new AssertionError(
                    "JVM thread allocation ölçümü desteklemiyor; sıfır ayak izi kanıtlanamadı");
        }
        bean.setThreadAllocatedMemoryEnabled(true);

        FrameLimiter limiter = FrameLimiter.INSTANCE;
        FramePacingRecorder recorder = new FramePacingRecorder();
        limiter.resetFrameStats();

        long id = Thread.currentThread().threadId();
        for (long i = 0; i < WARMUP; i++) {
            limiter.resetFrameStats();
            recorder.recordFrame(BUDGET_NS + (i % 3), BUDGET_NS, i % 2 == 0,
                    (int) (i % 2), BUDGET_NS - 100_000, 96_000, 4_400);
        }

        long before = bean.getThreadAllocatedBytes(id);
        for (long i = 0; i < SAMPLES; i++) {
            limiter.resetFrameStats();
            recorder.recordFrame(BUDGET_NS + (i % 3), BUDGET_NS, i % 2 == 0,
                    (int) (i % 2), BUDGET_NS - 100_000, 96_000, 4_400);
        }
        long allocated = bean.getThreadAllocatedBytes(id) - before;

        // 1M çağrı başına 1 bayt bile hoş görülmez: 10^6 çağrıda bile toplam 0 beklenir.
        assertTrue(allocated <= 0,
                "ölçüm yolu " + SAMPLES + " çağrıda " + allocated + " bayt ayırdı (beklenen: 0)");
    }

    @Test
    @DisplayName("sayaç sıfırlama tahsis yapmaz")
    void resetAllocatesNothing() {
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();

        FrameLimiter limiter = FrameLimiter.INSTANCE;
        // Isinma olculen metodu CIFT cagir; aksi halde JIT derlemesi olcum
        // sirasinda olur ve tek seferlik tahsis (536 bayt) gercek hatayi maskeler.
        for (long i = 0; i < WARMUP; i++) {
            limiter.resetFrameStats();
        }
        long before = bean.getThreadAllocatedBytes(id);
        for (long i = 0; i < SAMPLES; i++) {
            limiter.resetFrameStats();
        }
        long allocated = bean.getThreadAllocatedBytes(id) - before;

        assertTrue(allocated <= 0, "resetFrameStats " + allocated + " bayt ayırdı");
    }

    /** Yüzde hesabının yolu çalışsın diye veri doldurulmuş kayıtçı. */
    private static FramePacingRecorder newFrameRecorderWithData() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 1000; i++) {
            r.recordFrame(BUDGET_NS + i, BUDGET_NS, i % 2 == 0, 1, BUDGET_NS - 100_000, 96_000, 4_400);
        }
        return r;
    }

    @Test
    @DisplayName("rapor yüzdesi ve okuma işlemleri tahsis yapmaz")
    void readOnlyQueriesAllocateNothing() {
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();

        FramePacingRecorder r = newFrameRecorderWithData();
        // Isinma yuzde hesabini da cagirsin.
        for (int i = 0; i < WARMUP; i++) {
            r.activeLatenessPercentileNs(0.95);
            r.activeOvershootMedianNs();
            r.actualFps();
        }

        long before = bean.getThreadAllocatedBytes(id);
        for (int i = 0; i < 50_000; i++) {
            r.activeLatenessPercentileNs(0.95);
            r.activeOvershootMedianNs();
            r.actualFps();
        }
        long allocated = bean.getThreadAllocatedBytes(id) - before;

        assertTrue(allocated <= 0, "yüzde hesabı " + allocated + " bayt ayırdı");
    }
}