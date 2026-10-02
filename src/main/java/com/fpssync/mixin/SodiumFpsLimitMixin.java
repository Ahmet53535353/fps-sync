package com.fpssync.mixin;

import com.fpssync.FpsSyncOption;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * FPS-Sync, FPS limiti kaydırıcısını Sodium'un ayarlar ekranına ekler; böylece
 * "Unlimited" yerine bir "FPS Sync" konumu sunar.
 *
 * <p>Sodium 0.8.x, slider aralığını ve değer biçimlendiricisini doğrudan
 * {@code SliderControl} alanlarında değil, seçenek tanımı zincirinde taşır. Bu
 * yüzden müdahale {@code IntegerOptionBuilder}'ın kurulum çağrılarına yönlendirilir.
 *
 * <h2>Neden "slider eklendi" bildirimi kaldırıldı</h2>
 * Burada daha önce {@code buildGeneralPage} başına bir {@code @Inject} vardı ve
 * slider'ın eklendiğini bildiriyordu. Bu <b>yanlış bir ölçüttü</b>: Sodium config'ini
 * {@code MinecraftClient.onInitFinished} sonunda kurar, oysa uyarı
 * {@code CLIENT_STARTED}'da soruluyordu. Arada ~7 saniye fark var ve bayrak o an
 * {@code false} olduğu için oyun, slider görünmesine rağmen <em>her açılışta</em>
 * "kaydırıcı eklenemedi" uyarısı bastı.
 *
 * <p>Doğru ölçüt, karıştırmanın hedefe <b>uygulanmış olmasıdır</b>
 * ({@link com.fpssync.mixin.SodiumPresenceMixinPlugin#postApply}). Uygulandıysa slider,
 * Sodium ayarlarını açtığında kesinlikle oradadır — yani sorulması gereken soru
 * "slider'ın yazısı göründü mü" değil, "karıştırma tuttu mu"dur.
 *
 * <h2>Ordinal'ler neden buradan</h2>
 * Aşağıdaki ordinal'ler {@code buildGeneralPage} içindeki çağrı sırasıdır:
 * <pre>
 *   setRange(III)          -> framerate_limit  ordinal 3   (4. çağrı)
 *   setValueFormatter(...) -> framerate_limit  ordinal 5   (6. çağrı)
 * </pre>
 * Bu değerler Sodium'un <b>gerçek bytecode'ından</b> türetilmiştir (2026-10-02'de
 * yeniden doğrulandı); kaynak dosyadaki satır sırasından hesaplanamaz, çünkü
 * koşullu bloklar bytecode'da ayrı çağrı noktaları üretir.
 * {@code SodiumApiContractTest} her derlemede doğrular.
 *
 * <p>Enjeksiyon {@code require = 0} ile yapılır: Sodium'un config API'si
 * değiştiğinde hedef bulunamaz ve oyun çökmez, bunun yerine FPS Sync kaydırıcısı
 * <b>yalnız Sodium'un ayarlarında</b> kaybolur — vanilla ayarlarındaki girdi her zaman
 * çalışmaya devam eder. Sessizce bozulan bir kaydırıcı, çöken bir oyundan daha
 * zor teşhis edilir; bu yüzden durum
 * {@link com.fpssync.SodiumSliderStatus} ile ayrıca izlenir.
 */
@Mixin(value = SodiumConfigBuilder.class, remap = false)
public abstract class SodiumFpsLimitMixin {

	/*
	 * Ordinal'ler buildGeneralPage içindeki çağrı sırasına göredir:
	 *   setRange(III)          -> framerate_limit  ordinal 3
	 *   setValueFormatter(...) -> framerate_limit  ordinal 5
	 *
	 * Bu değerler Sodium'un gerçek bytecode'ından türetilmiştir; kaynak dosyadaki
	 * satır sırasından hesaplanamaz, çünkü koşullu bloklar bytecode'da ayrı çağrı
	 * noktaları üretir. SodiumApiContractTest her derlemede doğrular.
	 */
	@Redirect(method = "buildGeneralPage", remap = false,
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;setRange(III)Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;",
					ordinal = 3),
			require = 0)
	public IntegerOptionBuilder redirectFpsSliderRange(
			IntegerOptionBuilder builder, int min, int max, int step) {
		return builder.setRange(FpsSyncOption.CODEC_MIN, FpsSyncOption.CODEC_MAX, FpsSyncOption.STEP);
	}

	@Redirect(method = "buildGeneralPage", remap = false,
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;setValueFormatter(Lnet/caffeinemc/mods/sodium/api/config/option/ControlValueFormatter;)Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;",
					ordinal = 5),
			require = 0)
	public IntegerOptionBuilder redirectFpsValueFormatter(
			IntegerOptionBuilder builder, ControlValueFormatter original) {
		return builder.setValueFormatter(value -> {
			if (FpsSyncOption.isSync(value)) {
				return Text.literal("FPS Sync");
			}
			if (FpsSyncOption.isUnlimited(value)) {
				return Text.translatable("options.framerateLimit.max");
			}
			return Text.translatable("options.framerate", value);
		});
	}
}
