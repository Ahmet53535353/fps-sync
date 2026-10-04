package com.fpssync;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Kare hızını sınırlar.
 *
 * <p>İki çalışma modu vardır. FPS Sync açıkken hedef, monitörün gerçek yenileme
 * hızıdır ve elle sınır yok sayılır. kapalıyken elle seçilen değer geçerlidir
 * ({@link FpsSyncOption#UNLIMITED} ve üstü sınırsız demektir; bkz.
 * {@link FpsSyncOption}).
 *
 * <p><b>Bekleme biçimi.</b> Bekleme {@link LockSupport#parkNanos} ile yapılır, nanosaniye
 * değeri olduğu gibi korunur. Daha önce şu iki satır vardı:
 *
 * <pre>{@code
 * long sleepMs = (nextFrameTime - now) / 1_000_000L - 1;
 * if (sleepMs > 0) Thread.sleep(sleepMs);
 * }</pre>
 *
 * İki ayrı kayıp birlikte: değer tam milisaniyeye kırpılıyor (en fazla 999 µs kayıp) ve
 * üstelik bilerek bir tam milisaniye daha düşülüyor. Kalan süre 1 ms veya üzerindeyse
 * geriye daima <b>1-2 ms</b> kalıyor ve bunun tamamı meşgul spin ile geçiyordu; kalan
 * süre 2 ms altına düşünce o 1 ms'lik fark uykuya hiç yansımıyor, sürenin tamamı spin
 * oluyor (1 ms altındaysa bu 1 ms'den az olabilir). Eski kodda ayrı bir spin penceresi
 * de yoktu: geriye kalan bu süre zaten koşulsuz spin'e gittiği için pencerenin ne kadar
 * olması gerektiğinin ayrı bir kararı yoktu.
 *
 * <p><b>Ölçüm: bu makine, evrensel bir rakam değil.</b> Aşağıdaki tablo
 * <em>tek bir makinede</em> alındı. Maliyetin büyük kısmı bekleme çağrısının
 * kendisindedir ve o çevreden <em>çevreye</em> değişir; hızla ölçeklenmez.
 * Başka bir makinedeki FPS-Sync kullanıcısı için geçerli sayılar değildir.
 * Ölçümü tekrarlayan araç: {@code FrameLimiterCostProbe}.
 *
 * <table>
 * <caption>Ölçüm donanımı: Intel Celeron N4120 @ 2.487 GHz, Linux 7.0, 4 çekirdek</caption>
 * <tr><th>Ölçüm</th><th>Değer</th></tr>
 * <tr><td>kare başına CPU (60 Hz, 400 kare, gerçek sınıf)</td><td>60-67 µs</td></tr>
 * <tr><td>&nbsp;&nbsp;bunun park çağrısındaki payı</td><td>%80-90</td></tr>
 * <tr><td>park CPU'su — 1 ms / 5 ms / 15 ms / 50 ms</td><td>24 / 45 / 51 / 53 µs</td></tr>
 * <tr><td>{@code Thread.sleep(0)} — çıplak syscall</td><td>1.17 µs</td></tr>
 * <tr><td>{@code System.nanoTime()}</td><td>29 ns</td></tr>
* <tr><td>park duvar aşımı (timer slack) — <b>yalnız çevrimdışı</b></td><td>~100 µs</td></tr>
   * <tr><td>allocasyon / {@code limitFrame()} çağrısı</td><td>0 bayt (10<sup>6</sup> çağrı)</td></tr>
   * </table>
   *
   * <p><b>Tablo ne zaman geçerli.</b> Tablodaki "kare başına CPU 60-67 µs" yalnız
   * park istenen süreyi spin penceresi kadar aşarak döndüğünde doğrudur. Oyun içinde
   * bu koşul sağlanmıyor ve gerçek maliyet <b>3534 µs/kare</b> çıktı
   * (aşağıdaki oyun içi ölçüme bakın).
 *
 * <p><b>Park'ın maliyeti süreyle ölçeklenmiyor</b> (1 ms'de 24 µs, 50 ms'de 53 µs):
 * sabit bir giderdir. {@code Thread.sleep(0)} ile karşılaştırıldığında zamanlı bir
 * bekleme çıplak syscall'in ~43 katı CPU harcıyor; en olası açıklama derin C-state'den
 * çıkma maliyetidir. Bu, bekleme biçiminin <em>kötü</em> olduğu anlamına gelmez —
 * yalnızca bu donanımda pahalıdır.
 *
 * <p><b>Sıcak yolda ayak izi yok.</b> Ölçüldü: 10<sup>6</sup> çağrıda 0 bayt
 * allocasyon. Sınıf hiçbir dosya, thread veya ağ kaynağı kullanmaz. Bu özellik
 * ileride bozulursa sessiz kalmasın diye teste bağlandı
 * ({@code FrameLimiterSpinLoopTest}).
 *
 * <p><b>JIT: test dikişleri sıcak yolu engellemiyor.</b> Daha önce, {@code nanoTime}
 * alanının değişken olması satır içine almayı engelliyor diye düşünülüyordu.
 * <em>Bu yanlıştı</em>; {@code -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining}
 * ile ölçüldü:
 *
 * <pre>{@code
 * @ 57  FrameLimiter$$Lambda::getAsLong  inline
 * @ 0   java.lang.System::nanoTime       intrinsic
 * }</pre>
 *
 * Arayüz çağrısı tamamen gömülüyor, alanın değişken olması maliyet üretmiyor. Yalnız
 * {@code spinHook} satır içine alınamıyor ({@code no static binding}) ve o da üretimde
 * hiç çağrılmıyor.
 *
 * <p><b>{@code Thread.onSpinWait()} ölçüldü, kaldırılmadı.</b> Kaldırmayı
 * önermiştim, gerekçesi "dönüş başına maliyet 129 ns -> 40 ns" idi. Bu doğru bir
 * ölçüm ama <em>zaman kazancı değil, verim</em> rakamıydı; ikisini karıştırdım.
 * spin penceresi <b>sabit süreye bağlıdır</b>, tur sayısına değil. Döngü
 * {@code nanoTime()} hedefe ulaşana kadar çalışır; gövdeyi hızlandırmak aynı pencereyi
 * doldurmak için daha çok tur demektir, kısa süre değil. Gerçek saatle ölçüldü:
 *
 * <pre>
 * onSpinWait yok : spin 4.322 µs/kare
 * onSpinWait var : spin 4.560 µs/kare      fark 0.238 µs (toplam CPU'nun %0.4'ü)
 * </pre>
 *
 * Frekans ölçüldü: iki durumda da spin sırasındaki frekans medyanı <b>1990 MHz</b>,
 * fark yok. Geriye kalan tek fayda emisyon edilen talimat sayısı (güç/ısı) ve bu
 * makinede ölçülemiyor: {@code perf} sayaçları kapalı ({@code perf_event_paranoid=4}),
 * güç sensörü yok. <b>Ölçülebilir fayda olmadığı için talimat kaldırılmadı.</b>
 *
 * <p><b>Spin penceresinin büyüklüğü.</b> Ölçüldü (400 kare, gerçek kodun kopyaları,
 * yalnız {@link #SPIN_WINDOW_NS} değişiyor):
 *
 * <pre>
 * pencere   ortalama sapma   p99        CPU/kare
 *    0 µs      311 µs      18.82 ms   64.2 µs
 *   50 µs       54 µs      16.72 ms   60.3 µs
 *  100 µs       50 µs      16.72 ms   66.2 µs   &lt;-- mevcut değer
 *  300 µs       79 µs      17.36 ms  257.6 µs
 * </pre>
 *
* Yani <b>çevrimdışı ölçüm koşullarında</b> 100 µs doğru seçilmiştir; penceresiz
   * bırakmak sapmayı 6 kat artırıyor, 300 µs ise hem CPU'yi 4 katına çıkarıyor hem
   * sapmayı kötüleştiriyor. Oyun içinde bu koşul sağlanmadığı için sonuç tersine
   * dönüyor:
   *
   * <h2>Oyun içi ölçüm: bu tablo geçerli değil</h2>
   * Yukarıdaki tablo <b>çevrimdışı</b> bir ölçümdür ve tek bir koşula dayanır:
   * <pre>
   *   park istenen süreyi, spin penceresinden az fazla aşarak döner
   * </pre>
   * Bu koşul sağlanırsa spin penceresi gerçekten 100 µs olur ve kare başına CPU
   * 66 µs'ta kalır. Oyun içinde koşul <b>sağlanmıyor</b>.
   *
   * <p>2026-10-02'de gerçek oyunda ölçüldü ({@code /fpsync status}, 61.743 kare,
   * 54,7 FPS, 1129 sn):
   * <pre>
   *   spin                3534 µs/kare   ->  bir çekirdeğin %17,5'i
   *   park erken dönüş    27.987 / 55.889 çağrı (%50,1), ortalama 6,93 ms ERKEN
   * </pre>
   * Yani karelerin yarısında park <b>erken</b> dönüyor ve kalan ~7 ms'nin tamamı spin
   * ile yakılıyor. Etkin spin penceresi 100 µs değil, milisaniyeler.
   *
   * <p>Bu, çevrimdışı tahminin <b>54 katı</b>. Karşılaştırma için aynı tablodaki eski
   * yol: çevrimdışı 1504 µs/kare CPU. Bugün oyun içi 3534 µs — yani düzeltme
   * niyetiyle (spin'i 1,5 ms'den 100 µs'ye indirmek) yapılan değişiklik, oyun içinde
   * <b>daha pahalı</b> bir sonuç verdi, çünkü dayandığı "park geç döner" varsayımı
   * sağlanmıyor.
   *
   * <p><b>Ders:</b> "park aşımı ~90-100 µs" ifadesi boş bir JVM'de ölçülmüştü.
   * Oyun içinde park'ın yarıda karelerde 6,93 ms <em>erken</em> döndüğü görüldü.
   * Spin penceresinin küçüklüğü ancak park pencere kadar geç döndüğünde anlamlıdır;
   * bu güvence olmadan pencereyi küçültmek ters etki yaratır.
   *
 * <p><em>Tarihçe notu: burada daha önce "ortalama spin 532 µs, %3.2, 10.7 kat iyileşme"
 * yazıyordu. Bunlar sanal saat modelinin <b>tahminidir, ölçüm değildir</b>; model
 * {@code Thread.sleep} aşımını ~1 ms varsaymış, ölçülen aşım ~150-200 µs çıktı. Gerçek
 * eski yol {@code LegacyFrameLimiter} ile bu depodaki eski kod birebir koşularak
 * ölçüldü: 60 fps'te spin 1453 µs, CPU 1504 µs (tek çekirdeğin %9'u); 144 fps'te
 * 1771 µs ve 1816 µs (%26). Yani eski hatanın maliyeti FPS'e doğrusal ölçeklenir ve
 * yüksek yenileme hızlarında en kötüdür.</em>
 *
 * <p><b>Yavaş oyun.</b> Kare bütçesini aşan bir kare geldiğinde sınırlayıcı hiç
 * beklemez ve kare süresine dokunmaz — oyun zaten hedefin altındadır, yapılacak tek
 * şey karışmamaktır. Bu, FPS Sync'in en sık gerçekleşen durumudur.
 *
 * <p><b>Test edilebilirlik.</b> Zaman ve bekleme çağrıları alan üzerinden
 * değiştirilebilir ({@link #nanoTime}, {@link #sleeper}); testler sanal saatle
 * ölçer. Sanal saat bir modeldir ve mutlak spin sayıları için
 * {@code FrameLimiterCpuProbe} kullanılmalıdır.
 */
public class FrameLimiter {

    /** Oyunun kullandığı tek örnek. */
    /**
     * Üretimde kullanılan, hiçbir iş yapmayan spin kancası.
     *
     * <p>Döngü bu referansı karşılaştırarak test yolunu ayırır; bu sayede
     * {@code spinHook} çağrısı üretimde hiç yapılmaz. Karşılaştırma sembolik bir
     * eşitliktir, {@code null} denetimi değil.
     *
     * <p><b>Tanım sırası önemlidir.</b> Bu sabit {@link #INSTANCE}'tan <em>önce</em>
     * yazılmalıdır. Örnek alan başlatıcıları yapılandırma sırasında çalışır;
     * INSTANCE bu sınıfın statikleri başlatılmadan kurulursa {@code spinHook}
     * null'a düşer ve döngü üretimde {@code NullPointerException} alır. Testler
     * {@code spinHook}'u her zaman açıkça atadığı için bu hata testlerde görünmez —
     * yalnız üretim yolunda ortaya çıkar.
     */
    private static final Runnable NO_SPIN_HOOK = () -> { };

    public static final FrameLimiter INSTANCE = new FrameLimiter();

    /** Kesinti fırlatabilen bekleme geri çağrısı; testlerde sanal saatle değiştirilir. */
    @FunctionalInterface
    interface Sleeper {
        void park(long nanos) throws InterruptedException;
    }

    /**
     * Bekleme sonrası meşgul bekleme penceresi.
     *
     * <p>0.1 ms: bekleme çağrısının aşımını (ölçülen ~90 µs) tolere edecek kadar
     * uzun, ama bir çekirdeği boşuna meşgul etmeyecek kadar kısa. Bu pencerenin
     * tamamı her karede kullanılmaz — aşım büyükse döngü hiç çalışmaz.
     */
    private static final long SPIN_WINDOW_NS = 100_000L;
    /**
     * Bir karede en fazla bu kadar park çağrısı yapılır.
     *
     * <p><b>Güvenlik ağıdır, ayar değil.</b> Asıl mekanizma "park ilerleme yaptı mı"
     * kontrolüdür; bu üst sınır yalnızca o kontrolün yetmediği patolojik durumlar
     * içindir. Rapor kare başına ortalama park çağrısını yazdığı için ağa ne zaman
     * takıldığımız ölçülür — kovalamaya gerek kalmaz.
     */
    private static final int MAX_PARK_CALLS_PER_FRAME = 32;
    /**
     * Bir park çağrısının CPU maliyeti: ~45 µs.
     *
     * <p>Ölçülen değer, çevrimdışı ({@code FrameLimiterCpuProbe}): 1 ms'de 24 µs,
     * 5 ms'de 45 µs, 15 ms'de 51 µs, 50 ms'de 53 µs. Süreye büyük ölçüde bağlı değil,
     * sabit bir gider — çekirdekten çıkmak ve futex'e girmek.
     */
    private static final long PARK_CPU_COST_NS = 45_000L;

    /**
     * Bir park çağrısının en az vermesi gereken süre.
     *
     * <p>Değer {@link #PARK_CPU_COST_NS}: bir çağrının CPU maliyeti kadar. Daha az
     * süre uyuyan çağrı, spin ile geçirilecek süreden daha pahalıdır.
     */
    private static final long PARK_MIN_WORTH_NS = PARK_CPU_COST_NS;

    private long lastFrameTime = 0;
    private boolean fpsSyncEnabled = false;
    private int manualFpsLimit = 0;
    private int monitorRefreshRate = 60;

    LongSupplier nanoTime = System::nanoTime;
    Sleeper sleeper = LockSupport::parkNanos;
    LongConsumer onSpin = spin -> { };
    Runnable spinHook = NO_SPIN_HOOK;

    // --- /fpsync status sayaçları -------------------------------------------------
    // Yalnız kare içinde gerçekten beklendiğinde artar; beklemeyen karelerde bu
    // alanlara hiç dokunulmaz. Böylece "limiter boşta" rejiminin ölçüm maliyeti
    // sıfıra yakın kalır. Değerler her kare sonunda {@link #resetFrameStats()} ile
    // tüketilir.
    /** Bu karede sınırlayıcı gerçekten bekledi mi (1/0). */
    int waitedLastFrame;
    /** Bu karedeki park çağrısı sayısı. */
    int parkCallsLastFrame;
    /** Bu karede park için istenen süre toplamı. */
    long parkRequestedNsLastFrame;
/**
       * Bu karede park'ın aşımı, <b>işaretli</b>: pozitif geç döndü, negatif erken döndü.
       *
       * <p>Erken dönüşün ayrı tutulması gerekir: kalan süre spin ile yakılır, yani
       * erken dönüş miktarı spin süresini doğrudan açıklar.
       */
      long parkOvershootNsLastFrame;
      /** Bu karede park çağrılarının gerçekte geçirdiği süre. */
      long parkElapsedNsLastFrame;
      /**
       * Park anında interrupt bayrağı set olan kare (1/0).
       *
       * <p><b>Teşhis;</b> temizlenmez. {@code LockSupport.parkNanos} interrupt durumu
       * set ise anında döner, kalan süre spin ile yakılır. 2026-10-02'de üç koşuda da
       * park ikiye bölünmüştü ve {@code p05} sıfırdı.
       */
      int interruptFlagSetFrames;
      /** Bu karede yakalanan {@link InterruptedException} sayısı. */
      int interruptsCaught;
    /**
     * Bu karede fayda korumasının devreye girdiği sayı: yani bir park çağrısı kendi
     * CPU maliyetinden ({@link #PARK_CPU_COST_NS}) az uyudu ve buna rağmen <em>bir kez
     * daha</em> denendi.
     *
     * <p><b>Neden bir kez daha.</b> Gerçek dağılım iki modludur:
     * <pre>
     * erken uyku  medyan 0,0 µs · p05 0,0 µs · p95 6.740 µs
     * </pre>
     * Karelerin yarısı hiç uyumadan dönüyor, bir kısmı 6,7 ms'ye kadar uyuyor. Kural
     * her çağrıyı tek başına değerlendirip 0 µs popülasyonunu gördüğünde o karedeki
     * 6,7 ms'lik kuyruğu da reddediyordu. Beklenen değer 0,05 × 6.740 ≈ 337 µs
     * kazanç karşılığında 45 µs gider — yani tekrar katlanarak pozitif.
     */
    int retryAfterFailCalls;
    /**
     * {@link #retryAfterFailCalls} içinden, <b>tekrarın gerçekten uyuduğu</b> olanlar.
     *
     * <p>Karar sayısı budur: {@code retryAfterFailSleptCalls / retryAfterFailCalls}
     * oranı düşükse bu değişiklik geri alınır. Oran yüksekse kalsın demektir.
     */
    int retryAfterFailSleptCalls;
    /** Tekrar denemelerin toplam uyuma süresi; kazanılan sürenin doğrudan ölçümü. */
    long retryAfterFailSleptNs;
    /** Bu karede harcanan spin süresi. */
    long spinNsLastFrame;
    /** Beklemeden önceki kare için hedef bütçe; 0 ise sınırlayıcı kapalıydı. */
    long frameBudgetNsLastFrame;
    /**
     * Bu karede okunan {@code nanoTime}.
     *
     * <p>Ölçüm kancası bunu <b>kare süresi</b> olarak kullanır; böylece sıcak yola
     * ek bir {@code System.nanoTime()} çağrısı girmesine gerek kalmaz. Sınırlayıcı
     * kapalıyken okunmaz ve 0 kalır — o durumda kare zamanlaması ölçülmez, çünkü
     * sınırlayıcının yapacağı bir şey yoktur.
     */
    long nanoTimeLastFrame;

    /**
     * Kare sayaclarını sifirlar.
     *
     * <p>Ölçüm kancası her karede bir kez çağırır. Maliyeti altı alan yazımıdır.
     */
    void resetFrameStats() {
        waitedLastFrame = 0;
        parkCallsLastFrame = 0;
        parkRequestedNsLastFrame = 0;
        parkOvershootNsLastFrame = 0;
        parkElapsedNsLastFrame = 0;
        interruptFlagSetFrames = 0;
        interruptsCaught = 0;
        retryAfterFailCalls = 0;
        retryAfterFailSleptCalls = 0;
        retryAfterFailSleptNs = 0;
        spinNsLastFrame = 0;
        frameBudgetNsLastFrame = 0;
        nanoTimeLastFrame = 0;
    }

    /**
     * Dikişleri üretim değerlerine döndürür.
     *
     * <p>Testler ve problar alanları geçici olarak değiştirir. Bu metot olmadan
     * geri dönüş <b>görünmez bir hataya</b> yol açar: {@code spinHook = () -> {}}
     * yazmak, üretimde kullanılan {@link #NO_SPIN_HOOK} referansıyla <em>farklı</em>
     * bir nesnedir (lambda özdeşliği referans eşitliğine değil değer eşitliğine
     * bakar), bu yüzden döngü test dalına geçer ve üretim yolu ölçülmez. Bu yüzden
     * sembolik karşılaştırma yerine bu açık metot tercih edildi.
     */
    void useProductionSeams() {
        this.nanoTime = System::nanoTime;
        this.sleeper = LockSupport::parkNanos;
        this.onSpin = spin -> { };
        this.spinHook = NO_SPIN_HOOK;
    }

    public void setEnabled(boolean value) {
        fpsSyncEnabled = value;
        lastFrameTime = 0;
    }

    public void setManualLimit(int fps) {
        manualFpsLimit = fps;
        lastFrameTime = 0;
    }

    /** FPS Sync modu açık mı — {@code /fpsync status} bunu raporlar. */
    public boolean isSyncEnabled() {
        return fpsSyncEnabled;
    }

    public void setMonitorRefreshRate(int hz) {
        if (hz > 0) {
            monitorRefreshRate = hz;
        }
    }

    public void reset() {
        lastFrameTime = 0;
        fpsSyncEnabled = false;
        manualFpsLimit = 0;
        monitorRefreshRate = 60;
    }

    public void limitFrame() {
        int targetFps;

        if (fpsSyncEnabled) {
            targetFps = monitorRefreshRate;
        } else if (FpsSyncOption.isManual(manualFpsLimit)) {
            targetFps = manualFpsLimit;
        } else {
            return; // sınırsız (0 ya da FpsSyncOption.UNLIMITED ve üstü)
        }

        if (targetFps <= 0) return;

        long frameBudgetNs = 1_000_000_000L / targetFps;
        frameBudgetNsLastFrame = frameBudgetNs;
        long now = nanoTime.getAsLong();
        nanoTimeLastFrame = now;

        if (lastFrameTime == 0) { lastFrameTime = now; return; }

        long nextFrameTime = lastFrameTime + frameBudgetNs;

        if (now >= nextFrameTime) { lastFrameTime = now; return; }

        // Buraya gelmek demek sınırlayıcının bu karede gerçekten beklediğidir.
        waitedLastFrame = 1;

        // Park'a tek başına güvenilmez; güvenmeyelim.
        //
        // 2026-10-02'de park karelerin yarısında istenenden 7,69 ms ERKEN döndü ve
        // kalan sürenin tamamı Thread.onSpinWait() ile yanıldı: kare başına 4.330 µs
        // CPU, bir çekirdeğin %18'i. Kare süresi doğruydu (park + spin sabit), yalnız
        // beklemenin maliyeti yüksekti.
        //
        // Çözüm: kalan süre spin penceresinin üstünde olduğu sürece park TEKRAR
        // denenir, yalnız son pencere spin ile kapanır. Böylece park'ın neden erken
        // döndüğünü bilmeye gerek kalmaz — erken de döne, zamanında da döne, geç de
        // döne, üçünde de kalan süre kapanır.
        //
        // NanoTime maliyeti: ilk turun `before`u zaten girişte okunmuş `now`dur,
        // sonraki turlar bir önceki turun sonucu. İlk denemede ek okuma yoktur.
        //
        // 1.3.0'ın eklediği fayda koruması "başarısız çağrıdan sonra koşulsuz kır"
        // diyordu ve gerçek koşuda işe yaramadı: kare başına park çağrısı 1,21'de
        // kaldı, yani koruma çoğu karede o karedeki 6,7 ms'lik kuyruğu görmeden
        // vazgeçti. Aşağıdaki kural onu bir kez daha denemeye çevirir ve sonucu
        // sayar; karar o sayılara bakar.
        long before = now;
        boolean retriedAfterFail = false;
        while (true) {
            long left = nextFrameTime - before;
            if (left <= SPIN_WINDOW_NS) {
                break;
            }
            long sleepNs = left - SPIN_WINDOW_NS;
            parkCallsLastFrame++;
            parkRequestedNsLastFrame += sleepNs;

            // Teşhis: park anında kesinti bayrağı set mi? Okunur, TEMİZLENMEZ.
            // Temizlemek oyun iş parçacığının davranışını değiştirirdi; burada yalnız
            // ölçülüyor. (2026-10-02'de bu bayrağın hiç set olmadığı görüldü, yani
            // erken dönüşün sebebi başka.)
            if (Thread.currentThread().isInterrupted()) {
                interruptFlagSetFrames = 1;
            }

            try {
                // Nanosaniye değeri doğrudan korunur. Thread.sleep(ms) kullanılsaydı
                // kırpma 0.1 ms'lik spin penceresini yutardı.
                sleeper.park(sleepNs);
            } catch (InterruptedException e) {
                interruptsCaught++;
                Thread.currentThread().interrupt();
            }

            long after = nanoTime.getAsLong();
            long elapsed = after - before;
            parkElapsedNsLastFrame += elapsed;
            // İşaretli toplam: pozitif = geç döndü, negatif = erken döndü.
            parkOvershootNsLastFrame += elapsed - sleepNs;
            before = after;

            // Başarısız bir çağrıdan sonra TAM OLARAK BİR KEZ daha dene.
            //
            // 1.3.0 burada koşulsuz kırıyordu ve yanlış bir varsayıma dayanıyordu:
            // "bu çağrı uyumadı" demek "sonraki de uyumayacak" demek değildir. Gerçek
            // dağılım iki modludur (medyan 0 µs, p95 6.740 µs), yani bir sonraki çağrı
            // 6,7 ms uyuyabilir. Ek gider 45 µs, beklenen kazanç ~337 µs.
            //
            // İki koşulda vazgeçilir: (a) zaten bir kez denendi, (b) istenen süre
            // zaten 45 µs'un altında — o zaman hiçbir çağrı faydalı olamaz, kuyruğun
            // sonundaki bu çağrılar sayacı boşa kirletmesin.
            //
            // Döngüden çıkarken kalan süre spin'e eklenir; kare süresi değişmez.
            if (elapsed < PARK_MIN_WORTH_NS) {
                if (retriedAfterFail || sleepNs < PARK_MIN_WORTH_NS) {
                    break;
                }
                retriedAfterFail = true;
                retryAfterFailCalls++;
                continue;
            }
            // Başarısız denemeden sonraki bu çağrı işe yaradı: faydayı kaydet.
            if (retriedAfterFail) {
                retryAfterFailSleptCalls++;
                retryAfterFailSleptNs += elapsed;
            }
            if (parkCallsLastFrame >= MAX_PARK_CALLS_PER_FRAME) {
                break;
            }
        }

        long spinStart = before;
        Runnable hook = spinHook;
        if (hook == NO_SPIN_HOOK) {
            while (nanoTime.getAsLong() < nextFrameTime) {
                Thread.onSpinWait();
            }
        } else {
            // Sanal saatli testler döngüyü yalnız bu kanca ilerletebilir.
            // Gerçek zamanda döngü saat okumasıyla kendiliğinden kapanır.
            while (nanoTime.getAsLong() < nextFrameTime) {
                hook.run();
            }
        }
        spinNsLastFrame = nanoTime.getAsLong() - spinStart;
        onSpin.accept(spinNsLastFrame);

        lastFrameTime = nextFrameTime;
    }
}
