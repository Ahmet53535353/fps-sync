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
    /**
     * Park'ın gerçekte uyuduğu sürenin üst sınırı: 64 ms.
     *
     * <p>İstenen süre 60 Hz bütçesinden küçük olmak zorunda, ama taşma durumları ve
     * düşük FPS'lerde daha uzun olabilir; 64 ms her ikisini de kapsar.
     */
    public static final long PARK_ELAPSED_MAX_NS = 64_000_000L;

    /** Park gerçek süre kovasının genişliği: 10 µs. */
    public static final long PARK_ELAPSED_STEP_NS = 10_000L;

    static final int OVERSHOOT_BUCKETS = (int) (OVERSHOOT_MAX_NS / OVERSHOOT_STEP_NS) + 1;
    /**
     * Karşılaştırma penceresi: ilk 10 dakika.
     *
     * <p>Sabit süreli bir pencere, koşular arasındaki farkı ölçülebilir kılar. Toplamlar
     * karşılaştırılamaz: üç ardışık koşuda CPU payı %17,0 → %17,5 → %25,1 çıktı ve kod
     * değişmedi; değişen şey oynanan içerikti (gezinme, maden). İlk 10 dakika aynı
     * koşulu ölçer: dünya yüklenmiş, ısınma tamam, oyuncu benzer yerde.
     */
    public static final long EARLY_WINDOW_NS = 600_000_000_000L;

    static final int PARK_ELAPSED_BUCKETS =
            (int) (PARK_ELAPSED_MAX_NS / PARK_ELAPSED_STEP_NS) + 1;

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
        long overshootLateCalls;
        long parkEarlyCalls;
        long parkEarlyNsTotal;
        long parkElapsedCalls;
        long parkElapsedOverflow;
        long spinNsTotal;
        long spinEntries;
        final int[] lateness = new int[LATE_BUCKETS];
        final int[] overshoot = new int[OVERSHOOT_BUCKETS];
        final int[] parkElapsed = new int[PARK_ELAPSED_BUCKETS];

        /**
         * Başka bir rejimin değerlerini alır.
         *
         * <p>İlk 10 dakika penceresini dondururken kullanılır: pencere, sınır anındaki
         * sayaçların <em>kopyasıdır</em> ve bundan sonra değişmez. Tahsis yapmaz —
         * diziler zaten var, yalnız içerik kopyalanır.
         *
         * @param other kopyalanacak rejim
         */
        void copyFrom(Regime other) {
            frames = other.frames;
            lateFrames = other.lateFrames;
            totalFrameNs = other.totalFrameNs;
            latenessOverflowFrames = other.latenessOverflowFrames;
            latenessMaxNs = other.latenessMaxNs;
            parkCalls = other.parkCalls;
            parkNsTotal = other.parkNsTotal;
            overshootOverflow = other.overshootOverflow;
            overshootMaxNs = other.overshootMaxNs;
            parkEarlyCalls = other.parkEarlyCalls;
            parkEarlyNsTotal = other.parkEarlyNsTotal;
            parkElapsedCalls = other.parkElapsedCalls;
            parkElapsedOverflow = other.parkElapsedOverflow;
            spinNsTotal = other.spinNsTotal;
            spinEntries = other.spinEntries;
            System.arraycopy(other.lateness, 0, lateness, 0, LATE_BUCKETS);
            System.arraycopy(other.overshoot, 0, overshoot, 0, OVERSHOOT_BUCKETS);
            System.arraycopy(other.parkElapsed, 0, parkElapsed, 0, PARK_ELAPSED_BUCKETS);
        }

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
            overshootLateCalls = 0;
            parkEarlyCalls = 0;
            parkEarlyNsTotal = 0;
            parkElapsedCalls = 0;
            parkElapsedOverflow = 0;
            spinNsTotal = 0;
            spinEntries = 0;
            java.util.Arrays.fill(lateness, 0);
            java.util.Arrays.fill(overshoot, 0);
            java.util.Arrays.fill(parkElapsed, 0);
        }
    }

    private final Regime active = new Regime();
    private final Regime idle = new Regime();

    /**
     * İlk 10 dakikanın dondurulmuş kopyası.
     *
     * <p>Kurucuda tahsis edilir; sınırda yalnızca kopyalanır. Böylece sıcak yolda
     * tahsis olmaz ve sıfır ayak izi testi bozulmaz.
     */
    private final Regime earlyActive = new Regime();
    private final Regime earlyIdle = new Regime();
    private long earlyElapsedNs;
    private boolean earlyCaptured;

    private long elapsedNs;
    private long firstWaitAtNs = -1;
    private volatile boolean paused;

    /**
     * Park süresi <b>ölçülmeden</b> kare kaydeder.
     *
     * <p>Yalnız aşım ve aşım dışı dağılımıyla ilgilenen çağrılar için. Üretim yolu
     * ({@link FpsSyncMod#recordFrameTiming}) daima gerçek süreyi geçirir.
     *
     * @param frameNs     kare süresi
     * @param budgetNs    hedef kare bütçesi
     * @param waited      sınırlayıcı bekledi mi
     * @param parkCalls   bu karedeki park çağrısı sayısı
     * @param parkNs      park için istenen süre
     * @param overshootNs işaretli aşım (negatif = erken dönüş)
     * @param spinNs      harcanan spin süresi
     */
    public void recordFrame(long frameNs, long budgetNs, boolean waited,
            int parkCalls, long parkNs, long overshootNs, long spinNs) {
        recordFrame(frameNs, budgetNs, waited, parkCalls, parkNs, overshootNs, spinNs, 0L);
    }

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
     * @param overshootNs   işaretli aşım; negatifse erken dönüş sayılır
     * @param spinNs        bu karede harcanan spin süresi
     * @param parkElapsedNs park çağrılarının bu karede gerçekte geçirdiği süre
     */
    public void recordFrame(long frameNs, long budgetNs, boolean waited,
            int parkCalls, long parkNs, long overshootNs, long spinNs,
            long parkElapsedNs) {
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
            if (parkElapsedNs > 0) {
                r.parkElapsedCalls++;
                if (parkElapsedNs < PARK_ELAPSED_MAX_NS) {
                    r.parkElapsed[(int) (parkElapsedNs / PARK_ELAPSED_STEP_NS)]++;
                } else {
                    r.parkElapsedOverflow++;
                }
            }
            if (overshootNs < 0) {
                // Park istenenden ERKEN döndü: kalan süre spin ile yakıldı.
                // Negatif aşım önceden 0'a yassılanıyordu, bu yüzden spin'in neden
                // 100 µs'luk pencerenin çok üstüne çıktığı görünmüyordu.
                r.parkEarlyCalls++;
                r.parkEarlyNsTotal += -overshootNs;
            } else if (overshootNs > 0) {
                r.overshootLateCalls++;
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

        // İlk 10 dakika dolduğunda sayaçların kopyası dondurulur. Buradan sonra
        // gelen kareler pencereyi değiştirmez. Sınır tek sefer geçilir.
        if (!earlyCaptured && elapsedNs >= EARLY_WINDOW_NS) {
            earlyActive.copyFrom(active);
            earlyIdle.copyFrom(idle);
            earlyElapsedNs = elapsedNs;
            earlyCaptured = true;
        }
    }

    /**
     * Bir ölçüm penceresinin toplamları.
     *
     * <p>Hem tüm oyun hem ilk 10 dakika için aynı biçimdedir; rapor tek bir kod yoluyla
     * ikisini de çizer, böylece karşılaştırma aynı ölçütlerle yapılır.
     *
     * @param elapsedNs      pencerede geçen süre
     * @param waitingFrames  sınırlayıcı beklerken geçen kare
     * @param idleFrames     sınırlayıcı beklemediği kare
     * @param lateFrames     hedefi aşan kare
     * @param spinNsTotal    pencerede spin ile geçen süre
     * @param spinEntries    spin yapılan kare sayısı
     * @param parkCalls      park çağrısı sayısı
     * @param parkNsTotal    park için istenen süre toplamı
     * @param parkEarlyCalls istenenden erken dönen park çağrısı
     * @param parkEarlyNsTotal erken dönüşlerin toplam büyüklüğü
     */
    public record Totals(long elapsedNs, long waitingFrames, long idleFrames,
            long lateFrames, long spinNsTotal, long spinEntries, long parkCalls,
            long parkNsTotal, long parkEarlyCalls, long parkEarlyNsTotal) {

/**
           * Penceredeki toplam kare: bekleyen ve boşta geçenler.
           *
           * @return kare sayısı
           */
          public long totalFrames() {
            return waitingFrames + idleFrames;
        }

        /**
         * Gerçek FPS: kare ÷ geçen süre.
         *
         * @return kare/saniye; süre sıfırsa 0
         */
        public double fps() {
            return elapsedNs <= 0 ? 0.0 : totalFrames() * 1_000_000_000.0 / elapsedNs;
        }
    }

    /**
     * Tüm oyunun toplamları.
     *
     * @return oturum başından beri biriken toplamlar
     */
    public Totals totals() {
        return totalsOf(active, idle, elapsedNs);
    }

    /**
     * İlk {@value #EARLY_WINDOW_NS} nanosaniyenin toplamları.
     *
     * @return dondurulmuş pencere; oturum 10 dakikadan kısaysa {@code null}
     */
    public Totals earlyWindowTotals() {
        return earlyCaptured ? totalsOf(earlyActive, earlyIdle, earlyElapsedNs) : null;
    }

    /**
     * İlk 10 dakika doldu mu?
     *
     * @return pencere dondurulduysa {@code true}
     */
    public boolean earlyWindowCaptured() {
        return earlyCaptured;
    }

    private static Totals totalsOf(Regime a, Regime i, long elapsed) {
        return new Totals(elapsed, a.frames, i.frames, a.lateFrames,
                a.spinNsTotal, a.spinEntries, a.parkCalls, a.parkNsTotal,
                a.parkEarlyCalls, a.parkEarlyNsTotal);
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

    /** Ölçüm başlangıcını temizler. Geçmiş histogramlar ve ilk pencere de silinir. */
    public void reset() {
        active.clear();
        idle.clear();
        earlyActive.clear();
        earlyIdle.clear();
        earlyElapsedNs = 0;
        earlyCaptured = false;
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
         * Aşım dağılımının örnek sayısı: <b>geç dönen</b> park çağrıları.
         *
         * <p>Erken dönüşler aşım değildir ve histograma girmez. Yüzdelik hesabında
         * payda olarak {@code parkCalls} kullanılırsa, erken dönüş olan her koşuda
         * medyan ve p95 doygunlaşır ve "ölçülemez" görünür — 2026-10-02 koşusunda
         * olan da buydu. Doğru payda histogramdaki örnek sayısıdır.
         *
         * @return aşımı olan park çağrısı sayısı
         */
        /**
       * Park'ın gerçekte uyuduğu sürenin medyanı.
       *
       * <p>İstenen süreden kısa olduğu için bekleme yapılmamış demektir ve kalan
       * süre spin ile yakılmıştır. Ortalama tek başına şekli söylemez; bu yüzden
       * gerçek süre doğrudan histogramlanır.
       *
       * @return nanosaniye; ölçülemiyorsa {@link #SATURATED}
       */
      public long activeParkElapsedMedianNs() {
          return percentile(active.parkElapsed, active.parkElapsedCalls,
                  PARK_ELAPSED_STEP_NS, 0.50);
      }

      /**
       * Park'ın gerçekte uyuduğu sürenin istenen yüzdeliği.
       *
       * @param q istenen yüzdelik, 0–1 arası
       * @return nanosaniye; ölçülemiyorsa {@link #SATURATED}
       */
      public long activeParkElapsedPercentileNs(double q) {
          return percentile(active.parkElapsed, active.parkElapsedCalls,
                  PARK_ELAPSED_STEP_NS, q);
      }

      /**
       * Gerçek park süresi kaydedilen çağrı sayısı.
       *
       * @return park çağrısı sayısı
       */
      public long activeParkElapsedCalls() {
          return active.parkElapsedCalls;
      }

      /**
       * Park süresi histogramının üst sınırını aşan çağrı sayısı.
       *
       * @return taşan çağrı sayısı
       */
      public long activeParkElapsedOverflow() {
          return active.parkElapsedOverflow;
      }

      /**
       * Aşım dağılımının örnek sayısı: <b>geç dönen</b> park çağrıları.
       *
       * <p>Erken dönüşler aşım değildir ve histograma girmez. Yüzdelik hesabında
       * payda olarak tüm park çağrıları kullanılırsa, erken dönüş olan her koşuda
       * medyan ve p95 doygunlaşır ve "ölçülemez" görünür. 2026-10-02 koşusunda olan
       * da buydu: 27.902 örnek, 27.945 hedef.
       *
       * @return aşımı olan park çağrısı sayısı
       */
      public long activeOvershootLateCalls() {
            return active.overshootLateCalls;
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
        return percentile(active.overshoot, active.overshootLateCalls, OVERSHOOT_STEP_NS, 0.50);
    }

    /**
     * Park aşımının istenen yüzdeliği.
     * @param q istenen yüzdelik, 0–1 arası
     * @return park aşımının istenen yüzdeliği, nanosaniye
     */
    public long activeOvershootPercentileNs(double q) {
        return percentile(active.overshoot, active.overshootLateCalls, OVERSHOOT_STEP_NS, q);
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