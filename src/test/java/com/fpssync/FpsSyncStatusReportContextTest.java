package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Raporun <b>kendi kendini yeterince tanımladığını</b> kilitler.
 *
 * <h2>Bu test hangi eksikleri önler</h2>
 * 2026-10-02 raporunda "spin toplam 286461.85 ms" vardı ama <b>oturum ne kadar
 * sürdü</b> yazmıyordu. Tek başına bu sayı anlamsız: aynı toplam 5 dakikalık
 * oturumda ağır, 30 dakikalık oturumda hafif görünür. Okuyucunun bölme yapması
 * gerekiyordu ve hiçbiri yapmadı — CPU payı gözden kaçtı.
 *
 * <p>İkinci eksik: koşular arası karşılaştırma. Üç ardışık koşuda CPU payı
 * %17,0 → %17,5 → %25,1 çıktı, kod değişmedi; değişen oynanan içerikti
 * (birinde gezinme, sonuncusunda maden). Toplamlar bu yüzden karşılaştırılamaz.
 *
 * <p>Üçüncü eksik: park çağrılarının CPU maliyeti hiç yazılmıyordu. Bu <em>ölçülemez</em>
 * (çekirdekte geçer), ama <b>tahmin</b> olarak yazılabilir. Tahmin olduğu de açıkça
 * belirtilmelidir; ölçülmüş gibi sunulursa yanlış güven üretir.
 */
class FpsSyncStatusReportContextTest {

    private static final long BUDGET_NS = 16_666_667L;

    /** Raporlanabilir uzunlukta, erken dönüşlü bir oturum üretir. */
    private static FramePacingRecorder session(long targetNs, int frames) {
        FramePacingRecorder r = new FramePacingRecorder();
        long perFrame = targetNs / frames;
        for (int i = 0; i < frames; i++) {
            r.recordFrame(perFrame, BUDGET_NS, true, 1, 9_000_000L, -7_000_000L, 1_000_000L,
                    2_000_000L);
        }
        return r;
    }

    private static String render(FramePacingRecorder r) {
        return FpsSyncStatusReport.render(new FpsSyncStatusReport.Snapshot(
                r, true, 60, 1366, 768, "1.2.0+1.21.1", SodiumSliderStatus.MIXIN_APPLIED, 0));
    }

    @Test
    @DisplayName("oturum süresi yazılır")
    void durationIsReported() {
        // ~20 dakikalık oturum
        String out = render(session(1_200_000_000_000L, 72_000));

        assertTrue(out.contains("süre"), "süre satırı yok");
        assertTrue(out.matches("(?s).*süre\\s+\\d+:\\d\\d.*"),
                "süre okunabilir biçimde olmalı");

        // Kare süreleri eşit bölünmediği için toplam tam 1200 sn değil, 1199,999 sn
        // olur. Tam sayı beklemek ölçümün kendisine değil, bölme artığına bağlı
        // olurdu; bir saniyelik payla yetiyor.
        long seconds = parseSeconds(out);
        assertTrue(Math.abs(seconds - 1200) <= 2,
                "saniye cinsinden süre ~1200 olmalı, çıktı: " + seconds + "\n" + out);
    }

    /** Raptordan "(NNN sn)" biçimindeki süreyi çıkarır. */
    private static long parseSeconds(String report) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(([0-9]+) sn\\)").matcher(report);
        assertTrue(m.find(), "saniye değeri '(' ve ')' içinde yazılmalı");
        return Long.parseLong(m.group(1));
    }

    @Test
    @DisplayName("spin, çekirdeğin yüzdesi olarak yazılır — toplam tek başına yetmez")
    void spinIsReportedAsARate() {
        String out = render(session(1_200_000_000_000L, 72_000));

        assertTrue(out.contains("spin"), "spin satırı yok");
        assertTrue(out.contains("çekirdeğin"), "spin çekirdek payı olarak verilmeli");
        assertTrue(out.matches("(?s).*çekirdeğin\\s+[\\d.]+%.*"),
                "yüzde biçimi hatalı, çıktı:\n" + out);
    }

    @Test
    @DisplayName("park maliyeti yazılır ve TAHMİN olduğu belirtilir")
    void parkCostIsLabelledAsAnEstimate() {
        String out = render(session(600_000_000_000L, 36_000));

        assertTrue(out.contains("park maliyeti"), "park maliyeti satırı yok");
        assertTrue(out.contains("TAHMİN"),
                "park maliyeti ölçülemez; tahmin olduğu yazılmalı");
    }

    @Test
    @DisplayName("toplam mod CPU'su tek satırda verilir")
    void totalModCpuIsSummarised() {
        String out = render(session(600_000_000_000L, 36_000));

        assertTrue(out.contains("modun CPU"),
                "spin + park toplamı okuyucuya bırakılmamalı");
    }

    @Test
    @DisplayName("ilk 10 dakika bloğu koşular arası karşılaştırma için yazılır")
    void earlyWindowIsReported() {
        String out = render(session(1_200_000_000_000L, 72_000));

        assertTrue(out.contains("İLK 10 DAKİKA"), "erken pencere bloğu yok");
        assertTrue(out.contains("karşılaştırma"),
                "bloğun neden var olduğu yazılmalı");
    }

    @Test
    @DisplayName("kısa oturumda uydurma pencere basılmaz")
    void shortSessionSaysSoExplicitly() {
        String out = render(session(300_000_000_000L, 18_000));

        assertTrue(out.contains("İLK 10 DAKİKA"), "başlık yine de görünmeli");
        assertTrue(out.contains("dolmadı"),
                "10 dk dolmadıysa bu açıkça söylenmeli, çıktı:\n" + out);
        assertFalse(out.contains("karşılaştırma için\n"),
                "doluymuş gibi bir pencere basılmamalı");
    }

    @Test
    @DisplayName("süre çok büyükse milisaniye yerine saniye yazılır")
    void largeDurationsUseSeconds() {
        String out = render(session(1_200_000_000_000L, 72_000));

        assertFalse(out.matches("(?s).*spin\\s+\\d{6,}\\.\\d+ ms.*"),
                "170262.56 ms gibi okunması zor toplamlar olmamalı");
    }
}