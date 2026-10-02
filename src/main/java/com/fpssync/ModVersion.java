package com.fpssync;

import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Mod sürümünü <b>tek yerden</b> okur.
 *
 * <h2>Neden ayrı sınıf, neden sabit değil</h2>
 * Sürüm iki yere yazılırsa ikisi ayrışır: {@code gradle.properties} jar'ın adını ve
 * {@code fabric.mod.json} içeriğini belirler, ama rapor göstereceği metni de bilmesi
 * gerekir. Daha önce ikinci bir sabit ({@code MOD_VERSION}) eklendi ve sürüm
 * güncellendiğinde o unutulabiliyordu — o zaman tanılama aracı kendini <b>yanlış
 * sürümle</b> tanıtır.
 *
 * <p>Burada sürüm hiçbir yerde saklanmaz; {@code fabric.mod.json}'dan okunur. Yani
 * {@code mod_version} güncellenince rapor kendiliğinden güncel olur.
 *
 * <p>Çözümleme <b>tembeldir</b>: sınıf yüklenirken FabricLoader hazır olmayabilir,
 * rapor üretildiğinde ise kesinlikle hazırdır.
 */
public final class ModVersion {

    /** Mod kimliği kayıtlı değilse ya da loader okunamazsa raporlanan değer. */
    public static final String UNKNOWN = "bilinmiyor";

    private static String cached;

    private ModVersion() {
    }

    /**
     * Modun kendi sürümü.
     *
     * @return {@code fabric.mod.json} içindeki sürüm (ör. {@code 1.2.0+1.21.1}),
     *         bulunamazsa {@link #UNKNOWN}
     */
    public static String resolve() {
        if (cached == null) {
            cached = friendlyVersion(FabricLoader.getInstance()
                    .getModContainer(FpsSyncMod.MOD_ID));
        }
        return cached;
    }

    /**
     * Konteynerden sürümü çözer.
     *
     * <p>FabricLoader'a bağımlı olmayan tek parça; test edilebilir olsun diye ayrıldı.
     * Mod kimliği yanlış yazılmış ya da loader henüz hazır değilse <b>uydurma sürüm
     * yazmaz</b>, {@link #UNKNOWN} döner.
     */
    static String friendlyVersion(Optional<ModContainer> container) {
        return container
                .map(ModContainer::getMetadata)
                .map(m -> m.getVersion().getFriendlyString())
                .orElse(UNKNOWN);
    }
}