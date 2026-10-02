package com.fpssync.mixin;

import com.fpssync.FpsSyncMod;
import com.fpssync.FpsSyncOption;
import com.mojang.serialization.Codec;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla'nın FPS limiti seçeneğine "FPS Sync" konumunu ekler.
 *
 * <h2>Sentinel sözleşmesi</h2>
 * Minecraft tek bir tam sayı saklar, oysa üç anlam gerekir: FPS Sync, elle sınır,
 * sınırsız. Bu yüzden iki özel değer ayrılmıştır ({@link FpsSyncOption}):
 * {@code <= 0} FPS Sync, {@code >= 1010} sınırsız.
 *
 * <h2>Neden kurulum iki yerden yapılıyor</h2>
 * Vanilla'nın {@code maxFps} codec'i {@code intRange(10, 260)} — yani
 * {@code 1010} ve {@code -10} sentinel'lerinin ikisini de <b>reddeder</b>. Oyun
 * logundaki {@code Value -10 outside of range [10:260]} satırı tam olarak budur.
 *
 * <p>Bu hata, vanilla'nın {@code load()} çağrısı bizim seçeneğimiz kurulmadan
 * <em>önce</em> çalıştığı için oluşuyor. Bu mixin iki kurulum yolu dener:
 * <ol>
 *   <li><b>{@link #installBeforeLoad} (tercih edilen).</b> Vanilla kurucusunun
 *       {@code load()} çağrısını yakalar; seçeneği <b>önce</b> kurar, sonra
 *       {@code load()} <b>bir kez</b> çalışır. Böylece dosya doğru codec ile
 *       okunur: hata kaybolur, ayar iki kez yüklenmez.</li>
 *   <li><b>{@link #installAtReturn} (yedek).</b> İlk yol çalışmazsa — örneğin
 *       Minecraft bu sürümde {@code load()} çağrısını kurucudan kaldırmışsa —
 *       kurucu bittikten sonra kurar ve {@code load()}'u çağırır. Bu, düzeltmeden
 *       önceki davranışın <b>birebir</b> kendisidir.</li>
 * </ol>
 *
 * <p>İki yol da {@code require = 0}: hedef bulunamazsa injector sessizce devre dışı
 * kalır, oyun çökmez. Böylece hiçbir Minecraft sürümünde risk yok — en kötü durum
 * bugünkü davranışa dönmek.
 *
 * <p>{@code fpssync$installed} bayrağı ikinci kurulumun (ve dolayısıyla ikinci
 * {@code load()} çağrısının) olmasını engeller.
 */
@Mixin(GameOptions.class)
public class GameOptionsMixin {

    @Shadow @Final @Mutable private SimpleOption<Integer> maxFps;

    /** Seçenek bu oturumda kuruldu mu. Çift kurulumı ve çift {@code load()}'u önler. */
    @Unique private boolean fpssync$installed = false;

    /**
     * Tercih edilen yol: seçeneği vanilla {@code load()} çağrısından hemen önce kur.
     *
     * <p>Böylece {@code load()} dosyayı <b>modun</b> codec'iyle okur: sentinel'ler
     * kabul edilir, logda hata satırı çıkmaz, ayar tek seferde yüklenir.
     *
     * <p>{@code require = 0}: bu Minecraft sürümünde kurucudan {@code load()}
     * çağrısı yoksa bu yol sessizce atlanır ve {@link #installAtReturn} devreye girer.
     */
    @Redirect(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/option/GameOptions;load()V"),
            require = 0)
    private void installBeforeLoad(GameOptions self) {
        install();
        self.load();
    }

    /**
     * Yedek yol: {@link #installBeforeLoad} çalışmadıysa kurucu bittikten sonra kur.
     *
     * <p>Davranış düzeltmeden önceki hâliyle aynıdır (kurulum + ikinci {@code load()}).
     */
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void installAtReturn(CallbackInfo ci) {
        if (fpssync$installed) {
            return;
        }
        install();
        ((GameOptions) (Object) this).load();
    }

    /** FPS Sync konumunu taşıyan seçeneği kurar. İdempotenttir. */
    @Unique
    private void install() {
        if (fpssync$installed) {
            return;
        }
        fpssync$installed = true;
        this.maxFps = new SimpleOption<>(
                "options.framerateLimit",
                SimpleOption.emptyTooltip(),
                (optionText, value) -> {
                    if (FpsSyncOption.isSync(value)) {return Text.literal("FPS Sync");}
                    if (FpsSyncOption.isUnlimited(value)) {return Text.translatable("options.framerateLimit.max");}
                    return Text.translatable("options.framerate", value);
                },
                new SimpleOption.ValidatingIntSliderCallbacks(
                        FpsSyncOption.SLIDER_MIN, FpsSyncOption.SLIDER_MAX)
                        .withModifier(FpsSyncOption::sliderToValue, FpsSyncOption::valueToSlider),
                Codec.intRange(FpsSyncOption.CODEC_MIN, FpsSyncOption.CODEC_MAX),
                FpsSyncOption.DEFAULT_FPS,
                value -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (FpsSyncOption.isSync(value)) {
                        FpsSyncMod.LIMITER.setEnabled(true);
                        FpsSyncMod.LIMITER.setManualLimit(0);

                        if (client != null && client.getWindow() != null) {
                            client.getWindow().setFramerateLimit(FpsSyncOption.toWindowLimit(value));
                        }

                        return;
                    }

                    FpsSyncMod.LIMITER.setEnabled(false);
                    FpsSyncMod.LIMITER.setManualLimit(FpsSyncOption.manualLimitOrZero(value));

                    if (client != null && client.getWindow() != null) {
                        client.getWindow().setFramerateLimit(FpsSyncOption.toWindowLimit(value));
                    }
                }
        );
    }
}