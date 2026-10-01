package com.fpssync.mixin;

import com.fpssync.FpsSyncMod;
import com.fpssync.MonitorInfoProvider;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    /**
     * Kare bittikten sonra sınırlamayı uygular.
     *
     * <p>Monitör bilgisi önce tazelenir: FPS Sync modunda hedef, monitörün
     * gerçek yenileme hızıdır ve pencere monitörler arasında taşınabilir.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void onFrameEnd(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        MonitorInfoProvider.updateDisplayInfo();
        FpsSyncMod.LIMITER.setMonitorRefreshRate(MonitorInfoProvider.getRefreshRate());
        FpsSyncMod.LIMITER.limitFrame();
    }
}
