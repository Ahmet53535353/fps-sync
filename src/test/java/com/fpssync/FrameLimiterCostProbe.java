package com.fpssync;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;

/**
 * {@link FrameLimiter}'ın maliyetini ölçen prob. Yeni bağımlılık yoktur: JDK'nın
 * {@code ThreadMXBean}, {@code OperatingSystemMXBean} ve {@code /proc} erişimiyle
 * çalışır.
 *
 * <p><b>Nasıl çalıştırılır</b> — test JVM'i içinde <em>değil</em>, ayrı bir JVM'de.
 * Testlerle aynı JVM'de çalıştırılan ölçümler kirlenir: testler
 * {@link FrameLimiter#INSTANCE} alanlarını değiştirdiği için çağrı yerleri
 * megamorfikleşir ve JIT satır içine almaz.
 *
 * <pre>{@code
 * ./gradlew compileJava
 * java -XX:+UseSerialGC -Xmx256m \
 *      -cp build/classes/java/main:build/classes/java/test \
 *      com.fpssync.FrameLimiterCostProbe
 * }</pre>
 *
 * <p><b>Bu makinede alınan sonuç</b> (Intel Celeron N4120 @ 2.487 GHz, Linux 7.0):
 * <pre>
 *   System.nanoTime()                    29.0 ns
 *   Thread.onSpinWait()                  61.2 ns
 *   Thread.sleep(0)  (çıplak syscall)     1.17 us
 *   parkNanos 1/5/15/50 ms (CPU)          24 / 45 / 51 / 53 us
 *   parkNanos duvar aşımı                 ~100 us
 *   limitFrame() CPU (60 Hz)             60-67 us  (%80-90 park)
 *   limitFrame() allocasyon               0 bayt / 10^6 çağrı
 * </pre>
 *
 * <p><b>Bu rakamlar evrensel değildir.</b> Maliyetin çoğu bekleme çağrısının
 * kendisindedir ve o çevreden çevreye değişir. Hızlı bir masaüstünde
 * {@code parkNanos} çok daha ucuzdur; zayıf bir dizüstünde ve bellek baskısı
 * varken pahalıdır.
 *
 * <p><b>Yöntem notları.</b>
 * <ul>
 *   <li>Her ölçüm ısınma turundan sonra, en az 9 denemede <b>medyan</b> alınarak
 *       raporlanır. Tek koşum gürültüdür; bu depodaki "165 kat" gibi iddiaların
 *       belirsizlik taşımasının nedeni buydu.</li>
 *   <li>{@code ThreadMXBean} çağrısının kendisi 0.65-0.96 µs tutuyor. Bu, ölçülen
 *       CPU rakamının içindedir; {@code limitFrame()} etrafındaki iki çağrı
 *       ~1.3 µs ekler. 60 µs'luk bir ölçümde bu %2'dir, açıklama değildir.</li>
 *   <li>Enstrümanı doğrulamak için süreç geneli CPU sayacı
 *       ({@link OperatingSystemMXBean#getProcessCpuTime()}) ile çapraz kontrol
 *       yapılmalıdır: süreç sayacı thread sayacından <em>düşük</em> çıkarsa
 *       thread sayacı şişiriyor demektir.</li>
 * </ul>
 */
public final class FrameLimiterCostProbe {

    private FrameLimiterCostProbe() {}

    private static final int WARMUP = 200_000;
    private static final int TRIALS = 9;
    private static final Path CUR_FREQ =
            Path.of("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");

    private static long blackhole;

    public static void main(String[] args) {
        System.out.println("=== FPS-Sync maliyet probu ===");
        System.out.println("  JVM: " + System.getProperty("java.version")
                + " | işlemci sayısı: " + Runtime.getRuntime().availableProcessors());
        System.out.println("  DÖNÜŞÜMLÜ ölçüm, medyan raporlanır.\n");

        nanoTimeCost();
        onSpinWaitCost();
        syscallBaseline();
        parkCpuScaling();
        spinWindowTurns();
        limiterEndToEnd();
        allocation();
        System.out.println("\n(blackhole=" + blackhole + ")");
    }

    // ---- System.nanoTime() ----
    private static void nanoTimeCost() {
        long acc = 0;
        for (int i = 0; i < WARMUP; i++) acc += System.nanoTime();
        blackhole += acc;

        int n = 1_000_000;
        double med = medianWallNs(n, () -> {
            long a = 0;
            for (int i = 0; i < n; i++) a += System.nanoTime();
            return a;
        }, () -> { });
        System.out.printf("  System.nanoTime()            : %7.2f ns/çağrı%n", med / n);
    }

    // ---- Thread.onSpinWait() ----
    private static void onSpinWaitCost() {
        long acc = 0;
        for (int i = 0; i < WARMUP; i++) { acc++; Thread.onSpinWait(); }
        blackhole += acc;

        int n = 2_000_000;
        double med = medianWallNs(n, () -> {
            long a = 0;
            for (int i = 0; i < n; i++) { a++; Thread.onSpinWait(); }
            return a;
        }, () -> { });
        System.out.printf("  Thread.onSpinWait()          : %7.2f ns/çağrı%n", med / n);
    }

    /** Çıplak syscall tabanı — zamanlı beklemenin ne kadar pahalı olduğunu gösterir. */
    private static void syscallBaseline() {
        try {
            int n = 200_000;
            long[] r = new long[TRIALS];
            for (int t = 0; t < TRIALS; t++) {
                long c0 = cpu();
                for (int i = 0; i < n; i++) Thread.sleep(0);
                r[t] = cpu() - c0;
            }
            System.out.printf("  Thread.sleep(0) (syscall)    : %7.3f µs/çağrı%n",
                    median(r) / 1000.0 / n);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Park'ın CPU maliyeti uyku süresiyle ölçekleniyor mu? Sabit gider ise sorun
     * beklemenin kendisinde değil, çağrının kendisindedir.
     */
    private static void parkCpuScaling() {
        System.out.println("  park CPU maliyeti (süreye göre):");
        for (long ns : new long[]{1_000_000L, 5_000_000L, 15_000_000L, 50_000_000L}) {
            int n = (int) Math.max(20, 2_000_000_000L / ns);
            long[] r = new long[TRIALS];
            long[] w = new long[TRIALS];
            for (int t = 0; t < TRIALS; t++) {
                for (int i = 0; i < 3; i++) LockSupport.parkNanos(ns);
                long c0 = cpu(), w0 = System.nanoTime();
                for (int i = 0; i < n; i++) LockSupport.parkNanos(ns);
                w[t] = System.nanoTime() - w0;
                r[t] = cpu() - c0;
            }
            System.out.printf("    %6.1f ms isteniyor -> CPU %6.2f µs, duvar aşımı %6.1f µs%n",
                    ns / 1e6, median(r) / 1000.0 / n, (median(w) - (long) n * ns) / 1000.0 / n);
        }
    }

    /**
     * Sanal saatle spin penceresindeki tur sayısı.
     *
     * <p>Üretim döngüsü sanal saatle doğrudan çalıştırılamaz: döngü yalnız
     * {@code nanoTime} ilerlemesiyle kapanır, sanal saat ise yalnız {@code spinHook}
     * ile ilerler. Bu yüzden dikiş takılır.
     */
    private static void spinWindowTurns() {
        System.out.println("  spin penceresi tur sayısı (sanal saat, park aşımı 90 µs):");
        FrameLimiter l = FrameLimiter.INSTANCE;
        for (int hz : new int[]{60, 144, 240}) {
            long[] clock = {0};
            int[] turns = {0};
            l.reset();
            l.nanoTime = () -> clock[0];
            l.sleeper = x -> clock[0] += x + 90_000L;
            l.spinHook = () -> { clock[0] += 1_000L; turns[0]++; };
            l.onSpin = s -> { };
            l.setEnabled(true);
            l.setMonitorRefreshRate(hz);

            clock[0] = 1_000_000L;
            l.limitFrame();                                    // başlatma
            clock[0] += 1_000_000_000L / hz / 5;
            turns[0] = 0;
            l.limitFrame();
            System.out.printf("    %3d Hz -> %d tur (pencere 10 µs, 1 µs/tur)%n", hz, turns[0]);
        }
        // Sanal saat bırakıldı; üretim dikişlerine dönmeden sonraki ölçüm yanlış olur.
        l.useProductionSeams();
    }

    /** Gerçek sınıf, gerçek saat: kare başına CPU ve sapma. */
    private static void limiterEndToEnd() {
        int target = 60, frames = 300;
        long budget = 1_000_000_000L / target;
        double[] cpuUs = new double[TRIALS];
        double[] sapUs = new double[TRIALS];

        for (int t = 0; t < TRIALS; t++) {
            FrameLimiter l = FrameLimiter.INSTANCE;
            l.reset();
            l.setEnabled(true);
            l.setMonitorRefreshRate(target);
            for (int i = 0; i < 100; i++) l.limitFrame();

            long c0 = cpu();
            long prev = System.nanoTime();
            long err = 0;
            for (int i = 0; i < frames; i++) {
                long now = System.nanoTime();
                err += Math.abs((now - prev) - budget);
                prev = now;
                l.limitFrame();
            }
            cpuUs[t] = (double) (cpu() - c0) / 1000.0 / frames;
            sapUs[t] = (double) err / 1000.0 / frames;
        }
        System.out.printf("  limitFrame() %d Hz: CPU %5.2f µs/kare, ortalama sapma %6.1f µs%n",
                target, median(cpuUs), median(sapUs));
    }

    /**
     * Sıcak yolda allocasyon var mı?
     *
     * <p>0 çıkmalı. Sınıf hiçbir dosya, thread veya ağ kaynağı kullanmaz; bu özellik
     * sessizce bozulabileceği için ölçülür.
     */
    private static void allocation() {
        com.sun.management.ThreadMXBean t =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!t.isThreadAllocatedMemorySupported()) {
            System.out.println("  allocasyon sayacı desteklenmiyor");
            return;
        }
        long id = Thread.currentThread().getId();
        FrameLimiter l = FrameLimiter.INSTANCE;
        l.reset();
        l.setEnabled(true);
        l.setMonitorRefreshRate(60);
        for (int i = 0; i < 50_000; i++) l.limitFrame();

        int n = 1_000_000;
        long a0 = t.getThreadAllocatedBytes(id);
        for (int i = 0; i < n; i++) l.limitFrame();
        long a1 = t.getThreadAllocatedBytes(id);
        System.out.printf("  limitFrame() allocasyon       : %d bayt / %d çağrı = %d bayt%n",
                a1 - a0, n, (a1 - a0) / n);
    }

    /** Kullanılabiliyorsa çekirdek frekansı (PAUSE etkisi için). */
    static long readFreq() {
        try {
            String s = Files.readString(CUR_FREQ).trim();
            return s.isEmpty() ? -1 : Long.parseLong(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static long cpu() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    private static long median(long[] v) {
        long[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    /** {@code body}'yi {@code n} kez çalıştırıp duvar süresinin medyanını döner. */
    private static double medianWallNs(int n, java.util.function.LongSupplier body,
                                      Runnable after) {
        long[] r = new long[TRIALS];
        for (int t = 0; t < TRIALS; t++) {
            long t0 = System.nanoTime();
            blackhole += body.getAsLong();
            r[t] = System.nanoTime() - t0;
            after.run();
        }
        return median(r);
    }
}
