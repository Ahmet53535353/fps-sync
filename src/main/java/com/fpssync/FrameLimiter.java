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
 * <tr><td>park duvar aşımı (timer slack)</td><td>~100 µs</td></tr>
 * <tr><td>allocasyon / {@code limitFrame()} çağrısı</td><td>0 bayt (10<sup>6</sup> çağrı)</td></tr>
 * </table>
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
 * Yani 100 µs doğru seçilmiştir; penceresiz bırakmak sapmayı 6 kat artırıyor, 300 µs
 * ise hem CPU'yi 4 katına çıkarıyor hem sapmayı kötüleştiriyor.
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

    private long lastFrameTime = 0;
    private boolean fpsSyncEnabled = false;
    private int manualFpsLimit = 0;
    private int monitorRefreshRate = 60;

    LongSupplier nanoTime = System::nanoTime;
    Sleeper sleeper = LockSupport::parkNanos;
    LongConsumer onSpin = spin -> { };
    Runnable spinHook = NO_SPIN_HOOK;

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
        long now = nanoTime.getAsLong();

        if (lastFrameTime == 0) { lastFrameTime = now; return; }

        long nextFrameTime = lastFrameTime + frameBudgetNs;

        if (now >= nextFrameTime) { lastFrameTime = now; return; }

        long remaining = nextFrameTime - now;

        // Büyük kısımı uyu; yalnızca son 0.1 ms'yi spin ile tamamla.
        long sleepNs = remaining - SPIN_WINDOW_NS;
        if (sleepNs > 0) {
            try {
                // Nanosaniye değeri doğrudan korunur. Thread.sleep(ms) kullanılsaydı
                // kırpma 0.1 ms'lik spin penceresini yutardı: kalan süre 1.1 ms'nin
                // altına düşünce uyku hiç yapılmaz ve kalan sürenin tamamı spin
                // edilirdi. Ölçülen parkNanos aşımı bu makinede ~90 µs, Thread.sleep
                // aşımı ~150-200 µs; yani pencere gerçekten 0.1 ms olarak uygulanır.
                sleeper.park(sleepNs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long spinStart = nanoTime.getAsLong();
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
        onSpin.accept(nanoTime.getAsLong() - spinStart);

        lastFrameTime = nextFrameTime;
    }
}
