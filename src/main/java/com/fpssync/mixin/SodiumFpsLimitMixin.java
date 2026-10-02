package com.fpssync.mixin;

import com.fpssync.FpsSyncMod;
import com.fpssync.FpsSyncOption;
import net.caffeinemc.mods.sodium.api.config.option.ControlValueFormatter;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FPS-Sync, FPS limiti kaydırıcısını Sodium'un ayarlar ekranına ekler; böylece
 * "Unlimited" yerine bir "FPS Sync" konumu sunar.
 *
 * <p>Sodium 0.8.x, slider aralığını ve değer biçimlendiricisini doğrudan
 * {@code SliderControl} alanlarında değil, seçenek tanımı zincirinde taşır. Bu
 * yüzden müdahale {@code IntegerOptionBuilder}'ın kurulum çağrılarına yönlendirilir.
 *
 * <p>Enjeksiyon {@code require = 0} ile yapılır: Sodium'un config API'si
 * değiştiğinde hedef bulunamaz ve oyun çökmez, bunun yerine FPS Sync kaydırıcısı
 * kaybolur. {@link #markSliderApplied} bu durumu FpsSyncMod'a bildirip kullanıcıya
 * görünür bir uyarı bastırır. Sessizce bozulan bir kaydırıcı, çöken bir oyundan
 * daha zor teşhis edilir.
 */
@Mixin(value = SodiumConfigBuilder.class, remap = false)
public abstract class SodiumFpsLimitMixin {

	/** Slider'ın eklendiğini FpsSyncMod'a bildirir. */
	@Inject(method = "buildGeneralPage", at = @At("HEAD"), remap = false, require = 0)
	private void markSliderApplied(ConfigBuilder builder, CallbackInfoReturnable<OptionPageBuilder> ci) {
		FpsSyncMod.markSliderInjected();
	}

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
