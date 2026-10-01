package com.fpssync;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Eski ({@code Thread.sleep} kırpmalı) ve yeni ({@code parkNanos}) sınırlayıcıyı
 * <b>aynı süreçte, aynı ölçüm yoluyla</b> karşılaştırır.
 *
 * <p>Neden aynı süreçte: iki ayrı koşu, iki ayrı makine yükü demektir. Farkı
 * arayan yaklaşımda gürültü, aradaki gerçek farkla aynı büyüklük mertebesindedir.
 * Burada iki sınırlayıcı dönüşümlü koşar, ısınma/JIT koşulları paylaşılır.
 *
 * <p>Ölçüm tamamen gerçektir: gerçek {@code Thread.sleep}, gerçek
 * {@code LockSupport.parkNanos}, gerçek kernel zamanlayıcı, ana thread CPU'su
 * {@code ThreadMXBean} ile (ns çözünürlük, JIT/GC hariç).
 *
 * <p>Her satırda ayrıca <b>elde hesaplanan</b> spin kalanı gösterilir. Bu iki
 * sütunun ayrışması modelin gerçekten neyi kaçırdığını gösterir; yalnız ölçüme
 * bakmak "neden bu kadar fark var" sorusunu cevaplamaz.
 */
public class FrameLimiterLegacyComparison {


    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int targetFps = args.length > 1 ? Integer.parseInt(args[1]) : 60;

        long budgetNs = 1_000_000_000L / targetFps;

        System.out.println("=== Eski (Thread.sleep kırpma) vs yeni (parkNanos) ===");
        System.out.println("hedef " + targetFps + " fps · bütçe " + (budgetNs / 1000) + " µs · "
                + seconds + " s/adım · aynı süreç, dönüşümlü");
        System.out.println("CPU: ana thread (ThreadMXBean, ns) · spin: gerçek ölçüm");

        System.out.println();
        System.out.printf("%-11s %-9s %8s %11s %11s %11s%n",
                "render(µs)", "yol", "kare", "CPU/kare", "ort.spin", "hesap.spin");
        System.out.println("-".repeat(64));

        warmUp(budgetNs, targetFps);

        // 0, %25, %50, %75, %100, %125 — bütçe altı (çalışır) ve üstü (görünmez)
        double[] fractions = {0, 0.25, 0.50, 0.75, 1.0, 1.25};

        for (double f : fractions) {
            long renderNs = (long) (budgetNs * f);

            Row legacy = measureLegacy(renderNs, seconds, targetFps);
            Row modern = measureModern(renderNs, seconds, targetFps);

            // dönüşümlü koşum: sıra etkisini kırmak için ikinci tur ters sırada
            Row legacy2 = measureLegacy(renderNs, seconds, targetFps);
            Row modern2 = measureModern(renderNs, seconds, targetFps);

            long remaining = budgetNs - renderNs;
            long predicted = remaining > 0 ? LegacyFrameLimiter.spinRemainderNs(remaining) : 0;

            print(renderNs, "ESKİ", legacy, legacy2, predicted);
            print(renderNs, "YENİ", modern, modern2, predicted);
        }

        System.out.println();
        System.out.println("hesap.spin = eski formülün elle sonucu: (remaining mod 1ms) + 1ms");
        System.out.println("             uyku aşımı hariç, yani EN İYİMSER alt sınır");
        System.out.println("ort.spin    = gerçek ölçüm; aradaki fark uyku aşımı + zamanlama gürültüsü");
    }

    private static void print(long renderNs, String label, Row a, Row b, long predictedNs) {
        System.out.printf("%-11d %-9s %8d %11s %11s %11s%n",
                renderNs / 1000, label,
                a.frames(),
                String.format("%.1f µs", (a.cpuUsPerFrame() + b.cpuUsPerFrame()) / 2),
                String.format("%.1f µs", (a.avgSpinUs() + b.avgSpinUs()) / 2),
                predictedNs == 0 ? "—" : String.format("%.1f µs", predictedNs / 1000.0));
    }

    private record Row(long frames, double cpuUsPerFrame, double avgSpinUs) { }

    private static Row measureModern(long renderNs, int seconds, int targetFps) {
        long[] spinTotal = {0};
        FrameLimiter.INSTANCE.reset();
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);
        FrameLimiter.INSTANCE.onSpin = spin -> spinTotal[0] += spin;

        long frames = runLoop(renderNs, seconds,
                () -> FrameLimiter.INSTANCE.limitFrame());

        return new Row(frames,
                lastCpuUsPerFrame, spinTotal[0] / (double) Math.max(1L, frames) / 1000.0);
    }

    private static Row measureLegacy(long renderNs, int seconds, int targetFps) {
        long[] spinTotal = {0};
        LegacyFrameLimiter legacy = new LegacyFrameLimiter();
        legacy.setEnabled(true);
        legacy.setMonitorRefreshRate(targetFps);
        legacy.onSpin = spin -> spinTotal[0] += spin;

        long frames = runLoop(renderNs, seconds, legacy::limitFrame);

        return new Row(frames,
                lastCpuUsPerFrame, spinTotal[0] / (double) Math.max(1L, frames) / 1000.0);
    }

    private static double lastCpuUsPerFrame;

    private static long runLoop(long renderNs, int seconds, Runnable limit) {
        long wallStart = System.nanoTime();
        long cpuStart = currentThreadCpuNs();
        long frames = 0;
        long deadline = wallStart + seconds * 1_000_000_000L;

        while (System.nanoTime() < deadline) {
            burn(renderNs);
            limit.run();
            frames++;
        }

        double wallSec = (System.nanoTime() - wallStart) / 1e9;
        double cpuMs = (currentThreadCpuNs() - cpuStart) / 1_000_000.0;
        lastCpuUsPerFrame = cpuMs * 1000.0 / Math.max(1L, frames);
        return frames;
    }

    private static void warmUp(long budgetNs, int targetFps) {
        for (int i = 0; i < 300; i++) {
            burn(budgetNs / 4);
            FrameLimiter.INSTANCE.reset();
            FrameLimiter.INSTANCE.setEnabled(true);
            FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);
            FrameLimiter.INSTANCE.limitFrame();
            LegacyFrameLimiter legacy = new LegacyFrameLimiter();
            legacy.setEnabled(true);
            legacy.setMonitorRefreshRate(targetFps);
            legacy.limitFrame();
        }
    }

    private static void burn(long nanos) {
        if (nanos <= 0) return;
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    /**
     * Ana thread CPU zamanı. Süreç CPU'su JIT/GC'yi kapsar ve sınırlayıcının
     * maliyetini gizler; süreç bazlı thread istatistiği bu ortamda güncellenmediği
     * için ThreadMXBean kullanılıyor.
     */
    private static long currentThreadCpuNs() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean.isCurrentThreadCpuTimeSupported() && !bean.isThreadCpuTimeEnabled()) {
            bean.setThreadCpuTimeEnabled(true);
        }
        return bean.getCurrentThreadCpuTime();
    }

}
