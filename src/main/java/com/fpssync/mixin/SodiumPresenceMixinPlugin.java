package com.fpssync.mixin;

import com.fpssync.SodiumPresence;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Sodium kurulu değilse Sodium'a ait mixinleri <b>hiç uygulamaz</b>.
 *
 * <p>FPS-Sync, FPS Sync girdisini vanilla ayarlar ekranına zaten enjekte eder
 * ({@code GameOptionsMixin}). Sodium'a özel {@code SodiumFpsLimitMixin} yalnızca
 * ikinci bir kolaylık girdisidir, bu yüzden mod Sodium'u zorunlu tutmaz.
 *
 * <p>Bunu yapmak zorunluydu: {@code @Mixin(value = SodiumConfigBuilder.class)} hedefi
 * bulamazsa Mixin hata fırlatır ve {@code required: true} nedeniyle oyun açılışta
 * çöker. Daha önce bunu {@code "sodium": "*"} bağımlılığıyla "çözüyorduk"; o
 * bağımlılık kaldırılınca karar buraya taşındı.
 *
 * <p>Arayüzün adı Mixin 0.8.7'de {@code IMixinConfigPlugin}'dir ({@code IMixinPlugin} değil);
 * yanlış ad yazılmışsa sınıf derlenmez ve config yüklenemez.
 *
 * <p>Mixin sınıfının <b>imzalarında</b> Sodium tipleri vardır
 * ({@code SodiumConfigBuilder}, {@code IntegerOptionBuilder}). Bu yüzden sınıf
 * yüklenmeden uygulanmamalıdır; {@code shouldApplyMixin} kararı mixin
 * dönüştürülmeden <b>önce</b> verildiği için referans çözülmez ve
 * {@code NoClassDefFoundError} oluşmaz. Bu, eklentinin en kırılgan noktasıdır ve
 * gerçek oyunda doğrulanmıştır (Sodium'suz kurulum açılıp Slider çalışmaktadır).
 *
 * <p>Yalnız <code>fps-sync.sodium.mixins.json</code> bu eklentiyi kullanır; ana
 * config'teki mixinler her koşulda uygulanır.
 */
public class SodiumPresenceMixinPlugin implements IMixinConfigPlugin {

    /**
     * Tek çalıştırılan karar: Sodium kurulu mu.
     *
     * <p>{@link IMixinPlugin} uygulamaları Mixin tarafından <b>tek örnek</b> olarak
     * kullanılır; bu yüzden karar burada bir kez hesaplanıp saklanır, her mixin için
     * yeniden hesaplanmaz.
     */
    private boolean sodiumPresent;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumPresent = SodiumPresence.isPresent();
        if (!sodiumPresent) {
            System.out.println("[fps-sync] Sodium bulunamadı; FPS Sync girdisi yalnızca "
                    + "vanilla Video Ayarları'nda yer alacak.");
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sodiumPresent;
    }

    // --- aşağıdakiler varsayılan davranış; karışmamaları için açıkça yazıldı ---

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // Başka config'lerin hedefleriyle birleştirme yok.
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
            String mixinClassName, IMixinInfo mixinInfo) {
        // Uygulama öncesi değişiklik yok.
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
            String mixinClassName, IMixinInfo mixinInfo) {
        // Uygulama sonrası değişiklik yok.
    }
}