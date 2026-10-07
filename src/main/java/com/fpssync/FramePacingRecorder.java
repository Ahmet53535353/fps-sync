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
     * Kare süresi histogramının üst sınırı: 64 ms.
     *
     * <p>Gecikmeden ayrı bir histogram gerekir çünkü gecikme <b>yalnız geç kareleri</b>
     * tutar; zamanında kalanlar hiç girmez. "1% low" ise kare süresinin <em>tüm</em>
     * dağılımına bakar. 60 Hz'de taban 16,67 ms; 50 ms'lik gecikmeler de ölçüm
     * aralığının içinde.
     */
    public static final long FRAME_TIME_MAX_NS = 64_000_000L;

    /** Kare süresi kovasının genişliği: 50 µs. 16,67 ms'de %0,3 çözünürlük. */
    public static final long FRAME_TIME_STEP_NS = 50_000L;

    static final int FRAME_TIME_BUCKETS =
            (int) (FRAME_TIME_MAX_NS / FRAME_TIME_STEP_NS) + 1;

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

    /**
     * Erken dönen park çağrılarının uyuma süresi için üst sınır: 64 ms.
     *
     * <p>Düzgün çağrılar bu dağılıma girmez; yalnızca istenenden erken dönenler.
     * Toplam park süresi iki popülasyonu karıştırdığı için ayrı ölçülür.
     */
    public static final long EARLY_SLEEP_MAX_NS = 64_000_000L;

    /** Erken uyku kovasının genişliği: 10 µs. */
    public static final long EARLY_SLEEP_STEP_NS = 10_000L;

    static final int PARK_ELAPSED_BUCKETS =
            (int) (PARK_ELAPSED_MAX_NS / PARK_ELAPSED_STEP_NS) + 1;
    static final int EARLY_SLEEP_BUCKETS =
            (int) (EARLY_SLEEP_MAX_NS / EARLY_SLEEP_STEP_NS) + 1;

    /** Bir rejimin tüm sayacı ve histogramı. */
    private static final class Regime {
        long frames;
        long lateFrames;
        long totalFrameNs;
        long latenessOverflowFrames;
        long frameTimeCount;
        long frameTimeOverflow;
        long swapNsTotal;
        long swapEntries;
        long swapMaxNs;
        long swapLateNsTotal;
        long swapLateEntries;
        long swapOnTimeNsTotal;
        long swapOnTimeEntries;
        /** Görülen ilk kare bütçesi; swap yüzdesini bu bütçeye göre vermek için. */
        long budgetNs;
        /** Oturumda sınır durumu kaç kez değişti (FPS Sync / elle / sınırsız). */
        long stateChanges;
        final int[] frameTime = new int[FRAME_TIME_BUCKETS];
        long latenessMaxNs;
long parkCalls;
    long parkNsTotal;
    long parkRetryAfterFailCalls;
    long parkRetryAfterFailSleptCalls;
    long parkRetryAfterFailSleptNs;
        long overshootOverflow;
        long overshootMaxNs;
        long overshootLateCalls;
        long parkEarlyCalls;
        long parkEarlyNsTotal;
        long parkElapsedCalls;
        long parkElapsedOverflow;
        long earlySleepCalls;
        long earlySleepOverflow;
        long interruptFlagFrames;
        long interruptsCaught;
        long spinNsTotal;
        long spinEntries;
        final int[] lateness = new int[LATE_BUCKETS];
        final int[] overshoot = new int[OVERSHOOT_BUCKETS];
        final int[] parkElapsed = new int[PARK_ELAPSED_BUCKETS];
        final int[] earlySleep = new int[EARLY_SLEEP_BUCKETS];

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
            frameTimeCount = other.frameTimeCount;
            frameTimeOverflow = other.frameTimeOverflow;
            swapNsTotal = other.swapNsTotal;
            swapEntries = other.swapEntries;
            swapMaxNs = other.swapMaxNs;
            swapLateNsTotal = other.swapLateNsTotal;
            swapLateEntries = other.swapLateEntries;
            swapOnTimeNsTotal = other.swapOnTimeNsTotal;
            swapOnTimeEntries = other.swapOnTimeEntries;
            budgetNs = other.budgetNs;
            stateChanges = other.stateChanges;
            latenessMaxNs = other.latenessMaxNs;
            parkCalls = other.parkCalls;
            parkNsTotal = other.parkNsTotal;
            parkRetryAfterFailCalls = other.parkRetryAfterFailCalls;
            parkRetryAfterFailSleptCalls = other.parkRetryAfterFailSleptCalls;
            parkRetryAfterFailSleptNs = other.parkRetryAfterFailSleptNs;
            overshootOverflow = other.overshootOverflow;
            overshootMaxNs = other.overshootMaxNs;
            parkEarlyCalls = other.parkEarlyCalls;
            parkEarlyNsTotal = other.parkEarlyNsTotal;
            parkElapsedCalls = other.parkElapsedCalls;
            parkElapsedOverflow = other.parkElapsedOverflow;
            earlySleepCalls = other.earlySleepCalls;
            earlySleepOverflow = other.earlySleepOverflow;
            interruptFlagFrames = other.interruptFlagFrames;
            interruptsCaught = other.interruptsCaught;
            spinNsTotal = other.spinNsTotal;
            spinEntries = other.spinEntries;
            System.arraycopy(other.lateness, 0, lateness, 0, LATE_BUCKETS);
            System.arraycopy(other.frameTime, 0, frameTime, 0, FRAME_TIME_BUCKETS);
            System.arraycopy(other.overshoot, 0, overshoot, 0, OVERSHOOT_BUCKETS);
            System.arraycopy(other.parkElapsed, 0, parkElapsed, 0, PARK_ELAPSED_BUCKETS);
            System.arraycopy(other.earlySleep, 0, earlySleep, 0, EARLY_SLEEP_BUCKETS);
        }

        void clear() {
            frames = 0;
            lateFrames = 0;
            totalFrameNs = 0;
            latenessOverflowFrames = 0;
            frameTimeCount = 0;
            frameTimeOverflow = 0;
            swapNsTotal = 0;
            swapEntries = 0;
            swapMaxNs = 0;
            swapLateNsTotal = 0;
            swapLateEntries = 0;
            swapOnTimeNsTotal = 0;
            swapOnTimeEntries = 0;
            budgetNs = 0;
            stateChanges = 0;
            latenessMaxNs = 0;
            parkCalls = 0;
            parkNsTotal = 0;
            parkRetryAfterFailCalls = 0;
            parkRetryAfterFailSleptCalls = 0;
            parkRetryAfterFailSleptNs = 0;
            overshootOverflow = 0;
            overshootMaxNs = 0;
            overshootLateCalls = 0;
            parkEarlyCalls = 0;
            parkEarlyNsTotal = 0;
            parkElapsedCalls = 0;
            parkElapsedOverflow = 0;
            earlySleepCalls = 0;
            earlySleepOverflow = 0;
            interruptFlagFrames = 0;
            interruptsCaught = 0;
            spinNsTotal = 0;
            spinEntries = 0;
            java.util.Arrays.fill(lateness, 0);
            java.util.Arrays.fill(frameTime, 0);
            java.util.Arrays.fill(overshoot, 0);
            java.util.Arrays.fill(parkElapsed, 0);
            java.util.Arrays.fill(earlySleep, 0);
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
     * Oturumda sınır durumu kaç kez değişti — <b>oturum düzeyinde</b>, rejim
     * düzeyinde değil.
     *
     * <p>Değişim, sınırlayıcı hedefi çözerken ({@code limitFrame}) ve henüz hiç kare
     * yazılmadan bildirilir; hangi rejime yazılacağı o anda bilinemez. Rejim
     * sayaçlarına yazılsaydı, hiç bekleyen kare içermeyen taban koşusunda sayım
     * sıfırda kalır ve karışık oturum uyarısı tam da o koşullarda çalışmazdı.
     */
    private long stateChangeCount;

/**
       * Park süresi ve kesinti teşhisi <b>ölçülmeden</b> kare kaydeder.
       *
       * <p>Yalnız aşım/aşım dışı dağılımıyla ilgilenen çağrılar için. Üretim yolu
       * ({@link FpsSyncMod#recordFrameTiming}) daima gerçek süreyi ve teşhisi geçirir.
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
          recordFrame(frameNs, budgetNs, waited, parkCalls, parkNs, overshootNs, spinNs,
                  0L, 0L, 0L, 0, 0, 0L);
      }

      /**
       * Park süresi ölçülmeden kare kaydeder; teşhis sayaçları geçirilir.
       *
       * @param frameNs     kare süresi
       * @param budgetNs    hedef kare bütçesi
       * @param waited      sınırlayıcı bekledi mi
       * @param parkCalls   bu karedeki park çağrısı sayısı
       * @param parkNs      park için istenen süre
       * @param overshootNs işaretli aşım (negatif = erken dönüş)
       * @param spinNs      harcanan spin süresi
       * @param parkElapsedNs park'ın gerçekte geçirdiği süre
       */
      public void recordFrame(long frameNs, long budgetNs, boolean waited,
              int parkCalls, long parkNs, long overshootNs, long spinNs,
              long parkElapsedNs) {
          recordFrame(frameNs, budgetNs, waited, parkCalls, parkNs, overshootNs, spinNs,
                  parkElapsedNs, 0L, 0L, 0, 0, 0L);
    }

    /**
     * Teşhis sayaçlarıyla kare kaydeder; park tekrarı ölçülmez.
     *
     * @param frameNs       kare süresi
     * @param budgetNs      hedef kare bütçesi
     * @param waited        sınırlayıcı bekledi mi
     * @param parkCalls     bu karedeki park çağrısı sayısı
     * @param parkNs        park için istenen süre
     * @param overshootNs   işaretli aşım; negatifse erken dönüş sayılır
     * @param spinNs        harcanan spin süresi
     * @param parkElapsedNs park'ın gerçekte geçirdiği süre
     * @param interruptFlagFrames park anında kesinti bayrağı set olan kare
     * @param interruptsCaught    yakalanan {@link InterruptedException} sayısı
     */
    public void recordFrame(long frameNs, long budgetNs, boolean waited,
            int parkCalls, long parkNs, long overshootNs, long spinNs,
            long parkElapsedNs, long interruptFlagFrames, long interruptsCaught) {
        recordFrame(frameNs, budgetNs, waited, parkCalls, parkNs, overshootNs, spinNs,
                parkElapsedNs, interruptFlagFrames, interruptsCaught, 0, 0, 0L);
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
     * @param interruptFlagFrames park anında kesinti bayrağı set olan kare
     * @param interruptsCaught    yakalanan {@link InterruptedException} sayısı
     * @param retryAfterFailCalls  fayda koruması devreye girip bir kez daha denendiği kare
     * @param retryAfterFailSleptCalls  o tekrarın <em>gerçekten uyuduğu</em> sayısı
     * @param retryAfterFailSleptNs     tekrar denemelerin toplam uyuma süresi
     */
    public void recordFrame(long frameNs, long budgetNs, boolean waited,
            int parkCalls, long parkNs, long overshootNs, long spinNs,
            long parkElapsedNs, long interruptFlagFrames, long interruptsCaught,
            int retryAfterFailCalls, int retryAfterFailSleptCalls,
            long retryAfterFailSleptNs) {
        recordFrame(frameNs, budgetNs, waited, parkCalls, parkNs, overshootNs, spinNs,
                parkElapsedNs, interruptFlagFrames, interruptsCaught,
                retryAfterFailCalls, retryAfterFailSleptCalls, retryAfterFailSleptNs,
                0L, 0L, 0L);
    }

    /**
     * Bir kareyi swap ölçümüyle birlikte kaydeder.
     *
     * @param frameNs       kare süresi
     * @param budgetNs      hedef kare bütçesi
     * @param waited        sınırlayıcı bekledi mi
     * @param parkCalls     bu karedeki park çağrısı sayısı
     * @param parkNs        park için istenen süre
     * @param overshootNs   işaretli aşım; negatifse erken dönüş sayılır
     * @param spinNs        bu karede harcanan spin süresi
     * @param parkElapsedNs park'ın gerçekte geçirdiği süre
     * @param interruptFlagFrames park anında kesinti bayrağı set olan kare
     * @param interruptsCaught    yakalanan {@link InterruptedException} sayısı
     * @param retryAfterFailCalls  fayda koruması devreye girip tekrar denendiği kare
     * @param retryAfterFailSleptCalls  o tekrarın gerçekten uyuduğu sayısı
     * @param retryAfterFailSleptNs     tekrar denemelerin toplam uyuma süresi
     * @param swapNsTotal    bu kareye düşen swap süresi toplamı
     * @param swapEntries    bu kareye düşen swap ölçüm sayısı
     * @param swapMaxNs      bu kareye düşen en kötü swap süresi
     */
    public void recordFrame(long frameNs, long budgetNs, boolean waited,
            int parkCalls, long parkNs, long overshootNs, long spinNs,
            long parkElapsedNs, long interruptFlagFrames, long interruptsCaught,
            int retryAfterFailCalls, int retryAfterFailSleptCalls,
            long retryAfterFailSleptNs,
            long swapNsTotal, long swapEntries, long swapMaxNs) {
        if (paused) {
            return;
        }
        elapsedNs += frameNs;
        Regime r = waited ? active : idle;
        r.frames++;
        r.totalFrameNs += frameNs;

        // Kare süresi dağılımı: 1% low için gereklidir. Gecikmeden ayrıdır çünkü
        // zamanında kalan kareler de dağılımın parçasıdır.
        r.frameTimeCount++;
        if (frameNs >= 0 && frameNs < FRAME_TIME_MAX_NS) {
            r.frameTime[(int) (frameNs / FRAME_TIME_STEP_NS)]++;
        } else {
            r.frameTimeOverflow++;
        }

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

        r.interruptFlagFrames += interruptFlagFrames;
        r.interruptsCaught += interruptsCaught;
        r.parkRetryAfterFailCalls += retryAfterFailCalls;
        r.parkRetryAfterFailSleptCalls += retryAfterFailSleptCalls;
        r.parkRetryAfterFailSleptNs += retryAfterFailSleptNs;
        r.swapNsTotal += swapNsTotal;
        r.swapEntries += swapEntries;
        if (swapMaxNs > r.swapMaxNs) {
            r.swapMaxNs = swapMaxNs;
        }
        // Çapraz tablo: swap kare tipine göre ayrı toplanır. Genel ortalama, geç
        // kalmanın CPU'dan mı GPU'dan geldiğini ayırt edemiyordu.
        //
        // Eşleştirme bir iterasyon kaymış olabilir: swap, GameRenderer TAIL'inden
        // SONRA gerçekleşir, yani kare N'in gönderimiyle N'in sunumu farklı
        // iterasyonlardır. Dağılım için bu bir kayma değil, ortak örnekleme sayılır:
        // ikisi de aynı döngüde ölçülen "geç kaldı / zamanında" olayıdır.
        if (swapEntries > 0) {
            if (lateness > 0) {
                r.swapLateNsTotal += swapNsTotal;
                r.swapLateEntries += swapEntries;
            } else {
                r.swapOnTimeNsTotal += swapNsTotal;
                r.swapOnTimeEntries += swapEntries;
            }
        }
        if (r.budgetNs == 0L && budgetNs > 0) {
            r.budgetNs = budgetNs;
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
                // Erken dönenler ne kadar uyuyabildi? Ayrı dağılım: toplam park
                // süresi düzgün ve erken çağrıları birlikte özetliyor.
                if (parkElapsedNs > 0) {
                    r.earlySleepCalls++;
                    if (parkElapsedNs < EARLY_SLEEP_MAX_NS) {
                        r.earlySleep[(int) (parkElapsedNs / EARLY_SLEEP_STEP_NS)]++;
                    } else {
                        r.earlySleepOverflow++;
                    }
                }
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
     * @param interruptFlagFrames park anında kesinti bayrağı set olan kare
     * @param interruptsCaught    yakalanan {@link InterruptedException} sayısı
     * @param parkRetryAfterFailCalls fayda koruması devreye girip bir kez daha denendiği kare
     * @param parkRetryAfterFailSleptCalls o tekrarın <em>gerçekten uyuduğu</em> sayısı
     * @param parkRetryAfterFailSleptNs    tekrar denemelerin toplam uyuma süresi
     * @param swapNsTotal    ölçülen swap süresi toplamı
     * @param swapEntries    swap ölçüm sayısı
     * @param swapMaxNs      en kötü tek swap süresi
     */
    public record Totals(long elapsedNs, long waitingFrames, long idleFrames,
            long lateFrames, long spinNsTotal, long spinEntries, long parkCalls,
            long parkNsTotal, long parkEarlyCalls, long parkEarlyNsTotal,
            long interruptFlagFrames, long interruptsCaught,
            long parkRetryAfterFailCalls, long parkRetryAfterFailSleptCalls,
            long parkRetryAfterFailSleptNs, long swapNsTotal, long swapEntries,
            long swapMaxNs, long stateChanges, long idleFrameTimeCount,
            long idleSwapLateEntries, long idleSwapOnTimeEntries) {

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

    private Totals totalsOf(Regime a, Regime i, long elapsed) {
        return new Totals(elapsed, a.frames, i.frames, a.lateFrames,
                a.spinNsTotal, a.spinEntries, a.parkCalls, a.parkNsTotal,
                a.parkEarlyCalls, a.parkEarlyNsTotal,
                a.interruptFlagFrames, a.interruptsCaught,
                a.parkRetryAfterFailCalls, a.parkRetryAfterFailSleptCalls,
                a.parkRetryAfterFailSleptNs, a.swapNsTotal, a.swapEntries, a.swapMaxNs,
                // Oturum düzeyindeki sayaç: erken pencere için `stateChangeCount`'in
                // o andaki değeri kullanılır, çünkü geçmişteki değişimler o pencereye
                // ait değildir. Rejim sayaçları geriye dönük kalır.
                Math.max(stateChangeCount, a.stateChanges + i.stateChanges),
                i.frameTimeCount, i.swapLateEntries, i.swapOnTimeEntries);
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
        stateChangeCount = 0;
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

    /**
     * Kare süresi histogramından yüzdelik, <b>kova orta noktası</b> ile.
     *
     * <p>Neden orta nokta: {@link #percentile} kova <b>alt sınırını</b> döndürür. Gecikme
     * için bu bir tercihtir — "ölçemedim" ile "tam bu değer" ayrımını silmemek içindir.
     * Ama kare süresinden FPS türetilir ve alt sınır süreyi <b>eksik</b> saydığı için
     * FPS'i <b>gerçekten yüksek</b> gösterirdi. Sapma tek kova genişliğinde kalır:
     * 50 µs, 16,67 ms'de %0,3.
     *
     * @param q istenen yüzdelik, 0–1 arası
     * @return kare süresinin istenen yüzdeliği, nanosaniye; dağılım dışındaysa
     *         {@link #SATURATED}
     */
    private static long percentileMid(int[] hist, long count, long step, double q) {
        long lower = percentile(hist, count, step, q);
        if (lower == SATURATED) {
            return SATURATED;
        }
        return lower + step / 2;
    }

    /**
     * Sınırlayıcı beklerken ölçülen kare sayısı.
     *
     * <p>Kare süresi dağılımının örnek sayısıdır; zamanında kalan kareler de dahildir.
     *
     * @return kare sayısı
     */
    public long activeFrameTimeCount() {
        return active.frameTimeCount;
    }

    /**
     * Kare süresi histogramının dışına düşen kare sayısı.
     *
     * <p>64 ms üstü kareler ölçüm aralığının dışındadır; sessizce yok sayılırsa yüzdelik
     * iyimserleşir, bu yüzden ayrıca sayılır ve raporda belirtilir.
     *
     * @return tavan dışı kare sayısı
     */
    public long activeFrameTimeOverflow() {
        return active.frameTimeOverflow;
    }

    /**
     * Sınırlayıcı beklerken kare süresinin istenen yüzdeliği.
     *
     * @param q istenen yüzdelik, 0–1 arası
     * @return kare süresinin istenen yüzdeliği, nanosaniye
     */
    public long activeFrameTimePercentileNs(double q) {
        return percentileMid(active.frameTime, active.frameTimeCount,
                FRAME_TIME_STEP_NS, q);
    }

    /**
     * <b>1% low</b>: en yavaş %1'lik dilimin FPS karşılığı.
     *
     * <p>Gecikme yüzdesi tek başına yetmez: "geç kare %37,5" bir sayıdır, şiddet değildir.
     * 1 ms'lik tırtıklama ile 40 ms'lik duraklama aynı sayıda geç karedir. 1% low ikisini
     * tek sayıda özetler.
     *
     * @return 1% low FPS; örnek yoksa 0
     */
    public double activeFps1Low() {
        return fpsFromFrameTime(activeFrameTimePercentileNs(0.99));
    }

    /**
     * <b>0,1% low</b>: en yavaş %0,1'lik dilimin FPS karşılığı.
     *
     * @return 0,1% low FPS; örnek yoksa 0
     */
    public double activeFps01Low() {
        return fpsFromFrameTime(activeFrameTimePercentileNs(0.999));
    }

    /**
     * Sınırlayıcı beklerken ölçülen swap çağrısı sayısı.
     *
     * @return swap ölçüm sayısı
     */
    public long activeSwapEntries() {
        return active.swapEntries;
    }

    /**
     * Sınırlayıcı beklerken ölçülen swap süresi toplamı.
     *
     * @return toplam swap süresi, nanosaniye
     */
    public long activeSwapNsTotal() {
        return active.swapNsTotal;
    }

/**
 * En kötü tek swap süresi.
 *
 * <p>GPU darboğazının en güçlü göstergesi: swap kare süresi kadar blokluyorsa
 * iş parçacığı değil GPU geciktir.
 *
 * @return en kötü swap süresi, nanosaniye
 */
    public long activeSwapMaxNs() {
        return active.swapMaxNs;
    }

    /**
     * Sınırlayıcı beklerken görülen kare bütçesi.
     *
     * <p>Swap süresini mutlak mikrosaniye yerine <em>bütçenin yüzdesi</em> olarak
     * yorumlamak için gerekir: "1,63 ms" zihinsel bölme ister, "%9,8" istemez.
     *
     * @return hedef kare bütçesi, nanosaniye; 0 ise henüz belirlenmedi
     */
    public long activeBudgetNs() {
        return active.budgetNs;
    }

    /**
     * <b>Geç</b> karelerde ölçülen swap çağrısı sayısı.
     *
     * <p>Genel swap ortalaması iki ayrı olayı karıştırır: iş parçacığının geç
     * kalması ile iş parçacığı zamanında bitip GPU'nun gecikmesi. Kare tipine göre
     * ayırmak bu ikisini ölçülebilir kılar.
     *
     * @return geç karelerdeki swap ölçüm sayısı
     */
    public long activeSwapLateEntries() {
        return active.swapLateEntries;
    }

    /** @return geç karelerde ölçülen swap süresi toplamı, nanosaniye */
    public long activeSwapLateNsTotal() {
        return active.swapLateNsTotal;
    }

    /** @return hedefi zamanında tutan karelerdeki swap ölçüm sayısı */
    public long activeSwapOnTimeEntries() {
        return active.swapOnTimeEntries;
    }

    /** @return zamanında karelerde ölçülen swap süresi toplamı, nanosaniye */
    public long activeSwapOnTimeNsTotal() {
        return active.swapOnTimeNsTotal;
    }

    /**
     * Oturumda sınır durumu kaç kez değişti.
     *
     * <p>Rejim sayaçlarına yazılsaydı, hiç bekleyen kare içermeyen <b>taban
     * koşusunda</b> sayım sıfırda kalır ve karışık oturum uyarısı tam da o
     * koşullarda çalışmazdı. Oturum düzeyinde tutulur. Bkz.
     * {@code UnlimitedRegimeRecordingTest}.
     */
    public long stateChanges() {
        return stateChangeCount;
    }

    /**
     * Durum değişimini kaydeder.
     *
     * <p>Sınırlayıcı hedefi çözerken, kare yazılmadan önce çağrılır.
     */
    public void recordStateChange() {
        stateChangeCount++;
    }

    // --- boşta (sınırlayıcı çalışmadı) rejim --------------------------------
    //
    // Taban koşusu bu rejimde toplanır. Veri zaten dolduruluyordu — frameTime
    // histogramı her kare için her iki rejimde de yazılıyor — ama erişimciler
    // yalnız `active` okuduğu için rapor boşta rejimi tek satırla geçiyordu.
    // "Mod kapalıyken ne kadar iyi" sorusunu ölçememek, o soruyu cevaplamamanın
    // en pahalı biçimiydi.

    /** @return sınırlayıcı çalışmadığı kare sayısı */
    public long idleFrameTimeCount() {
        return idle.frameTimeCount;
    }

    /** @return boşta rejimde 1% low FPS; örnek yoksa 0 */
    public double idleFps1Low() {
        return fpsFromFrameTime(percentileMid(idle.frameTime, idle.frameTimeCount,
                FRAME_TIME_STEP_NS, 0.99));
    }

    /** @return boşta rejimde 0,1% low FPS; örnek yoksa 0 */
    public double idleFps01Low() {
        return fpsFromFrameTime(percentileMid(idle.frameTime, idle.frameTimeCount,
                FRAME_TIME_STEP_NS, 0.999));
    }

    /** @return boşta rejimde kare süresinin istenen yüzdeliği, nanosaniye */
    public long idleFrameTimePercentileNs(double q) {
        return percentileMid(idle.frameTime, idle.frameTimeCount, FRAME_TIME_STEP_NS, q);
    }

    /** @return boşta rejimde ölçülen swap çağrısı sayısı */
    public long idleSwapEntries() {
        return idle.swapEntries;
    }

    /** @return boşta rejimde swap süresi toplamı, nanosaniye */
    public long idleSwapNsTotal() {
        return idle.swapNsTotal;
    }

    /** @return boşta rejimde en kötü swap süresi, nanosaniye */
    public long idleSwapMaxNs() {
        return idle.swapMaxNs;
    }

    /** @return boşta rejimde geç karelerdeki swap ölçüm sayısı */
    public long idleSwapLateEntries() {
        return idle.swapLateEntries;
    }

    /** @return boşta rejimde geç karelerdeki swap süresi toplamı */
    public long idleSwapLateNsTotal() {
        return idle.swapLateNsTotal;
    }

    /** @return boşta rejimde zamanında karelerdeki swap ölçüm sayısı */
    public long idleSwapOnTimeEntries() {
        return idle.swapOnTimeEntries;
    }

    /** @return boşta rejimde zamanında karelerdeki swap süresi toplamı */
    public long idleSwapOnTimeNsTotal() {
        return idle.swapOnTimeNsTotal;
    }

    private static double fpsFromFrameTime(long ns) {
        if (ns <= 0 || ns == SATURATED) {
            return 0.0;
        }
        return 1_000_000_000.0 / ns;
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
      /**
       * Park anında interrupt bayrağı set olan kare sayısı.
       *
       * <p>Teşhis. Sıfırdan büyükse park'ın bazen uyumadan dönmesinin interrupt
       * kaynaklı olma olasılığı güçlenir: {@code LockSupport.parkNanos} interrupt
       * durumu set ise anında döner.
       *
       * @return bayrak set olduğu kare sayısı
       */
      /**
       * Erken dönen park çağrılarının gerçekte uyuduğu sürenin medyanı.
       *
       * <p>Toplam park süresi iki popülasyonu karıştırır: düzgün çağrılar isteneni
       * tam uyur, erken dönenler istenenin onda birini. Karışık ortalama hangi
       * popülasyonun baskın olduğunu göstermez; bu yüzden erken dönenler ayrı
       * histogramlanır.
       *
       * <p>Park tekrarının kaç deneme yapması gerektiğini bu dağılım belirler.
       *
       * @return nanosaniye; erken dönüş yoksa 0
       */
      public long activeEarlySleepMedianNs() {
          return percentile(active.earlySleep, active.earlySleepCalls, EARLY_SLEEP_STEP_NS, 0.50);
      }

      /**
       * Erken dönen park çağrılarının uyuma süresinin istenen yüzdeliği.
       *
       * @param q istenen yüzdelik, 0–1 arası
       * @return nanosaniye; ölçülemiyorsa {@link #SATURATED}
       */
      public long activeEarlySleepPercentileNs(double q) {
          return percentile(active.earlySleep, active.earlySleepCalls, EARLY_SLEEP_STEP_NS, q);
      }

      /**
       * Erken uyku süresi kaydedilen çağrı sayısı.
       *
       * @return kayıt sayısı
       */
      public long activeEarlySleepCalls() {
          return active.earlySleepCalls;
      }

      /**
       * Erken uyku histogramının üst sınırını aşan çağrı sayısı.
       *
       * @return taşan çağrı sayısı
       */
      public long activeEarlySleepOverflow() {
          return active.earlySleepOverflow;
      }

      /**
       * Park anında interrupt bayrağı set olan kare sayısı.
       *
       * <p>Teşhis. Sıfırdan büyükse park'ın bazen uyumadan dönmesinin interrupt
       * kaynaklı olma olasılığı güçlenir: {@code LockSupport.parkNanos} interrupt
       * durumu set ise anında döner.
       *
       * @return bayrak set olduğu kare sayısı
       */
      public long interruptFlagFrames() {
          return active.interruptFlagFrames;
      }

      /**
       * Sınırlayıcının yakaladığı {@link InterruptedException} sayısı.
       *
       * <p>Teşhis. Sıfırken hiç kesinti olmamış demektir; bu durumda bayrak sorunu da
       * yoktur ve açıklama başka yerde aranmalıdır.
       *
       * @return yakalanan kesinti sayısı
       */
      public long interruptsCaught() {
          return active.interruptsCaught;
      }

      /**
       * Fayda koruması devreye girip bir kez daha park denenen çağrı sayısı.
       *
       * <p>Sıfırken hiç tekrar denenmemiş demektir; yani park her karede tek denemede
       * faydalı olmuştur.
       *
       * @return başarısız çağrıdan sonra tekrar denenen kare sayısı
       */
      public long activeParkRetryAfterFailCalls() {
          return active.parkRetryAfterFailCalls;
      }

      /**
       * Bu tekrarın <b>gerçekten uyuduğu</b> sayısı.
       *
       * <p>Karar sayısı {@code activeParkRetryAfterFailSleptCalls()} /
       * {@link #activeParkRetryAfterFailCalls()} oranıdır.
       *
       * @return tekrarın fayda sağladığı çağrı sayısı
       */
      public long activeParkRetryAfterFailSleptCalls() {
          return active.parkRetryAfterFailSleptCalls;
      }

      /**
       * Tekrar denemelerin toplam uyuma süresi.
       *
       * <p>Bir park çağrısının CPU maliyeti ~45 µs. Bu toplamın çağrı sayısına bölümü
       * gideri aşıp aşmadığını gösterir.
       *
       * @return tekrar denemelerin toplam uyuma süresi (ns)
       */
      public long activeParkRetryAfterFailSleptNs() {
          return active.parkRetryAfterFailSleptNs;
      }

      /**
       * Aşım dağılımının örnek sayısı: <b>geç dönen</b> park çağrıları.
       *
       * <p>Erken dönüşler aşım değildir ve histograma girmez. Yüzdelik hesabında payda
       * olarak tüm park çağrıları kullanılırsa, erken dönüş olan her koşuda medyan ve p95
       * doygunlaşır ve "ölçülemez" görünür.
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