package com.fpssync;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sodium'un kurulu olup olmadığını belirler.
 *
 * <p>FPS-Sync, FPS Sync girdisini Sodium'un ayarlar ekranına da enjekte eder. Bu
 * <b>isteğe bağlı</b> bir kolaylık: aynı girdi vanilla'nın kendi video ayarlarında
 * zaten mevcut ({@code GameOptionsMixin}). Bu yüzden Sodium mod.json'da
 * {@code depends} değil {@code suggests} altında durmalıdır.
 *
 * <p>Ayrım olamazdı: mixin hedefi ({@code SodiumConfigBuilder}) yoksa Mixin hata
 * fırlatır, {@code required: true} yüzünden oyun açılışta çöker. O yüzden
 * {@code SodiumFpsLimitMixin} ayrı bir mixin config'inde ve bu sınıfın
 * {@code shouldApplyMixin} kararıyla uygulanıyor.
 *
 * <p>Mod kimliği ({@value #SODIUM_MOD_ID}) kaynak kontrolünde tutulmuyor ve
 * değişebileceği için tek kaynak olarak kullanılmıyor; sınıf varlığı ikinci bir
 * kaynaktır ve ikisi birlikte "kurulu" demektir.
 *
 * <p>Bu sınıf {@link #isPresent()} dışında hiçbir yerde kullanılmaz — yalnız test
 * edilebilirlik için ayrıldı. Gerçek karar {@link #isPresent()} sonucuna verilir.
 */
public final class SodiumPresence {

    /** Sodium'un mod kimliği. */
    public static final String SODIUM_MOD_ID = "sodium";

    /**
     * Enjekte edilen Sodium sınıfının <b>kaynak yolu</b>.
     *
     * <p>Salt okunur: bu alana referans vermek sınıfı yüklemez, yalnızca
     * {@link ClassLoader#getResource} araması yapılır.
     */
    static final String INJECTION_TARGET_CLASS =
            "net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder";

    private SodiumPresence() {
    }

    /**
     * Sodium kurulu mu?
     *
     * <p>İki bağımsız işaretten <b>herhangi biri</b> yeterlidir:
     * <ol>
     *   <li>Sınıfın kaynak dosyası sınıf yolunda var mı (kurulum bütünlüğü),</li>
     *   <li>yoksa mod kimliği FabricLoader'da kayıtlı mı (sürüm farkı, kırık kurulum).</li>
     * </ol>
     *
     * <p><b>Sınıf burada yüklenmez.</b> Doğrudan {@code SodiumConfigBuilder.class}
     * referansı verilseydi JVM sınıfı yüklerdi; Mixin hedef sınıfın kendisinin
     * dönüşümden <em>önce</em> yüklenmesini yasaklar ve
     * {@code MixinTargetAlreadyLoadedException} ile oyun çöker. Bu hata 2026-10-02'de
     * gerçek oyunda oluştu: yalnızca Sodium kurulu olduğunda görünür, çünkü
     * Sodium yokken arama zaten bulamaz.
     *
     * <p>Bu yüzden {@link Class#forName} <b>kullanılmaz</b>; yalnızca
     * {@code .class} dosyasının varlığına bakılır.
     */
    public static boolean isPresent() {
        return isClassAvailable() || isModLoaded();
    }

    /**
     * Enjekte edilen Sodium sınıfı sınıf yolunda var mı?
     *
     * <p>Sınıfı yüklemez. {@link ClassLoader#getResource} yalnızca dosya arar.
     */
    static boolean isClassAvailable() {
        return isClassAvailable(INJECTION_TARGET_CLASS, SodiumPresence.class.getClassLoader());
    }

    /**
     * Verilen sınıfın kaynak dosyası, verilen yükleyicide sınıf yolunda var mı?
     *
     * <p><b>Sınıfı yükleyen hiçbir yol yoktur</b> — yalnız {@code getResource} çağrılır.
     * {@link Class#forName} <b>kullanılmaz</b>, çünkü hedef sınıfı erken tanımlar ve
     * Mixin {@code MixinTargetAlreadyLoadedException} ile oyunu çökertir.
     *
     * <p>Yükleyici parametresi varlığı test edilebilir olsun diye ayrılmıştır:
     * gerçek yükleyici, yüklemeye çalışılırsa hata fırlatan bir yükleyiciyle
     * değiştirilerek bu yasağın bozulmadığı kanıtlanır.
     */
    static boolean isClassAvailable(String className, ClassLoader loader) {
        String resource = className.replace('.', '/') + ".class";
        return loader.getResource(resource) != null;
    }

    /** FabricLoader Sodium'u kurulu görüyor mu? Fabric API yoksa sadece sınıf testi geçerli olur. */
    static boolean isModLoaded() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .isModLoaded(SODIUM_MOD_ID);
        } catch (LinkageError | RuntimeException e) {
            // FabricLoader yoksa ya da erken başlangıçta hata verirse
            // "kurulu değil" demek yanlış olur; bu yüzden sınıf testine düşülür.
            return false;
        }
    }
}