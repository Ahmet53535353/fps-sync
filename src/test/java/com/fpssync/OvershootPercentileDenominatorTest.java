package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Yüzdelik hesabının <b>doğru paydayı</b> kullandığını kilitler.
 *
 * <h2>Bu test hangi hatayı önler</h2>
 * Faz 1'de erken dönüş takibini ekledim: negatif aşım artık ayrı sayılıyor ve aşım
 * histogramına <em>girmiyor</em>. Ama {@code percentile()} hâlâ payda olarak
 * {@code parkCalls} (tüm çağrılar) alıyordu.
 *
 * <p>Gerçek koşuda: 55.889 park çağrısı, 27.987'si erken. Histograma giren geç
 * dönüş sayısı 27.902, medyan hedefi ise {@code ceil(0,5 × 55.889) = 27.945}. 43 örnek
 * eksik olduğu için döngü hiç hedefe ulaşamıyor ve doygunluk işareti dönüyordu.
 *
 * <p>Sonuç: <b>erken dönüş olan her koşuda</b> medyan ve p95 ölçülemez olurdu. Bu,
 * 2026-10-02 koşusunda olmuştu. Ölçüm aracı, ölçtüğü şeyi yine de ölçemiyordu.
 *
 * <h2>Doğru davranış</h2>
 * Aşım dağılımı yalnız <em>geç dönüşler</em> üzerinden tanımlıdır; erken dönüş aşım
 * değildir. Payda histogramda gerçekten bulunan örnek sayısı olmalı, ve rapor
 * bunu açıkça söylemelidir.
 */
class OvershootPercentileDenominatorTest {

    private static final long BUDGET_NS = 16_666_667L;

    private static void record(FramePacingRecorder r, long overshootNs) {
        r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 9_000_000L, overshootNs, 100_000L);
    }

    @Test
    @DisplayName("erken dönüşler yüzdeliği bozmaz (asıl regresyon)")
    void earlyReturnsDoNotBreakPercentiles() {
        FramePacingRecorder r = new FramePacingRecorder();
        // Yarısı 6,93 ms erken — gerçek koşudaki gibi.
        for (int i = 0; i < 30; i++) {
            record(r, -6_930_000L);
        }
        for (int i = 0; i < 30; i++) {
            record(r, 2_000_000L);
        }

        long median = r.activeOvershootMedianNs();
        assertTrue(median != FramePacingRecorder.SATURATED,
                "erken dönüşler yüzdeliği doygunlaştırdı, medyan: " + median);
        assertTrue(median >= 2_000_000L && median <= 2_000_000L,
                "medyan geç dönüş değerinde olmalı, oldu: " + median);
    }

    @Test
    @DisplayName("p95 de erken dönüşlerden etkilenmez")
    void p95IsAlsoUnaffected() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 90; i++) {
            record(r, -6_930_000L);
        }
        for (int i = 0; i < 10; i++) {
            record(r, 2_000_000L);
        }

        assertTrue(r.activeOvershootPercentileNs(0.95) != FramePacingRecorder.SATURATED,
                "p95 doygunlaştı: erken dönüşler paydaya sayılıyor");
    }

    @Test
    @DisplayName("gerçek doygunluk yine de yakalanır")
    void genuineSaturationIsStillReported() {
        FramePacingRecorder r = new FramePacingRecorder();
        // Hepsi tavan dışı: bu kez gerçekten ölçülemez.
        for (int i = 0; i < 30; i++) {
            record(r, 90_000_000L);
        }

        assertEquals(FramePacingRecorder.SATURATED, r.activeOvershootMedianNs(),
                "gerçek taşma gizlenmemeli");
    }

    @Test
    @DisplayName("tam erken dönüşte yüzdelik 0 olur, doygunluk değil")
    void allEarlyYieldsZeroNotSaturation() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 20; i++) {
            record(r, -1_000_000L);
        }

        assertEquals(0, r.activeOvershootMedianNs(),
                "geç dönüş yoksa aşım da yoktur; ölçülemez demek yanlış olur");
    }

    @Test
    @DisplayName("geç dönüş sayısı ayrıca bildirilir")
    void lateReturnCountIsExposed() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 20; i++) {
            record(r, -1_000_000L);
        }
        for (int i = 0; i < 80; i++) {
            record(r, 2_000_000L);
        }

        assertEquals(100, r.activeParkCalls(), "toplam çağrı");
        assertEquals(20, r.activeParkEarlyCalls(), "erken dönüş");
        assertEquals(80, r.activeOvershootLateCalls(),
                "aşım dağılımının örnek sayısı geç dönüşler olmalı");
    }
}