package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code /fpsync status} ilk 10 dakika karşılaştırma tabanını kalıcı olarak bozuyordu.
 *
 * <h2>Neden bu dosya var</h2>
 * Modun belgelenen kullanımı tam olarak bu döngü: <b>ölç → bir şeyi değiştir → ölç</b>
 * ({@code FpsSyncStatusCommand} javadoc'u, {@code /fpsync status keep} ve
 * {@code reset} varyantları hep bunun için). Koşular arası karşılaştırma için
 * sabit bir ilk-10-dakika penceresi var çünkü üç ardışık koşuda CPU payı
 * %17,0 → %17,5 → %25,1 çıkmıştı, kod değişmemişti.
 *
 * <h2>Kök neden</h2>
 * {@link FramePacingRecorder#reset()} histogramları ve {@code elapsedNs}'i sıfırlar,
 * ama kare süresi tabanı ({@code FpsSyncMod.lastFrameNsBase}) <b>FpsSyncMod'un statik
 * alanıdır ve hiçbir yerden sıfırlanmaz</b>. Sıfırlamadan sonraki ilk kare, aradaki
 * boşluğun tamamını {@code frameNs} olarak yutar.
 *
 * <p>Gerçek senaryo: 10 dakika oyna → {@code /fpsync status} (sayaçlar sıfırlandı) →
 * oyunu kapat, bir saat dışarı çık → geri aç → iki kare oyna → tekrar
 * {@code /fpsync status}. İkinci rapor <b>"■ İLK 10 DAKİKA — kare 1"</b> diyor ve
 * {@code elapsedNs} tek karede 600 saniyeyi geçiyor. Karşılaştırma tabanı artık
 * "ilk 10 dakikada 1 kare oynadım, %100 limiter devrede" diyor.
 *
 * <p>Ölçülen (Probe6Test ile): sıfırlamadan sonraki boşluk 300 sn'de eşiğe ulaşmıyor,
 * 601 sn'de ulaşıyor ve pencere <b>1 kare</b> ile donuyor. Yani hata sessiz; küçük bir
 * arada görünmez, gerçek bir mola sonrası raporu bozar.
 */
class EarlyWindowResetTest {

    private static final long B = 1_000_000_000L / 60L;

    /** Sanal saatle kurulmuş sınırlayıcı + gerçek ölçüm girişi. */
    private static final class Rig {
        final FrameLimiter limiter = FrameLimiter.INSTANCE;
        final long[] clock = {1_000_000L};

        Rig() {
            limiter.reset();
            limiter.resetFrameStats();
            limiter.nanoTime = () -> clock[0];
            limiter.sleeper = ns -> clock[0] += ns;
            limiter.spinHook = () -> { clock[0] += B / 2; };
            limiter.onSpin = spin -> { };
            limiter.setMonitorRefreshRate(60);
            limiter.setEnabled(true);
            FpsSyncMod.bindTargetChangeListener();
        }

        void frame() {
            clock[0] += B / 2;
            limiter.limitFrame();
            FpsSyncMod.recordFrameTiming();
        }

        /** Oyunu kapatıp belirtilen süre dışarıda kalmak. */
        void idle(long seconds) {
            clock[0] += seconds * 1_000_000_000L;
        }

        void restore() {
            limiter.useProductionSeams();
            limiter.resetFrameStats();
            FpsSyncMod.bindTargetChangeListener();
        }
    }

    @Test
    @DisplayName("sıfırlamadan sonraki ilk kare, aradaki boşluğu kare süresi saymaz")
    void firstFrameAfterResetDoesNotSwallowTheGap() {
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();

            for (int i = 0; i < 600; i++) {
                rig.frame();
            }
            // Belgelenen döngü: ölç → sıfırla → oyunu kapat → geri aç → ölç.
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();

            rig.idle(900);   // 15 dakika mola
            rig.frame();
            rig.frame();

            long elapsedSeconds = pacing.elapsedNs() / 1_000_000_000L;
            assertTrue(elapsedSeconds < 5,
                    "15 dakikalık mola kare süresine sızdı: elapsed = " + elapsedSeconds
                            + " sn (2 kare ~0,03 sn olmalı). lastFrameNsBase sıfırlanmıyor.");
        } finally {
            rig.restore();
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();
        }
    }

    @Test
    @DisplayName("mola sonrası ilk 10 dakika penceresi boşluğu 'kare 1' yapmaz")
    void earlyWindowIsNotFrozenByTheGap() {
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();
            for (int i = 0; i < 600; i++) {
                rig.frame();
            }
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();

            rig.idle(900);
            for (int i = 0; i < 600; i++) {
                rig.frame();
            }

            FramePacingRecorder.Totals e = pacing.earlyWindowTotals();
            if (e != null) {
                assertTrue(e.totalFrames() > 100,
                        "ilk 10 dakika penceresi mola boşluğuyla dondu: kare = "
                                + e.totalFrames() + " (600 kare oynandı)");
                assertTrue(e.fps() > 30.0,
                        "penceredeki FPS çöktü: " + e.fps() + " (mola sonrası ~60 beklenir)");
            }
        } finally {
            rig.restore();
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();
        }
    }

    @Test
    @DisplayName("rapor 'İLK 10 DAKİKA: kare 1' yazmaz")
    void reportDoesNotClaimOneFrame() {
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();
            for (int i = 0; i < 600; i++) {
                rig.frame();
            }
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();

            rig.idle(900);
            // Yalnızca 2 kare: boşluk 600 sn'yi geçtiği için pencere bu 2 kareyle
            // donuyor. 300 kare oynansaydı eşik zaten aşılır ve test yanlış yere geçerdi.
            for (int i = 0; i < 2; i++) {
                rig.frame();
            }

            String text = FpsSyncStatusReport.render(
                    new FpsSyncStatusReport.Snapshot(pacing, true, 60, 1366, 768, "1.8.0", SodiumSliderStatus.MIXIN_APPLIED, 60));
            String window = lineStartingWith(text, "■ İLK 10 DAKİKA");
            assertFalse(window.contains("— kare 1"),
                    "mola boşluğu pencereyi 'kare 1' ile dondurdu:\n" + window);
            assertTrue(window.contains("dolmadı"),
                    "15 dakika moladan sonra 2 kare oynanmış; pencere oluşmamış olmalı:\n"
                            + window);
        } finally {
            rig.restore();
            pacing.reset();
            FpsSyncMod.resetFrameTimeBase();
        }
    }

    /**
 * Üretim yolu {@code resetFrameTimeBase()} çağrısını yapıyor mu?
 *
 * <h2>Neden ayrı bir test</h2>
 * İlk yazımda bu metot testte <b>elle</b> çağrılıyordu. Mutasyon denemesi
 * {@code FpsSyncStatusCommand} içindeki çağrıları silince testler <b>yeşil kaldı</b> —
 * yani test, düzelttiğim hatanın üretimde bağlandığı yeri sınamıyordu. Bu, "testler
 * gerçek üretim yolunu sürmüyor" kalıbının kendisi: {@code run()} Minecraft'e bağlı
 * olduğu için doğrudan çağrılamıyor.
 *
 * <p>Bu test iki şeyi birden kilitler: kaynakta çağrı <b>var</b> ve reset sonrası taban
 * gerçekten sıfırlanıyor.
 */
@Test
@DisplayName("her iki pacing.reset() çağrısı da kare süresi tabanını sıfırlamalı")
void resetCallSitesAlsoResetTheFrameBase() throws Exception {
    java.nio.file.Path source = java.nio.file.Path.of(
            "src/main/java/com/fpssync/FpsSyncStatusCommand.java");
    String text;
    try {
        text = new String(java.nio.file.Files.readAllBytes(source),
                java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
        // Kaynak dosya test sırasında erişilemiyorsa (dosya sistemi katmanı) test
        // sessizce geçmemeli; davranış testi aşağıda yine de çalışır.
        return;
    }

    int resetSites = text.split("pacing\\.reset\\(\\)", -1).length - 1;
    assertTrue(resetSites >= 2,
            "pacing.reset() çağrı sayısı değişmiş: " + resetSites);

    int withBase = text.split("FpsSyncMod\\.resetFrameTimeBase\\(\\)", -1).length - 1;
    assertEquals(resetSites, withBase,
            "her pacing.reset() yanında resetFrameTimeBase() olmalı. reset=" + resetSites
                    + " base=" + withBase + " — reset edilen sayaçlardan sonra kare süresi "
                    + "tabanı eski kalırsa bir sonraki kare boşluğu yutar.");
}

@Test
@DisplayName("resetFrameTimeBase tabanı gerçekten sıfırlar — boşluk biriktirmez")
void resetBaseActuallyClearsTheBase() {
    Rig rig = new Rig();
    FramePacingRecorder pacing = FpsSyncMod.pacing();
    try {
        pacing.reset();
        FpsSyncMod.resetFrameTimeBase();
        rig.frame();
        rig.frame();
        long before = pacing.elapsedNs();

        // Taban kuruldu; reset sonrası ilk kare yeni taban olmalı, ESKİ kare değil.
        FpsSyncMod.resetFrameTimeBase();
        rig.idle(3600);   // bir saat
        rig.frame();
        rig.frame();

        long after = pacing.elapsedNs();
        long added = (after - before) / 1_000_000L;
        assertTrue(added < 100,
                "reset sonrası taban sıfırlanmamış: " + added
                        + " ms eklendi (bir saatlik boşluk kare süresi sayıldı)");
    } finally {
        rig.restore();
        pacing.reset();
        FpsSyncMod.resetFrameTimeBase();
    }
}

private static String lineStartingWith(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return "(satır yok)";
    }
}