package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Raporun <b>ölçemediğini ölçmüş gibi göstermemesini</b> kilitler.
 *
 * <h2>Bu test hangi hatayı önler</h2>
 * Ahmet'in ilk gerçek koşusunda rapor şunu bastı:
 * <pre>
 *   park aşımı  medyan 1000.0 µs · p95 1000.0 µs · en kötü 35843.9 µs
 * </pre>
 * Bu bir ölçüm değildi. Aşım histogramı 1 ms'de doyuyor, istenen yüzdelik
 * histogramın dışına düşüyor ve {@code percentile()} sessizce son kovanın değerini
 * döndürüyordu. Gerçekte medyan ve p95 <b>ölçülememişti</b>; "ölçemediğim" ile
 * "tam 1 ms" arasındaki fark raporda yoktu.
 *
 * <p>Ayrıca spin satırı "kare başına" diyordu ama {@code spinEntries} ile bölüyordu;
 * yani etiketi ne ölçtüğünü söylemiyordu. Kare başına ve giriş başına ayrı ayrı
 * verilmesi gerekiyor.
 */
class FpsSyncStatusReportHonestyTest {

    private static final long BUDGET_NS = 16_666_667L;

    private static FramePacingRecorder saturatedRecorder() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 9_000_000L, 96_000L, 4_400L);
        }
        // Kalan hepsi tavan dışı: yüzdelikler çözülemeyecek.
        for (int i = 0; i < 60; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 9_000_000L, 90_000_000L, 4_400L);
        }
        return r;
    }

    private static String render(FramePacingRecorder r) {
        return FpsSyncStatusReport.render(new FpsSyncStatusReport.Snapshot(
                r, true, 60, 1366, 768, "1.1.0+1.21.1", true, 0));
    }

    @Test
    @DisplayName("yüzdelik ölçülemiyorsa rapor bunu açıkça söyler")
    void saturationIsStated() {
        String out = render(saturatedRecorder());

        // Dikkat: "taşma" gibi başka bir satırdan gelen bir sözcük bu koşulu
        // karşılamaz. Mutasyon denemesi ("ölçülemedi" yerine tavan değeri bas)
        // ilk yazımda "taşma" yüzünden geçti; bu yüzden ölçüm yapılmadığını söyleyen
        // sözcük doğrudan aranır.
        assertTrue(out.contains("ölçülemedi"),
                "ölçülemeyen yüzdelik açıkça belirtilmeli, çıktı:\n" + out);
    }

    @Test
    @DisplayName("tavanı aşan aşım sayısı ve oranı raporlanır")
    void overflowCountIsReported() {
        String out = render(saturatedRecorder());

        assertTrue(out.contains("60"),
                "60 tavan-dışı aşım görünmeli, çıktı:\n" + out);
    }

    @Test
    @DisplayName("sahte yuvarlak sayı basılmaz")
    void noFakeRoundNumber() {
        String out = render(saturatedRecorder());

        assertFalse(out.contains("medyan 1000.0 µs"),
                "doygunluk '1000.0 µs' gibi bir değer gibi görünmemeli");
        assertFalse(out.contains("p95 1000.0 µs"),
                "doygun p95 sahte değer olarak görünmemeli");
    }

    @Test
    @DisplayName("park erken dönüşü raporlanır — spin'in sebebi")
    void earlyReturnIsReported() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 100; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 9_000_000L, -3_000_000L, 3_800_000L);
        }

        String out = render(r);

        assertTrue(out.contains("erken"),
                "erken dönüş raporlanmalı, çıktı:\n" + out);
        assertTrue(out.contains("100"), "erken dönüş sayısı görünmeli");
    }

    @Test
    @DisplayName("spin ortalaması spin eden kareye bölünür, tüm kareye değil")
    void spinAverageDividesBySpinningFramesNotAllFrames() {
        FramePacingRecorder r = new FramePacingRecorder();
        // 100 kare bekliyor ama yalnız 10'unda spin var (park tam isabet etmiş).
        for (int i = 0; i < 10; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 9_000_000L, 96_000L, 2_000_000L);
        }
        for (int i = 0; i < 90; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 9_000_000L, 96_000L, 0L);
        }

        // 10 karede 2 ms = 20 ms toplam. 100 kareye bölünürse 200 µs, doğrusu 2 ms.
        String out = render(r);

        assertTrue(out.contains("spin eden kare başına"),
                "bölünen küme açıkça yazılmalı, çıktı:\n" + out);
        assertTrue(out.contains("2.0 ms") || out.contains("2000.0"),
                "spin eden kare başına ortalama 2.0 ms görünmeli, çıktı:\n" + out);
    }
}