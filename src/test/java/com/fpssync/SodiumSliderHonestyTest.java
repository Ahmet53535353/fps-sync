package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Raporun "Sodium slider" satırı ölçtüğü şeyi ölçmüyordu.
 *
 * <h2>Neden bu dosya var</h2>
 * Raporda şu yazıyordu:
 * <pre>
 *   .append(s.sodiumSlider() ? "uygulandı" : "uygulanmadı")
 * </pre>
 * ve {@code sodiumSlider} alanına gönderilen değer
 * {@code sync || !SodiumPresence.isPresent()} idi — yani <b>türetilmiş</b>, ölçülmüş
 * değil. Gerçek sinyal modda zaten vardı ve hiç okunmuyordu:
 * {@code FpsSyncMod.sliderInjected} (postApply'de set edilir) ve doğru kararı veren
 * saf {@link SodiumSliderStatus#decide(boolean, boolean)}.
 *
 * <p>Dört durumun dördü de yanlış cevap veriyordu:
 *
 * <table>
 * <caption>1.7.0 raporunun verdiği cevap</caption>
 * <tr><th>FPS Sync</th><th>Sodium</th><th>Rapor</th><th>Doğru</th></tr>
 * <tr><td>açık</td><td>kurulu</td><td>uygulandı</td><td>uygulanmamış olabilir</td></tr>
 * <tr><td>açık</td><td><b>yok</b></td><td><b>uygulandı</b></td><td>slider gerekmez</td></tr>
 * <tr><td>kapalı</td><td>kurulu</td><td>uygulanmadı</td><td>uygulanmış olabilir</td></tr>
 * <tr><td>kapalı</td><td>yok</td><td>uygulandı</td><td>slider yok</td></tr>
 * </table>
 *
 * <p>En kötüsü ikinci satır: <b>Sodium hiç kurulu değilken</b> rapor "uygulandı" diyor.
 * Kullanıcının slider'ı sessizce yokken (log'da MIXIN_NOT_APPLIED uyarısı basılır)
 * rapor ters yönde bilgi veriyor. 1.7.0'daki "ölçüm kapısı" hatasının en saf örneği:
 * veri toplanıyor, erişimci onu okumuyor.
 *
 * <h2>Çözüm</h2>
 * Boolean yerine <b>üç durum</b> taşınır. Rapor her durumda farklı metin yazar;
 * yalnız gerçek arızada (Sodium kurulu ama slider görünmüyor) "aç" der.
 */
class SodiumSliderHonestyTest {

    /** {@code buildReport}'in bugün ürettiği türetilmiş değer — düzeltilmiş olmalı. */
    private static String sliderLine(String text) {
        for (String line : text.split("\n")) {
            if (line.contains("Sodium slider")) {
                return line;
            }
        }
        throw new AssertionError("'Sodium slider' satırı yok:\n" + text);
    }

    private static FramePacingRecorder oneFrame() {
        FramePacingRecorder r = new FramePacingRecorder();
        r.recordFrame(16_670_000L, 16_670_000L, true, 0, 0L, 0L, 0L, 0L,
                0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        return r;
    }

    // ------------------------------------------------------------------ karar

    @Test
    @DisplayName("Sodium yoksa slider gerekmez — 'uygulanmadı' değil")
    void absentSodiumIsNotAFailure() {
        assertEquals(SodiumSliderStatus.SODIUM_ABSENT,
                SodiumSliderStatus.decide(false, false));
        // Bayrak yanlışlıkla true olsa bile arıza dememeli.
        assertEquals(SodiumSliderStatus.SODIUM_ABSENT,
                SodiumSliderStatus.decide(false, true));
        assertFalse(SodiumSliderStatus.SODIUM_ABSENT.isFailure());
    }

    @Test
    @DisplayName("Sodium kurulu ama slider uygulanmadıysa bu gerçek arızadır")
    void missingInjectionIsTheOnlyFailure() {
        assertEquals(SodiumSliderStatus.MIXIN_NOT_APPLIED,
                SodiumSliderStatus.decide(true, false));
        assertTrue(SodiumSliderStatus.MIXIN_NOT_APPLIED.isFailure());
        assertEquals(SodiumSliderStatus.MIXIN_APPLIED,
                SodiumSliderStatus.decide(true, true));
        assertFalse(SodiumSliderStatus.MIXIN_APPLIED.isFailure());
    }

    // ------------------------------------------------------------------ rapor

    @Test
    @DisplayName("rapor üç durumu da ayırt eder — 'uygulandı' her koşuda basılmaz")
    void reportDistinguishesAllThreeStates() {
        String absent = sliderLine(reportFor(SodiumSliderStatus.SODIUM_ABSENT));
        assertTrue(absent.contains("gerekmiyor"),
                "Sodium yokken slider'ın görünmemesi beklenen durumdur, arıza değil:\n" + absent);
        assertFalse(absent.contains("UYGULANMADI"), absent);

        String applied = sliderLine(reportFor(SodiumSliderStatus.MIXIN_APPLIED));
        assertTrue(applied.contains("uygulandı"), applied);

        String notApplied = sliderLine(reportFor(SodiumSliderStatus.MIXIN_NOT_APPLIED));
        assertTrue(notApplied.contains("UYGULANMADI"), notApplied);
    }

    @Test
    @DisplayName("slider yokken rapor 'uygulandı' DEMEZ — en kötü yanlış cevap")
    void absentSodiumNeverClaimsApplied() {
        String line = sliderLine(reportFor(SodiumSliderStatus.SODIUM_ABSENT));
        assertFalse(line.contains("uygulandı"),
                "Sodium kurulu değilken 'uygulandı' deniyor:\n" + line);
    }

    @Test
    @DisplayName("arıza durumu kullanıcıya ne yapması gerektiğini söyler")
    void failureStateIsActionable() {
        String line = sliderLine(reportFor(SodiumSliderStatus.MIXIN_NOT_APPLIED));
        assertTrue(line.contains("UYGULANMADI"), line);
        assertTrue(line.contains("Sodium") || line.contains("slider"),
                "hangi ekrandan bakılacağı yazılmalı:\n" + line);
    }

    /**
     * Üretim yolunun slider değerini okuması gerekir.
     *
     * <p>Eskiden {@code sync || !SodiumPresence.isPresent()} geçiliyordu; bu
     * ifade FPS Sync açıkken <b>her koşuda</b> "uygulandı" üretiyordu.
     */
    @Test
    @DisplayName("slider durumu türetilmiş değil, ölçülmüş olmalı")
    void sliderStatusIsMeasuredNotDerived() {
        assertEquals(SodiumSliderStatus.decide(true, false),
                FpsSyncMod.sliderStatus(true, false),
                "ölçülen sinyal kararı vermeli");
        assertEquals(SodiumSliderStatus.decide(false, true),
                FpsSyncMod.sliderStatus(false, true),
                "bayrak true olsa bile Sodium yoksa arıza sayılmamalı");
    }

    private static String reportFor(SodiumSliderStatus status) {
        return FpsSyncStatusReport.render(new FpsSyncStatusReport.Snapshot(
                oneFrame(), true, 60, 1366, 768, "1.8.0", status, 60));
    }
}