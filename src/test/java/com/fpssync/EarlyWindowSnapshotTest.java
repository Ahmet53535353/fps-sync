package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * İlk 10 dakikalık <b>karşılaştırılabilir</b> pencereyi kilitler.
 *
 * <h2>Neden sabit pencere</h2>
 * Üç ardışık koşuda ölçülen CPU payı %17,0 → %17,5 → %25,1 çıktı ve kod
 * değişmedi. Değişen şey oyundu: gezinme, maden, başka koşuda başka bir şey.
 * Toplamlar koşular arasında karşılaştırılamaz — birinde boşta kare sayısı 6.725,
 * diğerinde 4.132.
 *
 * <p>Sabit 10 dakikalık pencere koşulları sabitler: dünya yüklenmiş, ısınma
 * bitmiş, oyuncu aynı yerde. Sonrasındaki dakikalar neler olursa olsun ilk
 * pencere değişmez.
 *
 * <h2>Neden kopyalama</h2>
 * Yüzdeler de karşılaştırmanın parçası (park ikiye bölünüyor mu). Yalnız
 * skalerleri dondurmak dağıtımın <em>şeklini</em> kaybettirir. Tek seferlik
 * kopyadır; sıcak yolda olmaz.
 */
class EarlyWindowSnapshotTest {

    private static final long BUDGET_NS = 16_666_667L;

    /** Belirtilen süre kadar geçen, belirtilen sayıda kare yazar. */
    private static FramePacingRecorder recordFor(FramePacingRecorder r, long targetNs,
            int frames, long spinNs) {
        long perFrame = targetNs / frames;
        for (int i = 0; i < frames; i++) {
            r.recordFrame(perFrame, BUDGET_NS, true, 1, 9_000_000L, -7_000_000L, spinNs,
                    2_000_000L);
        }
        return r;
    }

    @Test
    @DisplayName("pencere 10 dakika")
    void windowIsTenMinutes() {
        assertEquals(600_000_000_000L, FramePacingRecorder.EARLY_WINDOW_NS);
    }

    @Test
    @DisplayName("600 sn dolmadan pencere yoktur")
    void noSnapshotBeforeTheWindow() {
        FramePacingRecorder r = recordFor(new FramePacingRecorder(), 300_000_000_000L, 18_000, 100_000L);

        assertNull(r.earlyWindowTotals(),
                "10 dakika dolmadan 'ilk pencere' üretilmemeli — uydurma veri olur");
        assertTrue(!r.earlyWindowCaptured());
    }

    @Test
    @DisplayName("600 sn dolunca pencere oluşur ve o andan sonrasını içermez")
    void snapshotFreezesAtTheBoundary() {
        FramePacingRecorder r = new FramePacingRecorder();
        // 600 sn'yi geçecek kadar, sonra fazlası
        recordFor(r, 700_000_000_000L, 42_000, 100_000L);

        FramePacingRecorder.Totals early = r.earlyWindowTotals();
        assertNotNull(early, "sınır geçildiği için pencere oluşmalı");
        assertTrue(early.waitingFrames() > 0, "pencerede kare olmalı");
        assertTrue(early.spinNsTotal() > 0, "pencerede spin olmalı");
    }

    @Test
    @DisplayName("pencere bir kez alınır, sonraki kareler onu değiştirmez")
    void snapshotIsTakenOnlyOnce() {
        FramePacingRecorder r = new FramePacingRecorder();
        recordFor(r, 700_000_000_000L, 42_000, 100_000L);
        long frozen = r.earlyWindowTotals().spinNsTotal();
        long frozenFrames = r.earlyWindowTotals().waitingFrames();

        recordFor(r, 700_000_000_000L, 42_000, 100_000L);   // çok daha fazla kare

        assertEquals(frozen, r.earlyWindowTotals().spinNsTotal(),
                "dondurulmuş pencere değişmemeli");
        assertEquals(frozenFrames, r.earlyWindowTotals().waitingFrames(),
                "dondurulmuş kare sayısı değişmemeli");
    }

    @Test
    @DisplayName("sıfırlama pencereyi de temizler")
    void resetClearsTheSnapshot() {
        FramePacingRecorder r = new FramePacingRecorder();
        recordFor(r, 700_000_000_000L, 42_000, 100_000L);
        assertNotNull(r.earlyWindowTotals());

        r.reset();

        assertNull(r.earlyWindowTotals(), "sıfırlamadan sonra pencere gitmeli");
        assertTrue(!r.earlyWindowCaptured());
    }

    @Test
    @DisplayName("duraklatılan kayıt pencereyi ilerletmez")
    void pausedRecordingDoesNotAdvanceTheWindow() {
        FramePacingRecorder r = new FramePacingRecorder();
        r.setPaused(true);
        recordFor(r, 700_000_000_000L, 42_000, 100_000L);

        assertNull(r.earlyWindowTotals(),
                "duraklatılmış kayıt pencereyi oluşturmamalı");
    }

    @Test
    @DisplayName("tüm oyun toplamları da raporlanabilir")
    void totalsCoverTheWholeSession() {
        FramePacingRecorder r = new FramePacingRecorder();
        recordFor(r, 100_000_000_000L, 6_000, 500_000L);

        FramePacingRecorder.Totals t = r.totals();
        assertEquals(6_000, t.waitingFrames());
        assertEquals(6_000, t.spinEntries());
        assertEquals(3_000_000_000L, t.spinNsTotal());
        assertEquals(6_000, t.parkCalls());
        assertEquals(6_000, t.parkEarlyCalls(), "tüm çağrılar erken döndü");
    }

    @Test
    @DisplayName("boşta kareler de toplama girer")
    void idleFramesAreCounted() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 10; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 9_000_000L, 0, 100_000L, 9_100_000L);
        }
        for (int i = 0; i < 4; i++) {
            r.recordFrame(38_000_000L, BUDGET_NS, false, 0, 0, 0, 0, 0);
        }

        FramePacingRecorder.Totals t = r.totals();
        assertEquals(10, t.waitingFrames());
        assertEquals(4, t.idleFrames());
    }
}