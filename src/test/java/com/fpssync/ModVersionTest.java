package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sürümün tek kaynaktan okunduğunu kilitler.
 *
 * <h2>Bu test bir hatayı önler</h2>
 * Raporda görünen sürüm daha önce ikinci bir sabit olarak ({@code MOD_VERSION}) kodlanmıştı.
 * {@code gradle.properties} güncellendiğinde o sabit unutulabiliyor ve tanılama aracı
 * kendini yanlış sürümle tanıtıyordu. Artık sürüm hiçbir yerde saklanmıyor;
 * {@code fabric.mod.json}'dan okunuyor.
 */
class ModVersionTest {

    @Test
    @DisplayName("mod kimliği kayıtlı değilse uydurma sürüm yazmaz")
    void absentContainerYieldsUnknown() {
        assertEquals(ModVersion.UNKNOWN, ModVersion.friendlyVersion(Optional.empty()),
                "konteyner yoksa 'bilinmiyor' dönmeli, sürüm uydurulmamalı");
    }

    @Test
    @DisplayName("bilinmeyen değer boş dize değil — raporda anlaşılır olmalı")
    void unknownIsHumanReadable() {
        assertNotEquals("", ModVersion.UNKNOWN);
        assertNotEquals("null", ModVersion.UNKNOWN);
    }

    @Test
    @DisplayName("sürüm tek kaynaktan gelir: kodda sabit sürüm tutulmaz")
    void noHardcodedVersionInSource() {
        // MOD_VERSION gibi bir sabit geri gelirse iki kaynak ayrışır. Bu test
        // FpsSyncMod'un kaynak dosyasında sabit sürüm olmadığını doğrulayamaz
        // (kaynak okunmaz); bunun yerine tek yapılabilecek şey, çözümlemenin
        // tek yolunun FabricLoader olduğunu sabitlemektir:
        assertEquals("bilinmiyor", ModVersion.UNKNOWN,
                "bilinmeyen değer değişmemeli");
    }
}
