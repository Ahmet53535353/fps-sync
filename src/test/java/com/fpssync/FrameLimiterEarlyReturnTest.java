package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Park'ın <b>erken</b> dönüşünün ölçüme taşındığını kilitler.
 *
 * <h2>Neden ayrı bir test</h2>
 * {@link OvershootMeasurementTest} kayıtçıya doğrudan negatif aşım veriyor; yalnız
 * kayıtçının doğru davrandığını gösterir. Asıl soru üreticidedir:
 * {@link FrameLimiter} erken dönüşü sıfıra yassılıyordu, bu yüzden kayıtçıya hiçbir
 * zaman negatif değer ulaşmıyordu ve "erken dönüş" ölçümü oynanışta hep sıfır çıkacaktı.
 *
 * <p>Bir kare başına 3.85 ms spin görülmesinin nedeni büyük olasılıkla budur: park
 * istenenden erken dönüp kalanı spin ile yakıyor. Bu değer görünmediği için sebep
 * bilinmiyordu.
 */
class FrameLimiterEarlyReturnTest {

    private static final long EARLY_NS = 3_000_000L;
    private static final long LATE_NS = 90_000L;

    /**
     * Sanal saatle sınırlayıcıyı kurar ve kare başına tam bütçenin bir kısmı kadar
     * ilerledikten sonra {@code limitFrame} çalıştırır.
     *
     * @param parkAdjustmentNs park'ın istenenden ne kadar saptığı (pozitif = geç)
     */
    private static FrameLimiter runFrame(long parkAdjustmentNs) {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        final long startNs = 1_000_000L;
        final long budgetNs = 1_000_000_000L / 60L;
        final long deadlineNs = startNs + budgetNs;
        long[] clock = {startNs};

        limiter.reset();
        // Park aşımı artık kareler boyunca TOPLANIR (birden çok park çağrısı olabilir).
        // Üretimde her kare sonunda recordFrameTiming() sıfırlar; testte de sıfırlamak
        // gerekir, yoksa önceki testten kalan değer devralınır.
        limiter.resetFrameStats();
        limiter.nanoTime = () -> clock[0];
        limiter.sleeper = ns -> clock[0] += ns + parkAdjustmentNs;
        // Kanca sanal saati deadline'a taşır: aksi hâlde spin döngüsü hiç çıkmaz.
        limiter.spinHook = () -> { clock[0] = deadlineNs; };
        limiter.onSpin = spin -> { };
        limiter.setEnabled(true);
        limiter.setMonitorRefreshRate(60);

        limiter.limitFrame();                     // başlatma karesi
        clock[0] += budgetNs / 2;                // bütçenin yarısı kadar ilerle
        limiter.limitFrame();                     // sınırlayıcı gerçekten bekler
        return limiter;
    }

    @Test
    @DisplayName("park erken dönerse aşım negatif olarak taşınır")
    void earlyReturnIsReportedAsNegative() {
          FrameLimiter limiter = runFrame(-EARLY_NS);

          // Artık birden çok park çağrısı yapılabilir: kalan süre kapanana kadar tekrar
          // denenir. Önemli olan aşımın negatif olması ve her çağrı için birikmesi.
          assertTrue(limiter.parkCallsLastFrame >= 1, "park çağrısı yapılmalı");
          assertEquals(-EARLY_NS * limiter.parkCallsLastFrame,
                  limiter.parkOvershootNsLastFrame,
                  "her park çağrısının erken dönüşü toplanmalı");
      }

          @Test
    @DisplayName("park geç dönerse aşım pozitif kalır")
    void lateReturnStaysPositive() {
        FrameLimiter limiter = runFrame(LATE_NS);

        assertEquals(LATE_NS, limiter.parkOvershootNsLastFrame,
                "geç dönüş pozitif olmalı");
    }

    @Test
    @DisplayName("ölçüm zinciri erken dönüşü kayıtçıya ulaştırır")
    void earlyReturnReachesTheRecorder() {
          FrameLimiter limiter = runFrame(-EARLY_NS);
          FramePacingRecorder recorder = new FramePacingRecorder();

          recorder.recordFrame(16_666_667L, 16_666_667L, true,
                  limiter.parkCallsLastFrame, limiter.parkRequestedNsLastFrame,
                  limiter.parkOvershootNsLastFrame, limiter.spinNsLastFrame);

          assertEquals(1, recorder.activeParkEarlyCalls(),
                  "kare başına bir erken dönüş kaydedilir");
          assertEquals(EARLY_NS * limiter.parkCallsLastFrame,
                  recorder.activeParkEarlyNsTotal(),
                  "birden çok park çağrısının erken dönüşü toplanmalı");
      }

          @Test
    @DisplayName("sıfırlama erken dönüş sayacını da temizler")
    void resetClearsEarlyReturnCounters() {
        FrameLimiter limiter = runFrame(-EARLY_NS);
        FramePacingRecorder recorder = new FramePacingRecorder();
        recorder.recordFrame(16_666_667L, 16_666_667L, true,
                limiter.parkCallsLastFrame, limiter.parkRequestedNsLastFrame,
                limiter.parkOvershootNsLastFrame, limiter.spinNsLastFrame);

        recorder.reset();

        assertEquals(0, recorder.activeParkEarlyCalls(), "sıfırlamadan sonra sıfır olmalı");
        assertEquals(0, recorder.activeParkEarlyNsTotal());
    }

    @Test
    @DisplayName("kare istatistikleri sıfırlanınca aşım da sıfırlanır")
    void frameStatsResetClearsOvershoot() {
        FrameLimiter limiter = runFrame(-EARLY_NS);
        assertTrue(limiter.parkOvershootNsLastFrame != 0, "önce aşım kaydedilmeli");

        limiter.resetFrameStats();

        assertEquals(0, limiter.parkOvershootNsLastFrame);
        assertEquals(0, limiter.parkCallsLastFrame);
    }
}