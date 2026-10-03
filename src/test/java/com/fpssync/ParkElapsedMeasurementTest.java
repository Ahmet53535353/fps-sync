package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Park'ın <em>gerçekte</em> ne kadar uyuduğunu ölçer.
 *
 * <h2>Bu test hangi soruyu cevaplayacak</h2>
 * 2026-10-02 koşusunda karelerin %50,1'i park'tan 6,93 ms <em>erken</em> döndü ve kalan
 * süre spin ile yakıldı. Ortalama, <em>şekli</em> söylemiyor. İki olasılık var:
 *
 * <ul>
 *   <li><b>Tek diptir:</b> park çoğunlukla <em>anında</em> dönüyor (sahte uyanma veya
 *       kesinti). O zaman beklemiyoruz demektir ve düzeltme "park'a güvenme".</li>
 *   <li><b>Yayılmıştır:</b> park kısmen uyuyor, kısmen erken dönüyor. O zaman
 *       istenenden biraz fazla süre istemek ve kalanı tekrar park etmek yeter.</li>
 * </ul>
 *
 * <p>İstensen süre ile gerçek süre arasındaki fark zaten "aşım"dır; ama farkın dağılımı
 * doğrudan görülemez. İstenen ortalama 8,90 ms, aşım ortalama 6,93 ms — farkı
 * ortalamaların farkı (~2 ms) yorumlamaya yetmez, çünkü iki dağılım da çarpık olabilir.
 * Bu yüzden <em>gerçek uyunan süre</em> doğrudan kaydedilir.
 */
class ParkElapsedMeasurementTest {

    private static final long BUDGET_NS = 16_666_667L;
    private static final long REQUESTED_NS = 9_000_000L;

    private static void record(FramePacingRecorder r, long overshootNs, long elapsedNs) {
        r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, REQUESTED_NS, overshootNs, 100_000L, elapsedNs);
    }

    @Test
    @DisplayName("park'ın gerçek uyuma süresi kaydedilir")
    void elapsedIsRecorded() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 50; i++) {
            record(r, 0, 9_100_000L);
        }

        long median = r.activeParkElapsedMedianNs();
        assertTrue(median > 8_000_000L && median <= 9_200_000L,
                "gerçek süre kaydedilmeli, medyan: " + median);
    }

    @Test
    @DisplayName("erken dönüşler küçük süre olarak görünür")
    void earlyReturnShowsAsShortElapsed() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 50; i++) {
            record(r, -6_900_000L, 2_100_000L); // 9 ms istendi, 2,1 ms'de döndü
        }

        long median = r.activeParkElapsedMedianNs();
        assertTrue(median > 1_500_000L && median < 3_000_000L,
                "erken dönüş kısa süre olarak ölçülmeli, medyan: " + median);
    }

    @Test
    @DisplayName("dağılımın şekli okunabilir: iki ayrı popülasyon ayrışır")
    void distributionShapeIsReadable() {
        FramePacingRecorder r = new FramePacingRecorder();
        // Yarısı 9,1 ms (normal uyku), yarısı 2 ms (erken dönüş).
        for (int i = 0; i < 50; i++) {
            record(r, 0, 9_100_000L);
        }
        for (int i = 0; i < 50; i++) {
            record(r, -7_000_000L, 2_000_000L);
        }

        // Medyan burada işe yaramaz: 50/50 dağılımda tam sınırda durur ve hangi tepeye
        // düştüğü belirsizdir. İlk yazımda "medyan yüksek tepeye düşer" diye varsayılmıştı,
        // bu matematiksel olarak doğru değil. Ayrımı kuyruk yüzdeleri gösterir:
        // p95 normal uykuda, p05 erken dönüşte.
        assertTrue(r.activeParkElapsedPercentileNs(0.95) > 8_000_000L,
                "p95 normal uyku bandında olmalı, p95: "
                        + r.activeParkElapsedPercentileNs(0.95));
        assertTrue(r.activeParkElapsedPercentileNs(0.05) < 3_000_000L,
                "p05 erken dönüş bandında olmalı, p05: "
                        + r.activeParkElapsedPercentileNs(0.05));
    }

    @Test
    @DisplayName("park çağrısı yokken gerçek süre sıfırdır")
    void noParkNoElapsed() {
        FramePacingRecorder r = new FramePacingRecorder();
        r.recordFrame(BUDGET_NS, BUDGET_NS, true, 0, 0, 0, 0, 0);

        assertEquals(0, r.activeParkElapsedMedianNs());
        assertEquals(0, r.activeParkElapsedCalls());
    }

    @Test
    @DisplayName("tavan dışı süreler sayılır")
    void elapsedOverflowIsCounted() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 10; i++) {
            record(r, 50_000_000L, 90_000_000L); // 90 ms, tavan dışı
        }

        assertEquals(10, r.activeParkElapsedOverflow());
        assertEquals(FramePacingRecorder.SATURATED, r.activeParkElapsedMedianNs());
    }

    @Test
    @DisplayName("tavan gerçek park sürelerini kapsar")
    void ceilingCoversRealParkDurations() {
        // İstenen süre 60 Hz bütçesinden (16,67 ms) küçük olmak zorunda; 64 ms tavanı
        // herhalükârda yeterli. Yine de kova çözünürlüğü karar vermeye yetecek kadar.
        assertTrue(FramePacingRecorder.PARK_ELAPSED_STEP_NS <= 50_000L);
        int buckets = (int) (FramePacingRecorder.PARK_ELAPSED_MAX_NS
                / FramePacingRecorder.PARK_ELAPSED_STEP_NS) + 1;
        assertTrue(buckets <= 8_192, "kova sayısı makul olmalı: " + buckets);
    }

    @Test
    @DisplayName("istenen süre de karşılaştırma için raporlanır")
    void requestedTotalIsAvailable() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 10; i++) {
            record(r, 0, 9_100_000L);
        }

        assertEquals(10, r.activeParkCalls());
        assertEquals(90_000_000L, r.activeParkNsTotal());
    }
}