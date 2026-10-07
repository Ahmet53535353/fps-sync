package com.fpssync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modun giriş noktası ve ölçüm merkezi.
 *
 * <p>Ölçüm zinciri: {@code GameRenderer} karesi bitirir → {@link #recordFrameTiming()}
 * sınırlayıcının sayaçlarını toplar ve kayıtçıya bir kare yazar. Sunum süresi ayrı bir
 * yoldan ({@link #onSwapBegin()} / {@link #onSwapEnd()}) gelir ve aynı kayda boşaltılır.
 *
 * <p>Hiçbir yol ölçümü değiştirmez: oyun mantığına, hasara, sağlığa dokunulmaz.
 */
public class FpsSyncMod implements ClientModInitializer {

    /** Mod kimliği; {@code fabric.mod.json} ile aynı olmalıdır. */
    public static final String MOD_ID = "fps-sync";

    /** Günlüğe yazılan logger. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Tek örnek; oyun ve testler aynı mantığı kullanır. */
    public static final FrameLimiter LIMITER = FrameLimiter.INSTANCE;

    /** Kare zamanlaması ölçümü. Oyun başlarken bir kez ayrılır. */
    private static final FramePacingRecorder PACING = new FramePacingRecorder();

    /** Kare süresi farkı için taban zaman. */
    private static long lastFrameNsBase;

    /**
     * {@code Window#swapBuffers()} süresi toplayıcı.
     *
     * <p>Kare sonunda {@link #recordFrameTiming()} içinde boşaltılır. Zaman damgaları
     * {@link SwapTimer}'a dışarıdan verilir; sınıf kendi saatini okumaz, böylece
     * ölçüm davranışı gerçek zamandan bağımsız sınanabilir.
     */
    private static final SwapTimer SWAP = new SwapTimer();

    /**
     * Sunum çağrısı başladı.
     *
     * <p>{@code WindowMixin} HEAD kancasından gelir. Yalnızca ölçüm: swap çağrısının
     * kendisine dokunulmaz.
     */
    public static void onSwapBegin() {
        SWAP.begin(System.nanoTime());
    }

    /**
     * Sunum çağrısı bitti; geçen süre ölçülür.
     *
     * <p>Başlangıç kaydı yoksa sayılmaz — yalnız bitiş bilmek süre çıkarmaya yetmez.
     */
    public static void onSwapEnd() {
        SWAP.end(System.nanoTime());
    }

    /**
     * {@code SodiumFpsLimitMixin} hedefe başarıyla uygulandı mı?
     *
     * <p><b>Bu bayrak "slider'ın eklenip eklenmediğini" değil, "karıştırmanın
     * uygulanıp uygulanmadığını" tutar.</b> İkisi farklı zamanlarda olur ve karıştırma
     * zamanında ölçülen budur — bkz. {@link SodiumSliderStatus}.
     *
     * <p>Neden ayrı tutulduğu: 2026-10-02'de {@code @Inject} metodunun
     * tetiklendiği "slider eklendi" bayrağı okunuyordu. Oysa Sodium config'ini
     * {@code MinecraftClient.onInitFinished} sonunda kurar; uyarı ise
     * {@code CLIENT_STARTED}'da basılıyor. Arada 7 saniye vardı ve bayrak o an
     * {@code false} olduğu için oyun, slider görünmesine rağmen <em>her açılışta</em>
     * "kaydırıcı eklenemedi" uyarısı bastı. Gerçek oyunda doğrulandı: mixin temiz
     * uygulandı, hiç injector hatası yok, slider ayarlarda duruyor — yine uyarı
     * basılıyordu.
     *
     * <p>Doğru ölçüt, karıştırmanın {@code postApply} ile uygulanmış olmasıdır:
     * uygulandıysa slider, kullanıcı Sodium ayarlarını açtığında kesinlikle oradadır.
     */
    private static volatile boolean sliderInjected = false;

    /** Sodium kurulu mu. Kurulu değilse slider uyarısı anlamsızdır. */
    private static final boolean SODIUM_PRESENT = SodiumPresence.isPresent();

    /**
     * {@code SodiumPresenceMixinPlugin.postApply} çağrısında tetiklenir:
     * karıştırma {@code SodiumConfigBuilder}'a başarıyla uygulandı.
     */
    public static void markSliderInjected() {
        sliderInjected = true;
    }

    /**
     * Slider'ın <b>ölçülmüş</b> durumu.
     *
     * <p>Rapor bu değeri okur. Eskiden {@code sync || !SodiumPresence.isPresent()} gibi
     * türetilmiş bir değer geçiliyordu; bu ifade FPS Sync açıkken <b>her koşuda</b>
     * "uygulandı" üretiyordu — Sodium kurulu bile olmasa. Gerçek sinyal
     * ({@link #sliderInjected}) zaten toplanıyordu, sadece okunmuyordu.
     *
     * <p>Bu metot saf: testler sinyalin her kombinasyonunu enjekte edebilsin diye
     * parametre alır. Üretim yolu {@link #sliderStatus()} kullanır.
     *
     * @param sodiumPresent  Sodium kurulu mu
     * @param mixinApplied   {@code SodiumFpsLimitMixin} hedefe uygulandı mı
     * @return üç durumdan biri
     */
    public static SodiumSliderStatus sliderStatus(boolean sodiumPresent, boolean mixinApplied) {
        return SodiumSliderStatus.decide(sodiumPresent, mixinApplied);
    }

    /**
     * Gerçek slider durumu: kurulu mu ve karıştırma uygulandı mı.
     *
     * @return ölçülmüş durum
     */
    public static SodiumSliderStatus sliderStatus() {
        return sliderStatus(SODIUM_PRESENT, sliderInjected);
    }

    /**
     * Ölçüm kaydı.
     *
     * @return oyun başlangıcından beri biriken kare zamanlaması kaydı
     */
    public static FramePacingRecorder pacing() {
        return PACING;
    }

    /**
     * Kare süresi tabanını sıfırlar.
     *
     * <p>{@link FramePacingRecorder#reset()} histogramları temizler ama taban
     * {@link FpsSyncMod} içinde durur. Sıfırlamadan sonraki ilk kare, aradaki boşluğun
     * tamamını kare süresi olarak yutar: {@code /fpsync status} → oyunu kapat → geri aç
     * akışında "İLK 10 DAKİKA — kare 1" yazıyordu.
     *
     * <p>Sıfırlamayla <b>aynı anda</b> çağrılmalıdır: {@code FramePacingRecorder.reset()}
     * sonrası bu çağrı yapılmazsa bir sonraki kare boşluğu yutar.
     */
    public static void resetFrameTimeBase() {
        lastFrameNsBase = 0;
    }

    /**
     * Sınır durumu oturumda değişti: FPS Sync / elle sınır / sınırsız.
     *
     * <p>Kayıtçıya yazılır ki rapor oturumu <b>geçersiz</b> ilan edebilsin. Gerçek bir
     * koşuda ilk 25 saniye FPS Sync (oyun ~60 FPS), sonra sınırsız (oyun ~25 FPS)
     * toplandı; "gerçek FPS 31,0" ikisinin ortalamasıydı ve hiçbir anlamı yoktu.
     *
     * @param from önceki hedef; 0 sınırsız
     * @param to   yeni hedef; 0 sınırsız
     */
    private static void onTargetChanged(int from, int to) {
        PACING.recordStateChange();
        LOGGER.info("FPS Sync hedefi değişti: {} -> {}. Bu oturumun ortalamaları "
                        + "karşılaştırmada kullanılamaz.",
                from == 0 ? "sınırsız" : from + " Hz",
                to == 0 ? "sınırsız" : to + " Hz");
    }

    /**
     * Durum değişimi kancasını bağlar.
     *
     * <p>{@code limitFrame} her karede çağrıldığı için kanca oyun başında bir kez
     * kurulmalıdır; aksi halde sınırlayıcı hedefi değiştirdiğini kimse öğrenemez.
     */
    public static void bindTargetChangeListener() {
        LIMITER.onTargetChanged = FpsSyncMod::onTargetChanged;
    }

    /**
     * Kare sonunda çağrılır; sınırlayıcının bu kareye ait sayaclarını toplar.
     *
     * <p>Sıcak yolda çalışır ve <b>sıfır ayak izi</b> bırakır (bkz.
     * {@code FramePacingZeroAllocationTest}). Kare süresi için ek {@code nanoTime}
     * çağrısı yapılmaz: sınırlayıcı zaten bir tane okumuş, onun farkı kullanılır.
     *
     * <p>Sınırlayıcı <b>kapalı</b> (sınırsız) iken de kayıt yazılır; bütçe 0 gelir ve
     * kare boşta rejime düşer. Taban koşusu ("sınırlama olmadan oyun ne kadar iyi")
     * tam olarak bu rejimdir: kapı bütçeye bakıyorken ölçüm hiç oluşmuyordu ve
     * {@code /fpsync status} "Henüz kare kaydedilmedi" diyordu. Bkz.
     * {@code UnlimitedRegimeRecordingTest}.
     */
    public static void recordFrameTiming() {
        FrameLimiter limiter = LIMITER;
        long now = limiter.nanoTimeLastFrame;
        long budget = limiter.frameBudgetNsLastFrame;
        // Swap ölçümü: değerler okunur, sonra sıfırlanır. Tek bir kayıt nesnesi
        // yaratılmaz — ölçüm yolu sıfır ayak izi kuralına tabidir.
        long swapNs = SWAP.nsTotal();
        long swapEntries = SWAP.entries();
        long swapMax = SWAP.maxNs();
        SWAP.reset();

        // Bütçe 0 olabilir: sınırlayıcı kapalıyken (sınırsız) kare yine de kaydedilir,
        // çünkü taban koşusu ancak o rejimde alınabilir. `budget` yalnızca gecikme
        // ("hedefe yetişemedi") hesabının paydası; 0 iken lateness ölçülemez ama
        // kare süresi histogramı dolmaya devam eder. Asıl kapı `now`'dur: zaman
        // damgası okunmamışsa kare henüz sınırlayıcıdan geçmemiştir.
        if (now > 0) {
            if (lastFrameNsBase > 0) {
                long frameNs = now - lastFrameNsBase;
                if (frameNs > 0) {
                    PACING.recordFrame(frameNs, budget, limiter.waitedLastFrame == 1,
                            limiter.parkCallsLastFrame, limiter.parkRequestedNsLastFrame,
                            limiter.parkOvershootNsLastFrame, limiter.spinNsLastFrame,
                            limiter.parkElapsedNsLastFrame,
                            limiter.interruptFlagSetFrames, limiter.interruptsCaught,
                            limiter.retryAfterFailCalls, limiter.retryAfterFailSleptCalls,
                            limiter.retryAfterFailSleptNs,
                            swapNs, swapEntries, swapMax);
                }
            }
            lastFrameNsBase = now;
        }
        limiter.resetFrameStats();
    }

    @Override
    public void onInitializeClient() {
        FpsSyncStatusCommand.register();
        bindTargetChangeListener();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            int value = client.options.getMaxFps().getValue();
            if (FpsSyncOption.isSync(value)) {
                LIMITER.setEnabled(true);
                LIMITER.setManualLimit(0);
                if (client.getWindow() != null) {
                    client.getWindow().setFramerateLimit(FpsSyncOption.toWindowLimit(value));
                }
                reportSliderStatus();
                return;
            }
            LIMITER.setEnabled(false);
            LIMITER.setManualLimit(FpsSyncOption.manualLimitOrZero(value));
            if (client.getWindow() != null) {
                client.getWindow().setFramerateLimit(FpsSyncOption.toWindowLimit(value));
            }
        });
    }

    /**
     * Slider durumunu değerlendirip gerekiyorsa mesaj basar.
     *
     * <p>Kararın kendisi saf ve testlidir: {@link SodiumSliderStatus#decide}. Burada
     * yalnızca mesajın <em>ne zaman</em> basılacağı belirlenir.
     */
    private static void reportSliderStatus() {
        SodiumSliderStatus status = SodiumSliderStatus.decide(SODIUM_PRESENT, sliderInjected);
        if (status.isSodiumAbsent()) {
            infoSodiumAbsent();
        } else if (status.isFailure()) {
            warnSliderMissing();
        }
        // MIXIN_APPLIED: slider kullanıcı Sodium ayarlarını açtığında görünecek; sessiz geçilir.
        // 2026-10-02'den önce bu durumda yanlış uyarı basılıyordu.
    }

    private static void warnSliderMissing() {
        LOGGER.warn("""
                FPS Sync: FPS Sync kaydırıcısı Sodium ayarlar ekranına eklenemedi.
                FPS Sync modu yüklü ve çalışıyor, ancak Sodium'un config API'si bu
                sürümde beklenenden farklı olduğu için kaydırıcı görünmeyecek.
                Bu genellikle FPS-Sync'in bu Sodium sürümünü desteklememesinden kaynaklanır.
                FPS Sync yine de kullanılabilir: Ayarlar > Video Ayarları > Kare Hızı
                Sınırı'nın en sol konumu "FPS Sync"tir.""");
    }

    /**
     * Sodium kurulu değilken basılan bilgi mesajı.
     *
     * <p>Bir <b>uyarı</b> değildir: Sodium opsiyoneldir ve girdi vanilla ayarlarında
     * zaten mevcuttur. Yalnızca kullanıcı ikinci bir yer aramasın diye yönlendirir.
     */
    private static void infoSodiumAbsent() {
        LOGGER.info("""
                FPS Sync: Sodium kurulu değil, bu yüzden Sodium ayarlar ekranına
                FPS Sync girdisi eklenmedi. Bu beklenen bir durumdur ve modun çalışmasını
                etkilemez. FPS Sync girdisi Ayarlar > Video Ayarları > Kare Hızı Sınırı'nın
                en sol konumundadır.""");
    }
}
