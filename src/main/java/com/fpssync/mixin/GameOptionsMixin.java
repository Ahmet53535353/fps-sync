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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameOptions.class)
public class GameOptionsMixin {

    @Shadow @Final @Mutable private SimpleOption<Integer> maxFps;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void overrideFpsSlider(CallbackInfo ci) {
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
        ((GameOptions)(Object)this).load();
    }
}