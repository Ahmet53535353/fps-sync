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

    /**
     * Boş kayıtçı: henüz hiç kare kaydedilmemiş.
     */
    public FramePacingRecorder() {
    }

    /** İnce gecikme histogramının üst sınırı: 50 ms. */
    public static final long LATE_FINE_MAX_NS = 50_000_000L;

    /** İnce gecikme kovasının genişliği: 0.05 ms. */
    public static final long LATE_FINE_STEP_NS = 50_000L;

    /**
     * Park aşımı histogramının üst sınırı: 64 ms.
     *
     * <p>Değer 1 ms'de <b>değildir</b>. 2026-10-02'deki gerçek koşuda en kötü aşım
     * 35.8 ms çıktı ve medyan 1 ms'in üstündeydi; 1 ms'lik tavan, kararın verilmesi
     * gereken aralığın tamamını taşma sayacına atıyordu.
     */
    public static final long OVERSHOOT_MAX_NS = 64_000_000L;

    /**
     * Park aşımı kovasının genişliği: 10 µs.
     *
     * <p>1 ms ile 1.5 ms'yi ayırt etmeye yeter. Daha ince çözünürlük kova sayısını
     * bellek maliyetiyle büyütür, kararı değiştirmez.
     */
    public static final long OVERSHOOT_STEP_NS = 10_000L;

    /**
     * İstenen yüzdelik histogramın dışına düştüğünde dönen değer.
     *
     * <p>Eski davranış son kovanın değerini döndürüyordu; bu, "ölçemedim" ile
     * "tam olarak bu değer" arasındaki farkı siliyordu. Gerçek koşuda 1 ms'lik
     * tavanda medyan <em>ve</em> p95 için tam olarak 1000.0 µs basıyordu.
     */
    public static final long SATURATED = -1L;

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
        long parkEarlyCalls;
        long parkEarlyNsTotal;
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
            parkEarlyCalls = 0;
            parkEarlyNsTotal = 0;
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
            if (overshootNs < 0) {
                // Park istenenden ERKEN döndü: kalan süre spin ile yakıldı.
                // Negatif aşım önceden 0'a yassılanıyordu, bu yüzden spin'in neden
                // 100 µs'luk pencerenin çok üstüne çıktığı görünmüyordu.
                r.parkEarlyCalls++;
                r.parkEarlyNsTotal += -overshootNs;
            } else if (overshootNs > 0) {
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
    /**
     * Ölçümü durdurur veya başlatır.
     *
     * @param value {@code true} ise yeni kareler kaydedilmez
     */
    public void setPaused(boolean value) {
        paused = value;
    }

    /**
     * Ölçümün duraklatılıp duraklatılmadığını söyler.
     *
     * @return ölçüm şu anda duraklatılmışsa {@code true}
     */
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
    /**
     * Geçen süreyi dışarıdan doğrular (testler ve saat kayması düzeltmesi).
     *
     * @param ns ekleneceği bildirilen süre
     */
    public void advanceElapsedNs(long ns) {
        if (!paused) {
            elapsedNs += ns;
        }
    }

    /**
     * Ölçüm başlangıcından beri geçen süre.
     *
     * @return nanosaniye cinsinden geçen süre
     */
    public long elapsedNs() {
        return elapsedNs;
    }

    /**
     * Gerçek FPS: kare sayısı ÷ geçen süre.
     *
     * <p>Anlık FPS'lerin ortalaması <b>kullanılmaz</b>: hızlı kareler ortalamada daha
     * çok ağırlık kazanır ve kimsenin yaşamadığı bir sayı verir.
     *
     * @return ölçülen gerçek FPS; henüz süre geçmediyse 0
     */
    public double actualFps() {
        return elapsedNs <= 0 ? 0.0 : (active.frames + idle.frames) * 1_000_000_000.0 / elapsedNs;
    }

    /**
     * Sınırlayıcının ilk kez beklediği ana kadarki geçen süre.
     *
     * @return geçen süre; hiç beklemediyse −1
     */
    public long firstWaitAtNs() {
        return firstWaitAtNs;
    }

/**
     * Histogramdan yüzdelik hesaplar.
     *
     * @param hist  kova sayacı
     * @param count toplam örnek sayısı (taşma dahil)
     * @param step  kova genişliği
     * @param q     istenen yüzdelik
     * @return değer; örneklerin yeterli kısmı histogramın dışındaysa {@link #SATURATED}
     */
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
// Örneklerin bu kadarı histogramın dışında: yüzdelik burada çözülemez.
          // Sessizce son kova değerini döndürmek "ölçemedim" ile "tam bu değer"
          // arasındaki farkı siliyordu; gerçek koşuda 1000.0 µs gibi görünüyordu.
          return SATURATED;
      }

    // --- bekleyen (sınırlayıcı aktif) rejim ---

    /**
     * Sınırlayıcı beklerken geçen kare sayısı.
     * @return sınırlayıcı beklerken geçen kare sayısı
     */
    public long activeFrames() {
        return active.frames;
    }

    /**
     * Sınırlayıcı beklerken hedefi aşan kare sayısı.
     * @return sınırlayıcı beklerken hedefi aşan kare sayısı
     */
    public long activeLateFrames() {
        return active.lateFrames;
    }

    /**
     * Sınırlayıcı beklerken ölçülen en büyük gecikme.
     * @return sınırlayıcı beklerken ölçülen en büyük gecikme, nanosaniye
     */
    public long activeLatenessMaxNs() {
        return active.latenessMaxNs;
    }

    /**
     * Gecikme histogramının taştığı kare sayısı (üst sınır 50 ms).
     * @return gecikme histogramının taştığı kare sayısı (üst sınır 50 ms)
     */
    public long activeLatenessOverflowFrames() {
        return active.latenessOverflowFrames;
    }

    /**
     * Sınırlayıcı beklerken gecikme medyanı.
     * @return sınırlayıcı beklerken gecikme medyanı, nanosaniye
     */
    public long activeLatenessMedianNs() {
        return percentile(active.lateness, active.lateFrames, LATE_FINE_STEP_NS, 0.50);
    }

    /**
     * Sınırlayıcı beklerken gecikmenin istenen yüzdeliği.
     * @param q istenen yüzdelik, 0–1 arası
     * @return gecikmenin istenen yüzdeliği, nanosaniye
     */
    public long activeLatenessPercentileNs(double q) {
        return percentile(active.lateness, active.lateFrames, LATE_FINE_STEP_NS, q);
    }

    /**
     * Sınırlayıcı beklerken park çağrısı sayısı.
     * @return sınırlayıcı beklerken park çağrısı sayısı
     */
    public long activeParkCalls() {
        return active.parkCalls;
    }

    /**
     * Park için istenen sürenin toplamı.
     * @return park için istenen sürenin toplamı, nanosaniye
     */
    public long activeParkNsTotal() {
        return active.parkNsTotal;
    }

    /**
     * Park'ın en büyük aşımı.
     * @return park'ın en büyük aşımı, nanosaniye
     */
public long activeOvershootMaxNs() {
          return active.overshootMaxNs;
      }

      /**
       * Sınırlayıcı beklerken histogramın üst sınırını aşan aşım sayısı.
       *
       * <p>Bu sayı sıfır değilse ilgili yüzdelikler {@link #SATURATED} döner. Daha önce
       * bu sayaç yalnızca içeride tutuluyor, raporda hiç görünmüyordu; o yüzden
       * "ölçemedim" ile "aşım tam 1 ms" ayırt edilemiyordu.
       *
       * @return tavanı aşan park çağrısı sayısı
       */
      public long activeOvershootOverflow() {
          return active.overshootOverflow;
      }

      /**
       * Park'ın istenenden <em>erken</em> döndüğü çağrı sayısı.
       *
       * <p>Eski ölçümde negatif aşım 0'a yassılanıyordu. Bu yüzden kalan sürenin
       * spin ile yakıldığı miktar görünmüyor ve spin'in neden 100 µs'luk pencerenin
       * çok üstüne çıktığı açıklanamıyordu.
       *
       * @return erken dönüş sayısı
       */
      public long activeParkEarlyCalls() {
          return active.parkEarlyCalls;
      }

      /**
       * Erken dönüşlerin toplam büyüklüğü.
       *
       * @return erken dönen sürenin toplamı, nanosaniye
       */
      public long activeParkEarlyNsTotal() {
          return active.parkEarlyNsTotal;
      }

    /**
     * Park aşımının medyanı.
     *
     * @return nanosaniye cinsinden medyan aşım
     */
    public long activeOvershootMedianNs() {
        return percentile(active.overshoot, active.parkCalls, OVERSHOOT_STEP_NS, 0.50);
    }

    /**
     * Park aşımının istenen yüzdeliği.
     * @param q istenen yüzdelik, 0–1 arası
     * @return park aşımının istenen yüzdeliği, nanosaniye
     */
    public long activeOvershootPercentileNs(double q) {
        return percentile(active.overshoot, active.parkCalls, OVERSHOOT_STEP_NS, q);
    }

    /**
     * Spin'de harcanan toplam süre.
     * @return spin'de harcanan toplam süre, nanosaniye
     */
    public long activeSpinNsTotal() {
        return active.spinNsTotal;
    }

    /**
     * Spin döngüsüne girilen kere sayısı.
     * @return spin döngüsüne girilen kere sayısı
     */
    public long activeSpinEntries() {
        return active.spinEntries;
    }

    // --- boşta (sınırlayıcı beklememiş) rejim ---

    /**
     * Sınırlayıcı beklemediği kare sayısı.
     * @return sınırlayıcı beklemediği kare sayısı
     */
    public long idleFrames() {
        return idle.frames;
    }

    /**
     * Sınırlayıcı beklemediği hâlde hedefi aşan kare sayısı.
     * @return sınırlayıcı beklemediği hâlde hedefi aşan kare sayısı
     */
    public long idleLateFrames() {
        return idle.lateFrames;
    }

    /**
     * Sınırlayıcı beklemediği hâlde ölçülen en büyük gecikme.
     * @return sınırlayıcı beklemediği hâlde ölçülen en büyük gecikme, nanosaniye
     */
    public long idleLatenessMaxNs() {
        return idle.latenessMaxNs;
    }

    /**
     * Sınırlayıcı beklemediği hâlde yapılan park çağrısı sayısı.
     * @return sınırlayıcı beklemediği hâlde yapılan park çağrısı sayısı
     */
    public long idleParkCalls() {
        return idle.parkCalls;
    }

    /**
     * Sınırlayıcı beklemediği hâlde spin'de harcanan süre.
     * @return sınırlayıcı beklemediği hâlde spin'de harcanan süre, nanosaniye
     */
    public long idleSpinNsTotal() {
        return idle.spinNsTotal;
    }

    /**
     * Boşta rejimde geçen süre — başlangıç aşamasının uzunluğunu verir.
     *
     * @return nanosaniye cinsinden boşta süre
     */
    public long idleElapsedNs() {
        return idle.totalFrameNs;
    }
}