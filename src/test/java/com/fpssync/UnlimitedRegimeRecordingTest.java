package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sınırsız rejimde (taban koşusu) kare kaydı hiç oluşmuyordu.
 *
 * <h2>Neden bu dosya var</h2>
 * Taban koşusunda FPS sınırı {@code >= 1010} (sınırsız). Beklenen: "sınırlama olmadan
 * oyun ne kadar iyi" sorusunun ölçülebilir cevabı. Gerçekleşen:
 * {@code /fpsync status} "Henüz kare kaydedilmedi" dedi ve rapor başlığı boş döndü.
 *
 * <h2>Kök neden</h2>
 * {@link FrameLimiter#limitFrame()} sınırsızda bütçe hesaplamadan döner, yani
 * {@code frameBudgetNsLastFrame} hiç güncellenmez. Ölçüm girişi
 * ({@link FpsSyncMod#recordFrameTiming()}) ise {@code if (budget > 0 && now > 0)} diyordu.
 * Sınırsızda bütçe 0 (ya da daha kötü, eski bir bütçe) olduğu için <b>kayıt hiç yazılmıyordu</b>.
 *
 * <p>1.6.0 boşta rejim erişimcilerini ekledi ({@code idleFps1Low()},
 * {@code idleSwapLateEntries()} …) ve "veri zaten toplanıyordu, yalnızca yazılmıyordu"
 * diye belgeledi. <b>Yanlış bir varsayımdı:</b> veri toplanmıyordu, çünkü kayıt hiç
 * oluşmuyordu. {@link HonestSessionTest} bu yüzden yeşildi — o test
 * {@code FramePacingRecorder}'a <em>doğrudan</em> {@code recordFrame} çağırıyor,
 * yani üretim yolunun kapısını atlıyordu. Buradaki testler gerçek yolu
 * ({@code limitFrame} + {@code recordFrameTiming}) sınar.
 *
 * <h2>Kural</h2>
 * Bir erişimcinin "veri var" demesi, verinin o erişimciye ulaştığını göstermez.
 * Ölçüm hattının <em>giriş kapısı</em> da teste tabidir.
 */
class UnlimitedRegimeRecordingTest {

    private static final long B = 1_000_000_000L / 60L;

    /** Sanal saatle kurulmuş sınırlayıcı; gerçek ölçüm girişini kullanır. */
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
            // Üretimdeki kancayı kur: hedef değişimi kayda geçsin. Bu satır olmadan
            // "karışık oturum" sayacı hiç artmaz ve testler yanlış yere geçer.
            FpsSyncMod.bindTargetChangeListener();
        }

        /** Gerçek üretim karesi: sınırlayıcı + ölçüm girişi. */
        void frame(long frameNs) {
            clock[0] += frameNs;
            limiter.limitFrame();
            FpsSyncMod.recordFrameTiming();
        }

        void setUnlimited() {
            limiter.setEnabled(false);
            limiter.setManualLimit(FpsSyncOption.UNLIMITED);
        }

        void setSync() {
            limiter.setEnabled(true);
        }

        void restore() {
            limiter.useProductionSeams();
            limiter.resetFrameStats();
            FpsSyncMod.bindTargetChangeListener();
        }
    }

    @Test
    @DisplayName("SINIRSIZ rejimde de kare kaydedilir — taban koşusu ölçülemezse karar verilemez")
    void unlimitedRegimeRecordsFrames() {
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            rig.setUnlimited();

            // 40 ms'lik kareler: sınırsızda oyun 25 FPS civarı koşuyor demektir.
            for (int i = 0; i < 200; i++) {
                rig.frame(40_000_000L);
            }

            assertTrue(pacing.totals().totalFrames() > 0,
                    "sınırsız rejimde hiç kare kaydedilmedi — taban koşusu ölçülemiyor. "
                            + "Toplam: " + pacing.totals().totalFrames()
                            + " (budget=" + FrameLimiter.INSTANCE.frameBudgetNsLastFrame + ")");
        } finally {
            rig.restore();
            pacing.reset();
        }
    }

    @Test
    @DisplayName("sınırsız kareler BOŞTA rejime yazılır, bekleyen rejime değil")
    void unlimitedFramesLandInIdleRegime() {
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            rig.setUnlimited();
            // 201 kare: ilk kare süresi tabanı kurar, kayıt 200 olur.
            for (int i = 0; i < 201; i++) {
                rig.frame(40_000_000L);
            }

            assertEquals(0, pacing.totals().waitingFrames(),
                    "sınırlayıcı hiç çalışmadı, bekleyen kare olmamalı");
            assertEquals(200, pacing.totals().idleFrames(),
                    "sınırsız kareler boşta rejime yazılmalı");
        } finally {
            rig.restore();
            pacing.reset();
        }
    }

    @Test
    @DisplayName("sınırsız rejimde 1% low hesaplanır — rapor tek satırla dönmez")
    void unlimitedRegimeReportsFpsLow() {
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            rig.setUnlimited();
            for (int i = 0; i < 40_000; i++) {
                rig.frame(40_000_000L);
            }

            assertTrue(pacing.idleFps1Low() > 0.0,
                    "boşta rejim 1% low hesaplanmalı, alınan: " + pacing.idleFps1Low());
            assertTrue(pacing.idleFrameTimeCount() > 0,
                    "boşta rejim kare süresi histogramı dolmalı");
        } finally {
            rig.restore();
            pacing.reset();
        }
    }

    @Test
    @DisplayName("sınırsızdan bekleme yapılana geçiş iki rejimi ayrı tutar")
    void switchingToSyncSplitsRegimes() {
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            rig.setUnlimited();
            for (int i = 0; i < 100; i++) {
                rig.frame(40_000_000L);
            }
            // Şimdi FPS Sync'e geç: hedef 60 Hz, kareler 5 ms — sınırlayıcı bekler.
            rig.setSync();
            for (int i = 0; i < 100; i++) {
                rig.frame(5_000_000L);
            }

            assertTrue(pacing.totals().idleFrames() > 0, "önceki bölüm boşta olmalı");
            assertTrue(pacing.totals().waitingFrames() > 0, "sonraki bölüm bekleyen olmalı");
            assertTrue(pacing.totals().stateChanges() > 0,
                    "rejim değişimi sayılmalı — karışık oturum uyarısı bunu okuyor");
        } finally {
            rig.restore();
            pacing.reset();
        }
    }

    @Test
    @DisplayName("hiç bekleyen kare yokken de değişim sayılır — sayaç yalnız aktifi okuyordu")
    void stateChangeCountedWithoutAnyWaitingFrame() {
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            // Sınırsız başlar: hiç bekleyen kare olmayacak.
            rig.setUnlimited();
            for (int i = 0; i < 100; i++) {
                rig.frame(40_000_000L);
            }
            rig.setSync();
            // Yalnızca bir kare: sınırlayıcı bir kez çalışsın.
            rig.frame(5_000_000L);

            assertEquals(0, pacing.totals().waitingFrames(),
                    "ön koşul: bekleyen kare henüz yok");
            assertTrue(pacing.stateChanges() > 0,
                    "bekleyen kare olmasa da hedef değişimi sayılmalı — "
                            + "sayaç yalnız aktif rejimi okuyordu ve sıfır kalıyordu");
        } finally {
            rig.restore();
            pacing.reset();
        }
    }

    @Test
    @DisplayName("düşeltme olmadan bu test kırmızıdır: kapı budget'a bakıyor")
    void gateMustNotDependOnBudget() {
        // Düşeltmenin kendisine dair koruma: bütçe sıfırken de kayıt yazılmalı.
        Rig rig = new Rig();
        FramePacingRecorder pacing = FpsSyncMod.pacing();
        try {
            pacing.reset();
            rig.setUnlimited();
            rig.frame(40_000_000L);
            rig.frame(40_000_000L);

            assertEquals(0L, FrameLimiter.INSTANCE.frameBudgetNsLastFrame,
                    "ön koşul: sınırsızda bütçe sıfır kalmalı (aksi halde test "
                            + "yanlış yere geçer)");
            assertTrue(pacing.totals().totalFrames() > 0,
                    "bütçe 0 olmasına rağmen kayıt yazılmalı");
        } finally {
            rig.restore();
            pacing.reset();
        }
    }
}