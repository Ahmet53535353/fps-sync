package com.fpssync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FpsSyncMod implements ClientModInitializer {

    public static final String MOD_ID = "fps-sync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Tek örnek; oyun ve testler aynı mantığı kullanır. */
    public static final FrameLimiter LIMITER = FrameLimiter.INSTANCE;

    /**
     * FPS Sync kaydırıcısının Sodium ayarlar ekranına başarıyla eklenip eklenmediği.
     *
     * <p>Sodium'un dahili config API'si sürümler arasında sık değişir ve FPS-Sync bu
     * API'ya doğrudan karışır. Bir gün hedef bulunamazsa injector sessizce devre dışı
     * kalır ({@code require = 0}) ve oyun çökmez; ama kullanıcı kaydırıcının neden
     * kaybolduğunu anlamaz. Bu bayrak, o durumda açık bir uyarı basılmasını sağlar.
     *
     * <p><b>Bu bayrak tek başına anlam taşımaz</b> — Sodium kurulu değilse de
     * {@code false} kalır. İkisini ayırmak için {@link #sodiumPresent} ayrı tutulur;
     * aksi hâlde Sodium'suz kurulumda "Sodium'un config API'si beklenenden farklı"
     * denilen bir uyarı basılır ve kullanıcı modu hatalı sanar.
     */
    private static volatile boolean sliderInjected = false;

    /** Sodium kurulu mu. Kurulu değilse slider uyarısı anlamsızdır. */
    private static final boolean SODIUM_PRESENT = SodiumPresence.isPresent();

    public static void markSliderInjected() {
        sliderInjected = true;
    }

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            int value = client.options.getMaxFps().getValue();
            if (FpsSyncOption.isSync(value)) {
                LIMITER.setEnabled(true);
                LIMITER.setManualLimit(0);
                if (client.getWindow() != null) {
                    client.getWindow().setFramerateLimit(FpsSyncOption.toWindowLimit(value));
                }
                if (!sliderInjected && SODIUM_PRESENT) {
                    warnSliderMissing();
                } else if (!sliderInjected) {
                    infoSodiumAbsent();
                }
                return;
            }
            LIMITER.setEnabled(false);
            LIMITER.setManualLimit(FpsSyncOption.manualLimitOrZero(value));
            if (client.getWindow() != null) {
                client.getWindow().setFramerateLimit(FpsSyncOption.toWindowLimit(value));
            }
        });
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
