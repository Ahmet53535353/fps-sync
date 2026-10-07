package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sınırlayıcının <b>hiç çalışmadığı</b> koşuda raporun sahte sayı basmaması.
 *
 * <h2>Yanlış anlaşılan hata</h2>
 * 10 dakikalık blokta {@code geç kare} oranı {@code lateFrames / waitingFrames}
 * idi ve koruması yoktu. "Sınırlayıcı hiç beklemediyse rapor çöker" dedim — bu
 * <b>yanlıştı</b>: çarpım {@code 100.0 * lateFrames} double olduğu için
 * {@code 0,0 / 0} çökmez, {@code Infinity} basar.
 *
 * <p>Yani koruma bir çökme değil, <b>sahte sayıyı</b> engelliyordu. Ve bu daha kötü:
 * rapor {@code Infinity%} yazar, kullanıcı bunu ölçüm sanar. Oyun 60'a hiç
 * ulaşamazsa {@code waitingFrames == 0} olur — yani tam olarak "sınırlayıcı hiç
 * çalışmadı" durumunda, yani makul bir koşuda.
 *
 * <h2>Bu testin değeri</h2>
 * Aynı sebeple {@code spin} satırındaki {@code elapsedNs} paydası da korunuyor.
 * İkisi de <em>yazılmayacak</em> değer üretiyor: satır hiç basılmamalı.
 */
class FakeRatioTest {

    private static final long B = 1_000_000_000L / 60L;

    private static String render(FramePacingRecorder r) {
        return FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.5.0", SodiumSliderStatus.MIXIN_APPLIED, 0));
    }

    @Test
    @DisplayName("sınırlayıcı hiç çalışmadıysa Infinity/NaN basılmaz")
    void noFakeRatioWhenLimiterNeverWaited() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            r.recordFrame(37_000_000L, B, false, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 1_000_000L, 1, 1_000_000L);
        }

        assertEquals(0, r.totals().waitingFrames(),
                "önce koşul: sınırlayıcı hiç beklememiş olmalı");

        String text = render(r);

        assertTrue(!text.contains("Infinity"),
                "Infinity basılmamalı, alınan:\n" + text);
        assertTrue(!text.contains("NaN"),
                "NaN basılmamalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("paydası olmayan geç kare oranı hiç yazılmaz")
    void noLateRatioLineWithoutDenominator() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            r.recordFrame(37_000_000L, B, false, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 1_000_000L, 1, 1_000_000L);
        }

        String text = render(r);

        assertTrue(text.contains("İLK 10 DAKİKA"),
                "10 dakikalık blok yine de yazılmalı, alınan:\n" + text);
        assertTrue(text.contains("limiter devrede 0.0%"),
                "sınırlayıcının hiç çalışmadığı görünmeli, alınan:\n" + text);
        assertTrue(!text.contains("geç kare           "),
                "paydası sıfır olan oran satırı basılmamalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("sınırlayıcı çalışıyorsa geç kare oranı yine yazılır")
    void lateRatioStillPrintedWhenMeasurable() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            r.recordFrame(B + 4_000_000L, B, true, 1, B - 100_000L,
                    0L, 0L, B - 100_000L, 0L, 0L, 0, 0, 0L, 200_000L, 1, 200_000L);
        }

        String text = render(r);

        assertTrue(text.contains("geç kare           "),
                "ölçülebilir durumda oran yazılmalı, alınan:\n" + text);
    }
}