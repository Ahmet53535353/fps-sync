package com.fpssync.mixin;

import com.fpssync.FpsSyncOption;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Window.class)
public class WindowMixin {

    @ModifyVariable(method = "setFramerateLimit", at = @At("HEAD"), argsOnly = true)
    private int fpssync$fixCustomFpsValues(int framerateLimit) {
        return FpsSyncOption.toWindowLimit(framerateLimit);
    }
}