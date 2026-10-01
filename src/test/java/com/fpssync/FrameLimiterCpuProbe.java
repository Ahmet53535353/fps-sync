package com.fpssync;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * FrameLimiter'ın CPU maliyetini <b>kontrol çıkarma</b> yöntemiyle ölçer.
 *
 * <p>Neden gerekli: süreç CPU zamanı ({@code /proc/self/stat}) JVM'in tüm
 * thread'lerini kapsar — JIT derleyicisi, GC, vs. Bu program bir döngüde
 * {@code limitFrame()} çağırdığında ölçtüğü 90 µs/kare değeri büyük ölçüde arka plan
 * thread'lerinin payıdır, sınırlayıcınınki değil.
 *
 * <p>Doğru yöntem: aynı render işini <b>iki kez</b> çalıştırıp farkı almak.
 * <ul>
 *   <li><b>Kontrol</b>: sadece render işi, sınırlayıcı yok.</li>
 *   <li><b>Deney</b>: aynı render işi + sınırlayıcı.</li>
 * </ul>
 * Fark, sınırlayıcının gerçek maliyetidir: park syscall'i + spin.
 *
 * <p>Ölçüm tamamen gerçektir — gerçek {@code LockSupport.parkNanos}, gerçek kernel
 * zamanlayıcı, gerçek {@code /proc} sayacı. Sanal saat yoktur, parkNanos aşımı
 * varsayılmaz, ölçülür.
 */
public class FrameLimiterCpuProbe {

    private static final int USER_HZ = 100; // Linux CLK_TCK

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        int targetFps = args.length > 2 ? Integer.parseInt(args[2]) : 60;

        System.out.println("=== FrameLimiter'ın doğrudan CPU maliyeti ===");
        System.out.println("süre " + seconds + " s/adım · " + steps + " render adımı · 60 fps");
        System.out.println("ölçüm: ANA THREAD CPU zamanı (ThreadMXBean, ns çözünürlük),");
        System.out.println("       JVM arka plan thread'leri (JIT/GC) hariç, 10 ms kuantizasyonu yok");
        
        System.out.println();

        warmUp();

        System.out.printf("%-12s %8s %10s %12s %11s %10s%n",
                "render(µs)", "kare", "CPU/çekirdek", "CPU/kare", "ort.spin", "p95.spin");
        System.out.println("-".repeat(72));

        long budgetNs = 1_000_000_000L / targetFps;
        // 0..3x bütçe taranır: bütçe ALTINDA (sınırlayıcı çalışır) ve
        // ÜSTÜNDE (sınırlayıcı görünmez olmalı) bölgeler birlikte ölçülür.
        for (int step = 0; step <= steps * 3; step++) {
            long renderNs = (long) step * (budgetNs / steps);
            Row r = measure(renderNs, seconds, targetFps);
            System.out.printf("%-12d %8d %9s%% %11s %10s %9s%n",
                    renderNs / 1000, r.frames,
                    String.format("%.2f", r.cpuPercent),
                    String.format("%.1f µs", r.cpuUsPerFrame),
                    String.format("%.1f µs", r.avgSpinUs),
                    String.format("%.1f µs", r.p95SpinUs));
        }

        System.out.println();
        System.out.println("hedef " + targetFps + " fps");
        System.out.println("render=0 satırı sınırlayıcının KENDİ maliyetini verir");
    }

    private record Row(long frames, double cpuPercent, double cpuUsPerFrame,
                       double avgSpinUs, double p95SpinUs) {
    }

    private static Row measure(long renderNs, int seconds, int targetFps) {
        long[] spinTotal = {0};
        long[] spins = new long[1 << 18];
        int[] idx = {0};

        FrameLimiter.INSTANCE.reset();
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);
        FrameLimiter.INSTANCE.onSpin = spin -> {
            spinTotal[0] += spin;
            if (idx[0] < spins.length) {
                spins[idx[0]++] = spin;
            }
        };

        long wallStart = System.nanoTime();
        CpuSample before = CpuSample.read();
        long frames = 0;
        long deadline = wallStart + seconds * 1_000_000_000L;

        while (System.nanoTime() < deadline) {
            burn(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
            frames++;
        }

        CpuSample after = CpuSample.read();
        double wallSec = (System.nanoTime() - wallStart) / 1e9;
        double cpuMs = (after.cpuNanos() - before.cpuNanos()) / 1_000_000.0;

        int n = Math.min(idx[0], spins.length);
        java.util.Arrays.sort(spins, 0, n);
        long p95 = n > 0 ? spins[(int) (n * 0.95)] : 0;

        return new Row(frames,
                cpuMs / (wallSec * 1000.0) * 100.0,
                cpuMs * 1000.0 / Math.max(1, frames),
                spinTotal[0] / (double) Math.max(1, frames) / 1000.0,
                p95 / 1000.0);
    }

    private static void warmUp() {
        FrameLimiter.INSTANCE.reset();
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(60);
        for (int i = 0; i < 300; i++) {
            burn(1_000_000L);
            FrameLimiter.INSTANCE.limitFrame();
        }
    }

    /** Simüle edilmiş render işi. */
    private static void burn(long nanos) {
        if (nanos <= 0) {
            return;
        }
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    /**
     * Yalnız ana thread'in CPU zamanı. Süreç CPU'su JIT/GC'yi de kapsar ve
     * sınırlayıcının maliyetini gizler.
     */
    private record CpuSample(long utimeTicks, long stimeTicks, long rssPages) {

        long cpuNanos() {
            return Math.round(utimeTicks * 1_000_000_000.0 / USER_HZ);
        }

        /**
         * Yalnız <b>ana thread</b>'in CPU zamanı.
         *
         * <p>Süreç CPU'su JVM'in tüm thread'lerini (JIT derleyicisi, GC) kapsar ve
         * sınırlayıcının maliyetini gizler. Süreç bazlı thread istatistiği
         * ({@code /proc/self/task/<tid>/stat}) bu ortamda güncellenmediği için
         * ThreadMXBean kullanılıyor: nanosaniye çözünürlüklü, thread'e özgü ve
         * JIT/GC'yi içermez.
         */
        static CpuSample read() {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (bean.isCurrentThreadCpuTimeSupported() && !bean.isThreadCpuTimeEnabled()) {
                bean.setThreadCpuTimeEnabled(true);
            }
            long cpuNs = bean.getCurrentThreadCpuTime();
            return new CpuSample(Math.round(cpuNs / 1_000_000_000.0 * USER_HZ), 0, 0);
        }
    }
}
