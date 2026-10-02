package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Park aşımı ölçümünün karar verebileceği aralığa eriştiğini kilitler.
 *
 * <h2>Bu test hangi hatayı önler</h2>
 * 2026-10-02'de Ahmet'in ilk gerçek koşusunda rapor şunu bastı:
 * <pre>
 *   park aşımı  medyan 1000.0 µs · p95 1000.0 µs · en kötü 35843.9 µs
 * </pre>
 * Bu <b>ölçüm değil</b>, histogramın 1 ms'de doymasıydı. Aşım histogramı 0–1 ms
 * aralığını tutuyordu; üstü {@code overshootOverflow} sayacına düşüyordu ama o
 * raporda <b>gösterilmiyordu</b>. Sonra {@code percentile()} hedefe ulaşamayınca
 * sessizce {@code (hist.length - 1) * step} döndürüyordu ve bu tam olarak
 * {@code 1000.0 µs} eder — yani "veri yok" ile "aşım tam 1 ms" ayırt edilemiyordu.
 *
 * <p>Aşımın gerçekten nerede olduğu ({@code en kötü} 35.8 ms) yalnızca ayrı bir
 * sayaçta duruyordu. Yani araç, kendisine en çok ihtiyaç duyulan aralığı ölçemiyordu.
 *
 * <p>Düzeltme üç parça: tavan 64 ms'e ve 10 µs adıma çıkarıldı, taşma sayacı
 * rapora çıkarıldı, doygunluk sessiz bir değer yerine açık bir işaret olarak
 * dönmeye başladı.
 */
class OvershootMeasurementTest {

    private static final long BUDGET_NS = 16_666_667L;

    private static FramePacingRecorder record(FramePacingRecorder r, long overshootNs) {
        r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 9_000_000L, overshootNs, 100_000L);
        return r;
    }

    @Test
    @DisplayName("1 ms üstü aşım artık ölçülebiliyor (eski tavan 1 ms idi)")
    void overshootAboveOneMsIsResolvable() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 50; i++) {
            record(r, 5_000_000L); // 5 ms
        }

        long median = r.activeOvershootMedianNs();
        assertTrue(median > 1_000_000L,
                "5 ms aşım 1 ms tavanının üstünde ölçülemiyordu, medyan: " + median);
        assertTrue(median <= 5_000_000L, "medyan gerçek değeri aşmamalı: " + median);
        assertEquals(0, r.activeOvershootOverflow(),
                "5 ms artık tavanın altında, taşma sayacı boş kalmalı");
    }

    @Test
    @DisplayName("tavan aşılırsa medyan sahte değil, açık işaret döner")
    void percentileIsExplicitWhenSaturated() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 50; i++) {
            record(r, 200_000_000L); // 200 ms, 64 ms tavanının çok üstünde
        }

        assertEquals(FramePacingRecorder.SATURATED, r.activeOvershootMedianNs(),
                "doygunluk sessizce son kova değerine dönüşmemeli");
        assertEquals(FramePacingRecorder.SATURATED, r.activeOvershootPercentileNs(0.95));
    }

    @Test
    @DisplayName("tavanı aşan aşımlar sayılır ve görünür")
    void overflowCountIsExposed() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 50; i++) {
            record(r, 200_000_000L); // hepsi tavan dışı
        }

        assertEquals(50, r.activeOvershootOverflow(),
                "tavanı aşan her park çağrısı sayılmalı");
    }

    @Test
    @DisplayName("en büyük aşım tavanı aşsa da tam değer korunur")
    void maxStaysExactBeyondCeiling() {
        FramePacingRecorder r = new FramePacingRecorder();
        record(r, 5_000_000L);
        record(r, 35_843_900L); // gerçek koşuda görülen en kötü değer
        record(r, 1_000_000L);

        assertEquals(35_843_900L, r.activeOvershootMaxNs(),
                "en büyük değer histogramla sınırlı olmamalı");
    }

    @Test
    @DisplayName("park erken dönerse bu da kaydedilir (spin neden uzadı)")
    void earlyParkReturnIsRecorded() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 10; i++) {
            record(r, -3_000_000L); // 3 ms ERKEN döndü
        }
        for (int i = 0; i < 10; i++) {
            record(r, 90_000L); // normal
        }

        assertEquals(10, r.activeParkEarlyCalls(), "erken dönüşler ayrı sayılmalı");
        assertEquals(30_000_000L, r.activeParkEarlyNsTotal(),
                "erken dönüş miktarı toplanmalı");
        // Erken dönüşler aşım histogramına karışmamalı.
        assertTrue(r.activeOvershootMaxNs() <= 90_000L,
                "erken dönüş aşım olarak sayılmamalı, max: " + r.activeOvershootMaxNs());
    }

    @Test
    @DisplayName("park çağrısı yokken erken dönüş de sayılmaz")
    void noParkNoEarlyReturn() {
        FramePacingRecorder r = new FramePacingRecorder();
        r.recordFrame(BUDGET_NS, BUDGET_NS, true, 0, 0, -5_000_000L, 100_000L);

        assertEquals(0, r.activeParkEarlyCalls());
        assertEquals(0, r.activeParkCalls());
    }

    @Test
    @DisplayName("tavan 64 ms: 35.8 ms'lik gerçek aşım aralığın içinde")
    void ceilingCoversTheObservedRealWorldValue() {
        assertTrue(FramePacingRecorder.OVERSHOOT_MAX_NS >= 64_000_000L,
                "gözlenen 35.8 ms aşımın üstünü kapsamalı");
        assertTrue(FramePacingRecorder.OVERSHOOT_MAX_NS < 100_000_000L,
                "tavAN 100 ms'i aşmamalı, gereksiz bellek");
    }

    @Test
    @DisplayName("adım 10 µs: 1 ms ile 1.5 ms ayırt edilebiliyor")
    void stepResolvesTheDecisionRange() {
        assertTrue(FramePacingRecorder.OVERSHOOT_STEP_NS <= 50_000L,
                "50 µs'tan geniş adım karar aralığını çözemez");
        int buckets = (int) (FramePacingRecorder.OVERSHOOT_MAX_NS
                / FramePacingRecorder.OVERSHOOT_STEP_NS) + 1;
        assertTrue(buckets <= 8_192,
                "kova sayısı bellek için makul olmalı, oldu: " + buckets);
    }
}