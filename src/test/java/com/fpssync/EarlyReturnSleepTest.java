package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Yalnız <b>erken dönen</b> park çağrılarının uyuma süresi dağılımı.
 *
 * <h2>Neden ayrı ölçülüyor</h2>
 * Toplam park süresi iki farklı popülasyonu karıştırıyor: düzgün çalışan çağrılar
 * (istenenin tamamını uyuyan) ve erken dönenler (istenenin onda birini uyuyan).
 * Ortalama ve yüzdelikler ikisini birlikte özetlediği için tek başına anlam taşımıyor.
 *
 * <p>Bilinen: üç koşuda da erken karelerde park istenenin %12–30'unu uyumuş ve
 * {@code p05} sıfırdı. Bilinmeyen: <em>dağılımın şekli</em>. Hepsi 1 ms'de mi
 * toplanıyor, yoksa yayık mı? Buna göre park tekrarının kaç deneme yapması
 * gerektiği değişir — ortalama ile karar vermek tahmindir.
 *
 * <h2>Bu teşhis, düzeltmeyi değil ölçümü besler</h2>
 * Park tekrarı (A) her koşulda faydalıdır, ancak kaç deneme yeterliğini bu
 * dağılım belirler. Dağılım dar ise 2–3 deneme yeter, yayıksa daha fazlası
 * gerekir. Raporun göstereceği "kare başına park çağrısı" da bunu doğrudan
 * ölçer.
 */
class EarlyReturnSleepTest {

    private static final long BUDGET_NS = 16_666_667L;
    private static final long REQUESTED_NS = 9_000_000L;

    private static void record(FramePacingRecorder r, long overshootNs, long elapsedNs) {
        r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, REQUESTED_NS, overshootNs, 100_000L,
                elapsedNs, 0L, 0L);
    }

    @Test
    @DisplayName("erken dönüşlerin uyuma süresi ayrı ölçülür")
    void earlySleepIsRecordedSeparately() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 50; i++) {
            record(r, -7_000_000L, 2_000_000L);   // 9 ms istendi, 2 ms'de döndü
        }

        assertEquals(50, r.activeParkEarlyCalls(), "erken dönüş sayısı");
        long median = r.activeEarlySleepMedianNs();
        assertTrue(median > 1_500_000L && median <= 2_000_000L,
                "erken karede uyunan süre 2 ms bandında olmalı, medyan: " + median);
    }

    @Test
    @DisplayName("düzgün dönenler erken dağılıma girmez")
    void lateReturnsAreNotInTheEarlyDistribution() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 90; i++) {
            record(r, 80_000L, 9_080_000L);       // düzgün
        }
        for (int i = 0; i < 10; i++) {
            record(r, -7_000_000L, 2_000_000L);   // erken
        }

        long median = r.activeEarlySleepMedianNs();
        assertTrue(median <= 2_000_000L,
                "90 düzgün çağrı medyanı bozmamalı, medyan: " + median);
    }

    @Test
    @DisplayName("hiç erken dönüş yoksa dağılım ölçülemez değer verir")
    void noEarlyReturnsYieldsZero() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 50; i++) {
            record(r, 80_000L, 9_080_000L);
        }

        assertEquals(0, r.activeEarlySleepMedianNs(),
                "erken dönüş yoksa ortalaması da sıfırdır, doygun işareti değil");
        assertEquals(0, r.activeEarlySleepCalls());
    }

    @Test
    @DisplayName("tavan dışı erken uyku sayılır")
    void overflowIsCounted() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 10; i++) {
            record(r, -50_000_000L, 90_000_000L);   // 90 ms
        }

        assertEquals(10, r.activeEarlySleepOverflow());
    }

    @Test
    @DisplayName("dağılımın kuyruğu okunabilir: p05 ve p95 ayrışır")
    void distributionTailsAreReadable() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 80; i++) {
            record(r, -7_000_000L, 1_000_000L);     // sık: 1 ms
        }
        for (int i = 0; i < 20; i++) {
            record(r, -3_000_000L, 6_000_000L);     // seyrek: 6 ms
        }

        long p05 = r.activeEarlySleepPercentileNs(0.05);
        long p95 = r.activeEarlySleepPercentileNs(0.95);
        assertTrue(p05 < 2_000_000L, "p05 sık kuvvetin içinde olmalı: " + p05);
        assertTrue(p95 > 5_000_000L, "p95 seyrek kuvvetin içinde olmalı: " + p95);
    }

    @Test
    @DisplayName("sıfırlama erken dağılımı da temizler")
    void resetClearsEarlySleep() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 20; i++) {
            record(r, -7_000_000L, 2_000_000L);
        }
        assertTrue(r.activeEarlySleepCalls() > 0);

        r.reset();

        assertEquals(0, r.activeEarlySleepCalls());
        assertEquals(0, r.activeEarlySleepMedianNs());
    }
}