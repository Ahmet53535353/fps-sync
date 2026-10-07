package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Doygunlaşan yüzdelik rapora <b>uydurma sayı</b> olarak giriyordu.
 *
 * <h2>Neden bu dosya var</h2>
 * 1.7.0'dan sonra taban koşusu (sınırsız) ilk kez gerçekten ölçülebilir hâle geldi.
 * Boşta rejimin tek karar satırı {@code 0.1% low} ve rapor şunu yazıyordu:
 *
 * <pre>
 *   1% low  60.0 FPS  ·  0.1% low 0.0  (p99 16.68 ms · p99.9 -0.00 ms)  ·  2000 kare
 * </pre>
 *
 * İki ayrı yanlış aynı satırda:
 *
 * <ol>
 * <li><b>{@code 0.1% low 0.0 FPS}</b> — {@code fpsFromFrameTime(SATURATED)} {@code 0.0}
 *     döndürür. Yani "ölçemedim" yazısı {@code 0} sayısına dönüşüyor. Aktif rejimde
 *     {@link FpsSyncStatusReport#fpsLowLine} bu kontrolü yapıyor, boşta rejimdeki
 *     karşılığı yapmıyordu: aynı veri iki farklı biçimde basılıyordu.</li>
 * <li><b>{@code p99.9 -0.00 ms}</b> — {@code SATURATED} ({@code -1}) {@code ms()}
 *     biçimlendiricisinden geçip {@code -0.00 ms} oluyor. <b>Negatif süre.</b>
 *     Park aşımı satırı aynı hatayı yaşamıştı ve {@code value()} ile düzeltilmişti;
 *     bu satırlar o düzeltmeden geçmedi.</li>
 * </ol>
 *
 * <p>Negatif süre, hiç ölçülmemiş olmaktan daha yanıltıcıdır: kullanıcı "p99.9 yok"
 * değil, "p99.9 eksi sıfır" görür. Doygunluğun kanıtı olan taşma sayacı toplanıyor
 * ama rapora hiç girmiyor.
 */
class SaturatedPercentileReportingTest {

    private static final long IN_WINDOW = 16_670_000L;   // 60 Hz bütçesi, tavanın altında
    private static final long OVER = 100_000_000L;       // 100 ms, 64 ms tavanının üstünde

    /** %0,1 çözülemez ama %99 çözülür: az sayıda taşan kare. */
    private static FramePacingRecorder saturated() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 1995; i++) {
            r.recordFrame(IN_WINDOW, 0, false, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        }
        for (int i = 0; i < 5; i++) {
            r.recordFrame(OVER, 0, false, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        }
        return r;
    }

    private static String idleReport(FramePacingRecorder r) {
        return FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, false, 60, 1366, 768, "1.8.0", SodiumSliderStatus.MIXIN_APPLIED, 0));
    }

    private static String activeReport(FramePacingRecorder r) {
        return FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.8.0", SodiumSliderStatus.MIXIN_APPLIED, 0));
    }

    // ------------------------------------------------------------------ ön koşul

    @Test
    @DisplayName("ön koşul: veri gerçekten doygun — p99 çözülür, p99.9 çözülemez")
    void fixtureActuallySaturates() {
        FramePacingRecorder r = saturated();
        assertTrue(r.idleFrameTimePercentileNs(0.99) > 0,
                "p99 çözülebilmeli");
        assertEquals(FramePacingRecorder.SATURATED, r.idleFrameTimePercentileNs(0.999),
                "p99.9 doygun olmalı — aksi halde test yanlış yere geçer");
        assertTrue(r.idleFps01Low() <= 0.0,
                "0,1% low çözülememiş olmalı");
    }

    // ------------------------------------------------------------------ #2 boşta

    @Test
    @DisplayName("boşta rejimde 0.1% low 'çözülemedi' der, 0.0 FPS basmaz")
    void idleDoesNotPrintFakeZeroFps() {
        String text = idleReport(saturated());
        String line = lineContaining(text, "1% low");

        assertFalse(line.contains("0.1% low 0.0"),
                "uydurma sıfır FPS basıldı:\n" + line);
        assertTrue(line.contains("çözülemedi"),
                "ölçülemedi yazısı olmalı:\n" + line);
    }

    @Test
    @DisplayName("boşta rejimde p99.9 negatif süre basmaz")
    void idleDoesNotPrintNegativeDuration() {
        String text = idleReport(saturated());
        assertFalse(text.contains("-0.00 ms"),
                "negatif süre rapora sızdı:\n" + text);
        assertFalse(text.contains("-0,00 ms"), "negatif süre rapora sızdı");
    }

    // ------------------------------------------------------------------ #1 aktif

    @Test
    @DisplayName("aktif rejimde p99.9 negatif süre basmaz")
    void activeDoesNotPrintNegativeDuration() {
        FramePacingRecorder r = activeSaturated();
        assertEquals(FramePacingRecorder.SATURATED, r.activeFrameTimePercentileNs(0.999),
                "ön koşul: p99.9 doygun olmalı");

        String text = activeReport(r);
        assertFalse(text.contains("-0.00 ms"), "negatif süre rapora sızdı:\n" + text);
    }

    /**
     * Aktif rejimde park yapılmadığında {@code 1% low} satırının <b>tümüyle</b>
     * kaybolması — {@link FpsSyncStatusReport#fpsLowLine} yalnız park bloğunun
     * içinden çağrılıyor. 2000 kare ölçülmüş olmasına rağmen rapor 1% low'u
     * hiç yazmıyor.
     *
     * <p>Bu bulgu <b>kapsam dışı</b>: uydurma sayı üretmiyor, ölçülmüş veriyi
     * gizliyor. Düzeltmesi {@code fpsLowLine}'in park bloğundan çıkarılmasını
     * gerektiriyor (bulguları not etti). Testi burada kilitliyoruz ki
     * düzeltildiğinde fark edilsin.
     */
    @Test
    @DisplayName("park yapılmasa bile aktif rejim 1% low'u yazmalı — şu an yazmıyor")
    void activeFpsLowIsPrintedWithoutPark() {
        FramePacingRecorder r = activeSaturated();
        assertEquals(0L, r.activeParkCalls(), "ön koşul: park çağrısı yok");
        assertEquals(2000L, r.activeFrameTimeCount(), "ön koşul: kareler ölçülmüş");

        String text = activeReport(r);
        assertTrue(text.contains("1% low"),
                "2000 kare ölçülmüşken 1% low satırı hiç yazılmıyor:\n" + text);
    }

    private static FramePacingRecorder activeSaturated() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 1995; i++) {
            r.recordFrame(IN_WINDOW, IN_WINDOW, true, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        }
        for (int i = 0; i < 5; i++) {
            r.recordFrame(OVER, IN_WINDOW, true, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        }
        return r;
    }

    // ------------------------------------------------------------------ gecikme

    @Test
    @DisplayName("gecikme medyan/p95/p99 doygunlaşınca 'ölçülemedi' der")
    void latenessSaturationSaysUnmeasurable() {
        // LATE_FINE_MAX_NS = 50 ms; geç karelerin %60'ı 83 ms -> medyan çözülemez.
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40; i++) {
            r.recordFrame(1_000_000L + IN_WINDOW, IN_WINDOW, true, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        }
        for (int i = 0; i < 60; i++) {
            r.recordFrame(83_000_000L + IN_WINDOW, IN_WINDOW, true, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        }
        assertEquals(FramePacingRecorder.SATURATED, r.activeLatenessMedianNs(),
                "ön koşul: gecikme medyanı doygun olmalı");

        String text = activeReport(r);
        String line = lineContaining(text, "gecikme");
        assertFalse(line.contains("-0.00 ms"),
                "gecikme satırı negatif süre basıyor:\n" + line);
        assertTrue(line.contains("ölçülemedi"),
                "gecikme doygunluğu 'ölçülemedi' olarak yazılmalı:\n" + line);
    }

    // ------------------------------------------------------------------ asimetri

    @Test
    @DisplayName("boşta rejim taşma sayacı raporda görünür — uydurma sıfırın nedeni yazılır")
    void idleOverflowIsReported() {
        String text = idleReport(saturated());
        assertTrue(text.contains("64 ms üstü"),
                "boşta rejimde 5 kare 64 ms üstü ama bu hiç yazılmıyor; "
                        + "kullanıcı '0.1% low 0.0' görüp nedenini arayamıyor:\n" + text);
    }

    private static String lineContaining(String text, String needle) {
        for (String line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        throw new AssertionError("'" + needle + "' içeren satır yok:\n" + text);
    }
}