package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sınır durumu değişimi tespiti ve kancanın bağlanması.
 *
 * <h2>Neden bu dosya var</h2>
 * Mutasyon denemesinde kanca bağlantısı ve tespit mantığı <b>hiç sınanmamış</b>
 * çıktı: dört mutasyon sessizce geçti. Testler yalnız kayıtçıya elle veri
 * yazıyordu — yani "tetikleyici gerçekten çalışıyor mu" sorusu hiç sorulmamıştı.
 *
 * <p>Bu tuzak sessizdir: kanca bağlanmazsa {@code GEÇERSİZ OTURUM} uyarısı hiç
 * fire etmez ve rapor kullanıcıya yalan söyler.
 *
 * <h2>Neden nesne karşılaştırması değil</h2>
 * İlk denemede kanca "değişti mi" diye referans karşılaştırması yapıyordu. Bu
 * işe yaramaz: {@code FpsSyncMod::onTargetChanged} her çağrıda <em>yeni</em> bir
 * nesne üretir, dolayısıyla karşılaştırma her zaman "değişmiş" der ve kancanın
 * bağlanıp bağlanmadığını anlamaz. Doğru sınama <b>davranışsal</b>: başlatmadan
 * sonra hedef değiştir, kayıtçının sayacının arttığını gör.
 */
class TargetChangeDetectionTest {

    private static final long B = 1_000_000_000L / 60L;

    /** Sanal saatle kurulmuş sınırlayıcı + hedef değişim günlüğü. */
    private static final class Rig {
        final FrameLimiter limiter = FrameLimiter.INSTANCE;
        final long[] clock = {1_000_000L};
        final int[] changes = {0};
        final int[][] fromTo = new int[8][];
        int n;

        Rig() {
            limiter.reset();
            limiter.resetFrameStats();
            limiter.nanoTime = () -> clock[0];
            limiter.sleeper = ns -> clock[0] += ns;
            limiter.spinHook = () -> { clock[0] += B / 2; };
            limiter.onSpin = spin -> { };
            limiter.onTargetChanged = (a, b) -> {
                if (n < fromTo.length) {
                    fromTo[n] = new int[] {a, b};
                }
                n++;
                changes[0]++;
            };
            limiter.setMonitorRefreshRate(60);
        }

        void frame() { limiter.limitFrame(); }
        void setSync(boolean on) { limiter.setEnabled(on); }
        void setManual(int fps) { limiter.setManualLimit(fps); }

        void restore() {
            limiter.useProductionSeams();
            limiter.resetFrameStats();
            FpsSyncMod.bindTargetChangeListener();
        }
    }

    @Test
    @DisplayName("ilk kare hedef değişimi sayılmaz")
    void firstFrameIsNotAChange() {
        Rig rig = new Rig();
        try {
            rig.frame();
            rig.frame();
            assertEquals(0, rig.changes[0], "oturumun ilk karesinde hedef değişmemiştir");
        } finally {
            rig.restore();
        }
    }

    @Test
    @DisplayName("hedef değişince kanca bir kez fırlar")
    void changeFiresHookOnce() {
        Rig rig = new Rig();
        try {
            rig.setSync(true);
            rig.frame();
            rig.frame();
            assertEquals(0, rig.changes[0], "önce değişim yok");

            // Sync ÖNCE kapatılmalı: limitFrame önce fpsSyncEnabled'e bakar, sonra
            // elle sınıra bakar. Sync açıkken setManual(50) hiçbir şeyi değiştirmez —
            // oyun durumunda da böyle: slider ya sync ya elle sınırdır.
            rig.setSync(false);
            rig.setManual(50);
            rig.frame();

            assertEquals(1, rig.changes[0], "geçiş bir kez sayılmalı");
            assertEquals(60, rig.fromTo[0][0], "önceki hedef panel hızı olmalı");
            assertEquals(50, rig.fromTo[0][1], "yeni hedef elle sınır olmalı");
        } finally {
            rig.restore();
        }
    }

    @Test
    @DisplayName("sınırsıza geçiş de bir değişim sayılır")
    void unlimitedTransitionIsAChange() {
        Rig rig = new Rig();
        try {
            rig.setSync(true);
            rig.frame();
            rig.frame();
            rig.setSync(false);
            rig.setManual(0);
            rig.frame();

            assertEquals(1, rig.changes[0], "sınırsıza geçiş sayılmalı");
            assertEquals(0, rig.fromTo[0][1], "sınırsız 0 olarak temsil edilir");
        } finally {
            rig.restore();
        }
    }

    @Test
    @DisplayName("arka arkaya aynı hedef sayılmaz")
    void repeatedSameTargetNotCounted() {
        Rig rig = new Rig();
        try {
            rig.setSync(true);
            for (int i = 0; i < 10; i++) {
                rig.frame();
            }
            assertEquals(0, rig.changes[0], "hedef değişmedi, sayım da olmamalı");
        } finally {
            rig.restore();
        }
    }

    @Test
    @DisplayName("kanca onInitializeClient içinde BAĞLANIR — davranışsal")
    void listenerIsWiredDuringInit() {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        FramePacingRecorder pacing = FpsSyncMod.pacing();

        // Kancayı bilerek kopar: artık durum değişimi kimseye ulaşmamalı.
        limiter.onTargetChanged = (a, b) -> { };
        limiter.reset();
        limiter.setMonitorRefreshRate(60);
        long before = pacing.stateChanges();

        // Gerçek başlatma yolu: kanca burada bağlanmalı.
        new FpsSyncMod().onInitializeClient();

        // Hedefi değiştirip bir kare çalıştır.
        limiter.reset();
        limiter.setEnabled(true);
        limiter.setMonitorRefreshRate(60);
        limiter.nanoTime = () -> 1_000_000L;
        limiter.spinHook = () -> { };
        limiter.limitFrame();
        limiter.setEnabled(false);
        limiter.setManualLimit(50);
        limiter.limitFrame();

        assertTrue(pacing.stateChanges() > before,
                "kanca bağlanmamış: hedef değişti ama kayıtçı saymadı (önce="
                        + before + ", sonra=" + pacing.stateChanges() + ")");

        limiter.useProductionSeams();
        FpsSyncMod.bindTargetChangeListener();
    }
}
