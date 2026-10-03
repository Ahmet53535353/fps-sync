package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rapor metninin biçimini kilitler.
 *
 * <p>Rapor saf bir dize üretimiyle oluşur; Minecraft sınıflarına dokunmaz. Böylece
 * biçim hem gerçek ortam olmadan doğrulanabilir hem de yerelleştirme sırasında
 * değişmez (sabit {@link java.util.Locale} kullanılır).
 */
class FpsSyncStatusReportTest {

    private static final long BUDGET_NS = 16_666_667L; // 60 Hz

    private static FpsSyncStatusReport.Snapshot sample() {
        FramePacingRecorder r = new FramePacingRecorder();
        // Başlangıç: sınırlayıcı boşta, oyun yavaş.
        for (int i = 0; i < 100; i++) {
            r.recordFrame(200_000_000, BUDGET_NS, false, 0, 0, 0, 0);
        }
        // Sonra 60 FPS'e ulaşıyor, sınırlayıcı devreye giriyor.
        for (int i = 0; i < 600; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 16_500_000, 96_000, 4_400);
        }
        for (int i = 0; i < 3; i++) {
            r.recordFrame(BUDGET_NS + 500_000, BUDGET_NS, true, 1, 16_500_000, 96_000, 4_400);
        }
        return new FpsSyncStatusReport.Snapshot(r, true, 60, 1920, 1080, "1.1.0+1.21.1",
                true, 0);
    }

    @Test
    @DisplayName("iki rejim ayrı ayrı raporlanır")
    void bothRegimesAppear() {
        String out = FpsSyncStatusReport.render(sample());

        assertTrue(out.contains("BEKLEYEN"), "bekleyen rejim başlığı yok");
        assertTrue(out.contains("BEKLEME YAPILMAYAN"), "boşta rejim başlığı yok");
    }

    @Test
    @DisplayName("geç kare oranı hesaplanır")
    void lateFrameRatioIsReported() {
        String out = FpsSyncStatusReport.render(sample());

        // 3 geç kare / 603 bekleyen kare = %0.5
        assertTrue(out.contains("3"), "geç kare sayısı görünmeli");
        assertTrue(out.contains("%"), "yüzde görünmeli");
    }

    @Test
    @DisplayName("FPS kare / geçen süre olarak hesaplanır")
    void fpsIsShown() {
        String out = FpsSyncStatusReport.render(sample());

        assertTrue(out.contains("FPS"), "FPS satırı yok");
    }

    @Test
    @DisplayName("park aşımı ayrı satırda raporlanır")
    void overshootIsReported() {
        String out = FpsSyncStatusReport.render(sample());

        assertTrue(out.contains("aşım"), "park aşımı satırı yok");
    }

    @Test
    @DisplayName("ilk bekleme süresi bildirilir")
    void firstWaitIsReported() {
        String out = FpsSyncStatusReport.render(sample());

        assertTrue(out.contains("ilk bekleme"), "ilk bekleme bilgisi yok");
    }

    @Test
    @DisplayName("algılanan monitör hızı ve pencere boyutu yazılır")
    void monitorAndWindowAreShown() {
        String out = FpsSyncStatusReport.render(sample());

        assertTrue(out.contains("60 Hz"), "monitör hızı yok");
        assertTrue(out.contains("1920x1080"), "pencere boyutu yok");
    }

    @Test
    @DisplayName("hiç ölçüm yokken rapor çökmez ve 'veri yok' der")
    void emptyReportIsGraceful() {
        FramePacingRecorder empty = new FramePacingRecorder();
        String out = FpsSyncStatusReport.render(new FpsSyncStatusReport.Snapshot(
                empty, false, 60, 0, 0, "1.1.0+1.21.1", false, 0));

        assertFalse(out.contains("NaN"), "NaN üretilmemeli");
        assertFalse(out.contains("Infinity"), "Infinity üretilmemeli");
        assertTrue(out.contains("Henüz kare kaydedilmedi"), "boş durum açıkça bildirilmeli");
    }

    @Test
    @DisplayName("tam sayı ve ondalıklar nokta ile yazılır — yerelleştirmeden bağımsız")
    void formattingIsLocaleIndependent() {
        String out = FpsSyncStatusReport.render(sample());

        // Niyet: sayıların ondalık ayırıcısı virgül olmasın (Türkçe yerel ayarı).
        // Tüm virgülleri yasaklamak çok geniş bir denetlemdi: rapor bir cümle
        // içinde virgül kullandığında kırılıyordu, oysa ondalıkla ilgisi yoktu.
        // Bu yüzden yalnız rakam- virgül -rakam biçimini arıyoruz.
        assertFalse(out.matches("(?s).*\\d,\\d.*"),
                "ondalık ayırıcı virgül olmamalı (deterministik test)");
        assertTrue(out.matches("(?s).*\\d\\.\\d.*"),
                "ondalık ayırıcı nokta olmalı");
    }

    @Test
    @DisplayName("sıfır ayak izi bırakmadan üretilir")
    void renderDoesNotThrowOnEmptyRegime() {
        FramePacingRecorder r = new FramePacingRecorder();
        r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, 1_000_000, 0, 0);

        String out = FpsSyncStatusReport.render(new FpsSyncStatusReport.Snapshot(
                r, true, 144, 2560, 1440, "1.1.0+1.21.1", true, 0));

        assertTrue(out.length() > 0);
    }
}