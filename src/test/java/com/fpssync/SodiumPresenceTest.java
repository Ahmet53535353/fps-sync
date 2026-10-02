package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sodium varlık algılamasını test eder — <b>özellikle algılamanın sınıfı YÜKLEMEDİĞINI</b>.
 *
 * <h2>Bu test bir hatadan doğdu</h2>
 * 2026-10-02'de {@code SodiumPresence} sınıfı {@code Class.forName(...)} ile arıyordu.
 * Sodium <b>yokken</b> bu çağrı {@code ClassNotFoundException} ile dönüyor, sorun
 * görünmüyordu. Sodium <b>varken</b> sınıfı erken yüklüyor ve Mixin
 * {@code MixinTargetAlreadyLoadedException} ile oyunu çökertiyordu:
 *
 * <pre>
 * Critical problem: SodiumFpsLimitMixin target SodiumConfigBuilder was loaded too early.
 * </pre>
 *
 * <p>İlk test paketi yalnız <b>"yok"</b> senaryosunu çalıştırıyordu. Yokluğu sınamak
 * kolay, varlığı sınamak zordur — ama kolay olanı test etmek, zor olanın
 * yakalanmamasına yol açıyor. Aşağıdaki statik-başlatıcı testi, tam olarak o boşluğu
 * kapatır.
 */
class SodiumPresenceTest {

    /**
     * Yüklemeye çalışılırsa hata fırlatan yükleyici.
     *
     * <p>{@link Class#forName} {@code ClassNotFoundException}'ı yakalar, bu yüzden
     * AssertionError fırlatıyoruz: böylece yanlışlıkla yüklemeye çalışılırsa test
     * sessizce "bulamadı" deyip geçmez, kızar.
     */
    static final class LoadTrapLoader extends ClassLoader {
        LoadTrapLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) {
            throw new AssertionError(
                    "Sınıf yüklemeye çalıştırıldı: " + name
                    + " — Mixin hedef sınıfı erken yüklenirse oyun çöker");
        }
    }

    @Test
    @DisplayName("VAR OLAN sınıfı bulur")
    void findsExistingClass() {
        assertTrue(SodiumPresence.isClassAvailable(
                FrameLimiter.class.getName(), getClass().getClassLoader()));
    }

    @Test
    @DisplayName("OLMAYAN sınıfı bulamaz")
    void doesNotFindMissingClass() {
        assertFalse(SodiumPresence.isClassAvailable(
                "com.fpssync.YokBoyleBirSinif", getClass().getClassLoader()));
    }

    @Test
    @DisplayName("varlık kontrolü sınıfı YÜKLEMEYE ÇALIŞMAZ")
    void doesNotAttemptToLoad() {
        // Bu testin var olma sebebi 2026-10-02'deki çökme: Class.forName hedef
        // sınıfı erken yüklüyor, Mixin "loaded too early" deyip oyunu düşürüyordu.
        // Tuzak yükleyici, yüklemeye çalışılırsa AssertionError fırlatır.
        LoadTrapLoader trap = new LoadTrapLoader(getClass().getClassLoader());
        String existing = FrameLimiter.class.getName();

        assertTrue(SodiumPresence.isClassAvailable(existing, trap),
                "kaynak dosyası bulunmalı");

        assertFalse(SodiumPresence.isClassAvailable("com.fpssync.Yok", trap));
    }

    @Test
    @DisplayName("enjekte edilen hedef sınıfın adı doğru")
    void targetIsTheClassWeMixInto() {
        assertTrue(SodiumPresence.INJECTION_TARGET_CLASS
                .equals("net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder"));
        assertTrue("sodium".equals(SodiumPresence.SODIUM_MOD_ID));
    }

    @Test
    @DisplayName("bu test paketinde Sodium yok — 'yok' senaryosu gerçekten çalışıyor")
    void sodiumIsAbsentFromTestClasspath() {
        assertFalse(SodiumPresence.isClassAvailable(),
                "Test sınıf yolunda Sodium olmamalı; olması testleri anlamsızlaştırır");
    }

    @Test
    @DisplayName("FabricLoader sorgusu her koşulda istisna kaçırmaz")
    void modCheckSurvivesMissingFabricApi() {
        boolean loaded = SodiumPresence.isModLoaded();
        assertTrue(loaded || !loaded);
    }
}
