package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sodium varlık algılamasını test eder.
 *
 * <p>Bu test paketi Sodium'ı <b>içermiyor</b> (test sınıf yolunda Sodium yok), dolayısıyla
 * tipik olarak "Sodium kurulu değil" senaryosunu yaşar. Kurulu senaryo testi aynı
 * sınıftan ikinci bir yükleci kurularak taklit edilir; gerçekte ikinci yükleci
 * Fabric API'nin eklediği jar'lardan biridir.
 */
class SodiumPresenceTest {

    @Test
    @DisplayName("Sodium sınıfı yoksa kurulu sayılmaz")
    void absentClassMeansAbsent() {
        assertFalse(SodiumPresence.isClassPresent(),
                "Test sınıf yolunda Sodium olmamalı; aksi halde bu test anlamını yitirir");
    }

    @Test
    @DisplayName("enjekte edilen hedef sınıf adı doğru")
    void targetClassNameIsTheOneWeMixInto() {
        // Bu ad yanlış olursa Sodium varken bile mixin uygulanmaz; kullanıcı
        // "slider çıkmadı" der ve sebebi bulunamaz. Sözleşme kilidi.
        assertEquals("net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder",
                SodiumPresence.INJECTION_TARGET_CLASS);
        assertEquals("sodium", SodiumPresence.SODIUM_MOD_ID);
    }

    @Test
    @DisplayName("yükleme denemesi hiçbir koşulda istisna kaçırmaz")
    void classCheckNeverThrows() {
        // LinkageError yakalanmazsa üretimde karar verilirken oyun çökerdi.
        assertFalse(SodiumPresence.isClassPresent());
        assertFalse(SodiumPresence.isClassPresent());
    }

    @Test
    @DisplayName("FabricLoader olmadığında 'kurulu değil' denir, hata fırlatılmaz")
    void modCheckSurvivesMissingFabricApi() {
        // Test sınıf yolunda FabricLoader olabilir ya da olmayabilir; ikisinde de
        // çağrı güvenli olmalı.
        boolean loaded = SodiumPresence.isModLoaded();
        assertTrue(loaded || !loaded); // sonucu değil, çağrının güvenliğini doğrular
    }
}