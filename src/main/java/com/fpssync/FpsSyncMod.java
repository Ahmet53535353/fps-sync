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
     */
    private static volatile boolean sliderInjected = false;

    public static void markSliderInjected() {
        sliderInjected = true;
    }

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            int value = client.options.getMaxFps().getValue();
            if (value <= 0) {
                LIMITER.setEnabled(true);
                LIMITER.setManualLimit(0);
                if (client.getWindow() != null) {
                    client.getWindow().setFramerateLimit(Integer.MAX_VALUE);
                }
                if (!sliderInjected) {
                    warnSliderMissing();
                }
                return;
            }
            LIMITER.setEnabled(false);
            LIMITER.setManualLimit(value >= 1010 ? 0 : value);
            if (client.getWindow() != null) {
                client.getWindow().setFramerateLimit(value >= 1010 ? Integer.MAX_VALUE : value);
            }
        });
    }

    private static void warnSliderMissing() {
        LOGGER.warn("""
                FPS Sync: FPS Sync kaydırıcısı Sodium ayarlar ekranına eklenemedi.
                FPS Sync modu yüklü ve çalışıyor, ancak Sodium'un config API'si bu
                sürümde beklenenden farklı olduğu için kaydırıcı görünmeyecek.
                Bu genellikle FPS-Sync'in bu Sodium sürümünü desteklememesinden kaynaklanır.""");
    }
}
