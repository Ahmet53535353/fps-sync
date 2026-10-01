package com.fpssync.mixin;

import com.fpssync.FpsSyncMod;
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
                    if (value <= 0) {return Text.literal("FPS Sync");}
                    if (value >= 1010) {return Text.translatable("options.framerateLimit.max");}
                    return Text.translatable("options.framerate", value);
                },
                new SimpleOption.ValidatingIntSliderCallbacks(0, 101).withModifier(
                        sliderPos -> {
                            if (sliderPos == 0) {return -10;}
                            if (sliderPos >= 101) {return 1010;}
                            return sliderPos * 10;
                        },
                        value -> {
                            if (value <= 0) {return 0;}
                            if (value >= 1010) {return 101;}
                            return Math.min(value / 10, 100);
                        }
                ),
                Codec.intRange(-10, 1010),
                120,
                value -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (value <= 0) {
                        FpsSyncMod.LIMITER.setEnabled(true);
                        FpsSyncMod.LIMITER.setManualLimit(0);

                        if (client != null && client.getWindow() != null) {
                            client.getWindow().setFramerateLimit(Integer.MAX_VALUE);
                        }

                        return;
                    }

                    FpsSyncMod.LIMITER.setEnabled(false);
                    FpsSyncMod.LIMITER.setManualLimit(value >= 1010 ? 0 : value);

                    if (client != null && client.getWindow() != null) {
                        client.getWindow().setFramerateLimit(value >= 1010 ? Integer.MAX_VALUE : value);
                    }
                }
        );
        ((GameOptions)(Object)this).load();
    }
}