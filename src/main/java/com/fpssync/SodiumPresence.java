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
     * Enjekte edilen Sodium sınıfı. Yükleme sırasında mevcutsa Sodium kuruludur.
     *
     * <p>Bu sınıfa referans verilmesi <b>yükleme tetiklemez</b>; yalnız
     * {@link Class#forName} çağrısı tetikler. Olmayan bir sınıf için
     * {@code NoClassDefFoundError} vereceğinden varlık kontrolü de yapılır.
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
     *   <li>Sınıf gerçekten yüklenebiliyor mu (kurulum bütünlüğü, en güvenilir),</li>
     *   <li>yoksa mod kimliği FabricLoader'da kayıtlı mı (sürüm farkı, kırık kurulum).</li>
     * </ol>
     *
     * <p>Sıra tersine çevrilmedi: önce sınıf, çünkü mod kimliği doğru olsa bile jar
     * bozuksa enjeksiyon yine patlar ve oyun çöker — oysa sınıf testi başarısız
     * olduğunda mixin sessizce uygulanmaz.
     */
    public static boolean isPresent() {
        return isClassPresent() || isModLoaded();
    }

    /** Enjekte edilen Sodium sınıfı yüklenebiliyor mu? */
    static boolean isClassPresent() {
        try {
            Class.forName(INJECTION_TARGET_CLASS, false,
                    SodiumPresence.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
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