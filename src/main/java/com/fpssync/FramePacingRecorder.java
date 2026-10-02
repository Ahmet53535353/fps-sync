package com.fpssync;

/**
 * Kare zamanlamasını ölçer. Kütüphane ve Minecraft bağımlılığı yoktur.
 *
 * <h2>Neden kare süresi değil gecikme</h2>
 * Ham kare süresi hedefi bilmeyi gerektirir: "p99 = 7.41 ms" dediğimizde karşılaştırılacak
 * bütçeyi hatırlamak gerekir. Gecikme ({@code kare − bütçe}) ise hedef 60 Hz de olsa
 * 240 Hz de olsa aynı ölçekte durur ve "geç kare" doğrudan {@code gecikme > 0} sayımıdır.
 *
 * <h2>Neden iki rejim</h2>
 * Oyun başlangıcında kare hızı düşüktür ve sınırlayıcı hiç beklemez; oyun ısındıkça hız
 * yükselir ve sınırlayıcı devreye girer. Tek blok hâlinde biriktirilseydi istatistiğin
 * ezici çoğunluğu sınırlayıcının <em>çalışmadığı</em> karelerden gelirdi. İki rejim hem
 * sınırlayıcının ne yaptığını hem <b>ne zaman devreye girdiğini</b> gösterir.
 *
 * <h2>Sıcak yol maliyeti</h2>
 * Kare başına yalnızca ilkel alan artışı ve bir dizi indeksi. Nesne, lambda ya da boxing
 * yoktur; histogramlar {@link #reset} dışında asla ayrılmaz. Bekleme yapılmayan karelerde
 * park/spin sayaçları hiç artmadığı için o rejimde ölçüm maliyeti sıfıra yakındır.
 *
 * <p>Yüzde hesabı histogramdan okur; kare süresi <b>kırpılmaz</b>, çünkü kırpmak tam olarak
 * aranan bilgiyi yok eder. Kova sınırını aşan değerler taşma sayacına düşer ve tam
 * maksimum ayrıca tutulur.
 *
 * @see FpsSyncStatusCommand
 */
public final class FramePacingRecorder {

    /** İnce gecikme histogramının üst sınırı: 50 ms. */
    public static final long LATE_FINE_MAX_NS = 50_000_000L;

    /** İnce gecikme kovasının genişliği: 0.05 ms. */
    public static final long LATE_FINE_STEP_NS = 50_000L;

    /** Park aşımı histogramının üst sınırı: 1 ms. */
    public static final long OVERSHOOT_MAX_NS = 1_000_000L;

    /** Park aşımı kovasının genişliği: 2 µs. */
    public static final long OVERSHOOT_STEP_NS = 2_000L;

    static final int LATE_BUCKETS = (int) (LATE_FINE_MAX_NS / LATE_FINE_STEP_NS) + 1;
    static final int OVERSHOOT_BUCKETS = (int) (OVERSHOOT_MAX_NS / OVERSHOOT_STEP_NS) + 1;

    /** Bir rejimin tüm sayacı ve histogramı. */
    private static final class Regime {
        long frames;
        long lateFrames;
        long totalFrameNs;
        long latenessOverflowFrames;
        long latenessMaxNs;
        long parkCalls;
        long parkNsTotal;
        long overshootOverflow;
        long overshootMaxNs;
        long spinNsTotal;
        long spinEntries;
        final int[] lateness = new int[LATE_BUCKETS];
        final int[] overshoot = new int[OVERSHOOT_BUCKETS];

        void clear() {
            frames = 0;
            lateFrames = 0;
            totalFrameNs = 0;
            latenessOverflowFrames = 0;
            latenessMaxNs = 0;
            parkCalls = 0;
            parkNsTotal = 0;
            overshootOverflow = 0;
            overshootMaxNs = 0;
            spinNsTotal = 0;
            spinEntries = 0;
            java.util.Arrays.fill(lateness, 0);
            java.util.Arrays.fill(overshoot, 0);
        }
    }

    private final Regime active = new Regime();
    private final Regime idle = new Regime();

    private long elapsedNs;
    private long firstWaitAtNs = -1;
    private volatile boolean paused;

    /**
     * Bir kareyi kaydeder.
     *
     * <p>Sıcak yoldan çağrılır; ayırma, tahsis veya boxing yapmaz.
     *
     * @param frameNs       kare süresi
     * @param budgetNs      hedef kare bütçesi
     * @param waited        sınırlayıcı gerçekten bekledi mi
     * @param parkCalls     bu karedeki park çağrısı sayısı
     * @param parkNs        park için istenen süre toplamı
     * @param overshootNs   park çağrılarının ortalama aşımı (0 ise kaydedilmez)
     * @param spinNs        bu karede harcanan spin süresi
     */
    public void recordFrame(long frameNs, long budgetNs, boolean waited,
            int parkCalls, long parkNs, long overshootNs, long spinNs) {
        if (paused) {
            return;
        }
        elapsedNs += frameNs;
        Regime r = waited ? active : idle;
        r.frames++;
        r.totalFrameNs += frameNs;

        long lateness = frameNs - budgetNs;
        if (lateness > 0) {
            r.lateFrames++;
            if (lateness > r.latenessMaxNs) {
                r.latenessMaxNs = lateness;
            }
            if (lateness < LATE_FINE_MAX_NS) {
                r.lateness[(int) (lateness / LATE_FINE_STEP_NS)]++;
            } else {
                r.latenessOverflowFrames++;
            }
        }

        if (waited && firstWaitAtNs < 0) {
            // "İlk bekleme" park'a değil "bekledi" olayına bağlanır: yalnız spin yapan
            // kare de bekmedir, park çağrısı yapmadan.
            firstWaitAtNs = elapsedNs;
        }

        if (parkCalls > 0) {
            r.parkCalls += parkCalls;
            r.parkNsTotal += parkNs;
            if (overshootNs > 0) {
                if (overshootNs > r.overshootMaxNs) {
                    r.overshootMaxNs = overshootNs;
                }
                if (overshootNs < OVERSHOOT_MAX_NS) {
                    r.overshoot[(int) (overshootNs / OVERSHOOT_STEP_NS)]++;
                } else {
                    r.overshootOverflow++;
                }
            }
        }

        if (spinNs > 0) {
            r.spinNsTotal += spinNs;
            r.spinEntries++;
        }
    }

    /** Rapor üretimi sırasında ölçümü durdurmak için. */
    public void setPaused(boolean value) {
        paused = value;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Ölçüm başlangıcını temizler. Geçmiş histogramlar da silinir. */
    public void reset() {
        active.clear();
        idle.clear();
        elapsedNs = 0;
        firstWaitAtNs = -1;
    }

    /** Geçen süreyi dışarıdan doğrular (testler ve saat kayması düzeltmesi). */
    public void advanceElapsedNs(long ns) {
        if (!paused) {
            elapsedNs += ns;
        }
    }

    public long elapsedNs() {
        return elapsedNs;
    }

    /**
     * Gerçek FPS: kare sayısı ÷ geçen süre.
     *
     * <p>Anlık FPS'lerin ortalaması <b>kullanılmaz</b>: hızlı kareler ortalamada daha
     * çok ağırlık kazanır ve kimsenin yaşamadığı bir sayı verir.
     */
    public double actualFps() {
        return elapsedNs <= 0 ? 0.0 : (active.frames + idle.frames) * 1_000_000_000.0 / elapsedNs;
    }

    /** Sınırlayıcının ilk kez beklediği ana kadarki geçen süre; hiç beklemediyse −1. */
    public long firstWaitAtNs() {
        return firstWaitAtNs;
    }

    private static long percentile(int[] hist, long count, long step, double q) {
        if (count <= 0) {
            return 0;
        }
        long target = (long) Math.ceil(q * count);
        if (target < 1) {
            target = 1;
        }
        long seen = 0;
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= target) {
                // Kova alt sınırı: değer [i·step, (i+1)·step) aralığında.
                return i * step;
            }
        }
        return (hist.length - 1) * step;
    }

    // --- bekleyen (sınırlayıcı aktif) rejim ---

    public long activeFrames() {
        return active.frames;
    }

    public long activeLateFrames() {
        return active.lateFrames;
    }

    public long activeLatenessMaxNs() {
        return active.latenessMaxNs;
    }

    public long activeLatenessOverflowFrames() {
        return active.latenessOverflowFrames;
    }

    public long activeLatenessMedianNs() {
        return percentile(active.lateness, active.lateFrames, LATE_FINE_STEP_NS, 0.50);
    }

    public long activeLatenessPercentileNs(double q) {
        return percentile(active.lateness, active.lateFrames, LATE_FINE_STEP_NS, q);
    }

    public long activeParkCalls() {
        return active.parkCalls;
    }

    public long activeParkNsTotal() {
        return active.parkNsTotal;
    }

    public long activeOvershootMaxNs() {
        return active.overshootMaxNs;
    }

    public long activeOvershootMedianNs() {
        return percentile(active.overshoot, active.parkCalls, OVERSHOOT_STEP_NS, 0.50);
    }

    public long activeOvershootPercentileNs(double q) {
        return percentile(active.overshoot, active.parkCalls, OVERSHOOT_STEP_NS, q);
    }

    public long activeSpinNsTotal() {
        return active.spinNsTotal;
    }

    public long activeSpinEntries() {
        return active.spinEntries;
    }

    // --- boşta (sınırlayıcı beklememiş) rejim ---

    public long idleFrames() {
        return idle.frames;
    }

    public long idleLateFrames() {
        return idle.lateFrames;
    }

    public long idleLatenessMaxNs() {
        return idle.latenessMaxNs;
    }

    public long idleParkCalls() {
        return idle.parkCalls;
    }

    public long idleSpinNsTotal() {
        return idle.spinNsTotal;
    }

    /** Boşta rejimde geçen süre — başlangıç aşamasının uzunluğunu verir. */
    public long idleElapsedNs() {
        return idle.totalFrameNs;
    }
}