package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Başarısız park çağrısından sonra bir kez daha" kuralının <b>ölçümü</b>.
 *
 * <h2>Karar buradan çıkıyor</h2>
 * Sınırlayıcı, fayda koruması devreye girdiğinde artık tam bir kez daha park dener.
 * Bunun işe yarayıp yaramadığı tahminle değil, ölçümle anlaşılır:
 *
 * <pre>
 *   faydalı tekrar oranı = retryAfterFailSleptCalls / retryAfterFailCalls
 * </pre>
 *
 * <ul>
 *   <li><b>%3'ün üstü:</b> kural kalsın demektir. 45 µs gider, karşılığında bir
 *       sonraki çağrı 6,7 ms uyuyor.</li>
 *   <li><b>%1'in altı:</b> kural geri alınır; park gerçekten işe yaramıyor demektir
 *       ve spin kabul edilir.</li>
 * </ul>
 *
 * <p>Uygulama bu sayıları raporun altına yazar; karar koşudan sonra verilir.
 */
class ParkRetryDecisionTest {

    private static final long BUDGET_NS = 1_000_000_000L / 60L;

    /** Sanal saatle, çağrı çağrı farklı oranları uyuyan sınırlayıcı. */
    private static FrameLimiter runSequence(double... ratios) {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        final long startNs = 1_000_000L;
        final long deadlineNs = startNs + BUDGET_NS;
        long[] clock = {startNs};
        int[] call = {0};

        limiter.reset();
        limiter.resetFrameStats();
        limiter.nanoTime = () -> clock[0];
        limiter.sleeper = ns -> {
            int i = call[0]++;
            double ratio = ratios[Math.min(i, ratios.length - 1)];
            clock[0] += Math.max(1L, (long) (ns * ratio));
        };
        limiter.spinHook = () -> { clock[0] = deadlineNs; };
        limiter.onSpin = spin -> { };
        limiter.setEnabled(true);
        limiter.setMonitorRefreshRate(60);

        limiter.limitFrame();
        clock[0] += BUDGET_NS / 2;
        limiter.limitFrame();
        return limiter;
    }

    /** Sayacı kayıtçıya geçiren tek satır — FpsSyncMod'daki gerçek yolla aynı. */
    private static FramePacingRecorder record(FrameLimiter limiter) {
        FramePacingRecorder recorder = new FramePacingRecorder();
        recorder.recordFrame(BUDGET_NS, BUDGET_NS, true,
                limiter.parkCallsLastFrame, limiter.parkRequestedNsLastFrame,
                limiter.parkOvershootNsLastFrame, limiter.spinNsLastFrame,
                limiter.parkElapsedNsLastFrame,
                limiter.interruptFlagSetFrames, limiter.interruptsCaught,
                limiter.retryAfterFailCalls, limiter.retryAfterFailSleptCalls,
                limiter.retryAfterFailSleptNs);
        return recorder;
    }

    private static FpsSyncStatusReport.Snapshot snapshot(FramePacingRecorder r) {
        return new FpsSyncStatusReport.Snapshot(r, true, 60, 1920, 1080,
                "1.3.0+1.21.1", true, 0);
    }

    @Test
    @DisplayName("başarısız çağrı sayacı kayıtçıya ulaşır")
    void failedRetriesReachRecorder() {
        FrameLimiter l = runSequence(0.0);
        FramePacingRecorder t = record(l);

        assertEquals(1, t.activeParkRetryAfterFailCalls(),
                "guard bir kez devretti ve kayıtçıya yazılmalı");
        assertEquals(0, t.activeParkRetryAfterFailSleptCalls(),
                "tekrar da uyumadıysa faydalı sayılmamalı");
    }

    @Test
    @DisplayName("faydalı tekrar ve kazanılan süre kayıtçıya ulaşır")
    void usefulRetriesReachRecorder() {
        FrameLimiter l = runSequence(0.0, 1.0);
        FramePacingRecorder t = record(l);

        assertEquals(1, t.activeParkRetryAfterFailCalls());
        assertEquals(1, t.activeParkRetryAfterFailSleptCalls());
        assertTrue(t.activeParkRetryAfterFailSleptNs() > 1_000_000L,
                "kazanılan uyku kaydedilmeli: " + t.activeParkRetryAfterFailSleptNs());
    }

    @Test
    @DisplayName("park hiç denenmeyen pencerede sayaçlar sıfır kalır")
    void countersStayZeroWithoutRetry() {
        FrameLimiter l = runSequence(1.0);
        FramePacingRecorder t = record(l);

        assertEquals(0, t.activeParkRetryAfterFailCalls(),
                "sağlıklı park'ta tekrar denenmemeli");
        assertEquals(0L, t.activeParkRetryAfterFailSleptNs());
    }

    @Test
    @DisplayName("sıfırlama yeni sayaçları da temizler")
    void resetClearsTotals() {
        FramePacingRecorder recorder = new FramePacingRecorder();
        recorder.recordFrame(BUDGET_NS, BUDGET_NS, true, 2, 8_000_000L,
                -8_000_000L, 8_000_000L, 0L, 0L, 0L, 1, 1, 6_700_000L);

        recorder.reset();

        FramePacingRecorder.Totals t = recorder.totals();
        assertEquals(0, t.parkRetryAfterFailCalls(), "sıfırlama yeni sayaçları da temizlemeli");
        assertEquals(0, t.parkRetryAfterFailSleptCalls());
        assertEquals(0L, t.parkRetryAfterFailSleptNs());
    }

    @Test
    @DisplayName("rapor karar oranını yazar")
    void reportPrintsDecisionRatio() {
        FrameLimiter l = runSequence(0.0, 1.0);
        String text = FpsSyncStatusReport.render(snapshot(record(l)));

        assertTrue(text.contains("başarısız park tekrarı"),
                "rapor tekrar satırını yazmalı, alınan:\n" + text);
        assertTrue(text.contains("1/1 faydalı"),
                "faydalı/deneme oranı yazılmalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("rapor gideri aşıp aşmadığını açıkça söyler")
    void reportStatesWhetherGainBeatsCost() {
        FrameLimiter l = runSequence(0.0, 1.0);
        String text = FpsSyncStatusReport.render(snapshot(record(l)));

        // 8 ms uyuma, 45 µs gideri fazlasıyla aşıyor.
        assertTrue(text.contains("45 µs gideri aşıyor"),
                "kazanç gideri aşıyorsa bu yazılmalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("rapor faydalı/deneme sırasını karıştırmaz")
    void reportKeepsRatioOrder() {
        // 100 denemenin 30'u tutmuş olsun: 30/100 yazılmalı, 100/30 değil.
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 100; i++) {
            boolean useful = i < 30;
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 2, 8_400_000L, -8_400_000L,
                    useful ? 1_700_000L : 8_400_000L, useful ? 6_700_000L : 0L,
                    0L, 0L, 1, useful ? 1 : 0, useful ? 6_700_000L : 0L);
        }

        String text = FpsSyncStatusReport.render(snapshot(r));

        assertTrue(text.contains("30/100 faydalı"),
                "faydalı/deneme sırası korunmalı, alınan:\n" + text);
        assertTrue(text.contains("30.0%"),
                "oran da doğru yazılmalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("tekrar denenmediyse rapor bunu açıkça söyler")
    void reportSaysWhenNoRetryHappened() {
        FrameLimiter l = runSequence(1.0);
        String text = FpsSyncStatusReport.render(snapshot(record(l)));

        assertTrue(text.contains("tekrar        yok"),
                "tekrar yokken bu yazılmalı, alınan:\n" + text);
    }
}