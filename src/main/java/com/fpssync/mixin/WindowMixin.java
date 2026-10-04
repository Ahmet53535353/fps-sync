package com.fpssync.mixin;

import com.fpssync.FpsSyncMod;
import com.fpssync.FpsSyncOption;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pencere tarafındaki iki bağımsız iş.
 *
 * <p>{@code setFramerateLimit} düzeltmesi FPS hedefini sınırlayıcıya uygun biçime
 * çevirir. {@code swapBuffers} kancaları ise <b>yalnızca ölçüm</b>yürütür: sunum
 * çağrısının ne kadar sürdüğünü kaydeder, çağrının kendisine dokunmaz.
 */
@Mixin(Window.class)
public class WindowMixin {

    @ModifyVariable(method = "setFramerateLimit", at = @At("HEAD"), argsOnly = true)
    private int fpssync$fixCustomFpsValues(int framerateLimit) {
        return FpsSyncOption.toWindowLimit(framerateLimit);
    }

    /**
     * Sunum çağrısı başladı — yalnızca zaman damgası alınır.
     *
     * <p><b>Ölçümdür, müdahale değil.</b> Çağrıya dokunulmaz, hiçbir şey değiştirilmez.
     *
     * <p>Ne için: geç kalmanın iki ayrı sebebi vardır — iş parçacığı geç kaldı (CPU) ya
     * da iş parçacığı zamanında bitti ama GPU kareyi hazırlayamadı. V-Sync kapalıyken
     * {@code glfwSwapBuffers} normalde yüzlerce mikrosaniyede döner, kuyruk doluysa
     * <b>bloklar</b>. Yani bu süre "GPU bizi mi bekliyor" sorusunun doğrudan cevabıdır.
     */
    @Inject(method = "swapBuffers", at = @At("HEAD"))
    private void fpssync$swapBegin(CallbackInfo ci) {
        FpsSyncMod.onSwapBegin();
    }

    /** Sunum çağrısı bitti; geçen süre ölçülür. Yine yalnızca ölçüm. */
    @Inject(method = "swapBuffers", at = @At("RETURN"))
    private void fpssync$swapEnd(CallbackInfo ci) {
        FpsSyncMod.onSwapEnd();
    }
}