package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>1% low</b> ve <b>0,1% low</b> FPS — oyun endüstrisinin standart pürüzsüzlük ölçütü.
 *
 * <h2>Neden yeni metrik</h2>
 * Raporda {@code geç kare %37,5} yazıyordu. Bu bir <em>sayı</em> veriyor, <em>şiddet</em>
 * vermiyor: o kareler ne kadar geç kaldı? 1 ms'lik bir gecikme küçük bir tırtıklama,
 * 40 ms'lik bir gecikme gözle görülür duraklamadır — ikisi de "geç kare" sayılır.
 *
 * <p>1% low, en yavaş %1'lik dilimi ortalama FPS olarak verir ve bu farkı tek sayıda
 * özetler. Ahmet "bu sefer FPS daha iyi gibi geldi" dediğinde bakılacak yer burasıdır.
 *
 * <h2>Neden ayrı histogram</h2>
 * Mevcut {@code lateness} histogramı <b>yalnız geç kareleri</b> tutuyor; zamanında
 * kalanlar hiç girmiyor. Kare süresinin tüm dağılımı (zamanında olanlar dahil) gerektiği
 * için ayrı bir histogram gerekiyor. Toplam gecikme buna eklenerek türetilemez.
 *
 * <h2>Dürüstlük</h2>
 * Mevcut {@code percentile} kova <b>alt sınırını</b> döndürür. Gecikme için bu bir
 * tercihtir ("ölçemedim" ile "tam bu değer" ayrımını silmemek için). Ama bir <em>oran</em>
 * metriğinde kova alt sınırı kare süresini <b>eksik</b>, dolayısıyla FPS'i
 * <b>gerçekten yüksek</b> gösterirdi. Bu yüzden kare süresi yüzdelikleri kova
 * <b>orta noktasını</b> kullanır; sapma tek kova genişliğinde kalır.
 */
class FrameTimeLowTest {

    private static final long BUDGET_NS = 1_000_000_000L / 60L; // 16,67 ms

    /** Kare süresi histogramını doldurup erişimcileri döndüren kayıtçı. */
    private static FramePacingRecorder recorderWith(long... frameTimesNs) {
        FramePacingRecorder r = new FramePacingRecorder();
        for (long ft : frameTimesNs) {
            r.recordFrame(ft, BUDGET_NS, true, 1, BUDGET_NS - 100_000L, 0L,
                    Math.max(0L, BUDGET_NS - ft));
        }
        return r;
    }

    @Test
    @DisplayName("zamanında kalan kareler de histograma girer")
    void onTimeFramesAreCounted() {
        FramePacingRecorder r = recorderWith(BUDGET_NS, BUDGET_NS, BUDGET_NS);

        assertEquals(3, r.activeFrameTimeCount(),
                "geç olmayan kareler de kare süresi dağılımına ait");
        assertEquals(16L,
                r.activeFrameTimePercentileNs(0.50) / 1_000_000L,
                "medyan 16 ms civarı olmalı");
    }

    @Test
    @DisplayName("1% low yavaş karelerin hızını verir")
    void fps1LowReflectsSlowFrames() {
        // 1000 karenin 990'ı zamanında, 10'u 40 ms (çok yavaş).
        long[] frames = new long[1000];
        for (int i = 0; i < 990; i++) {
            frames[i] = BUDGET_NS;
        }
        for (int i = 990; i < 1000; i++) {
            frames[i] = 40_000_000L;
        }
        FramePacingRecorder r = recorderWith(frames);

        double fps1Low = r.activeFps1Low();
        // p99 ~ 16,7 ms tabanlı → 1% low 60 FPS'e yakın olmalı,
        // ama yavaş kuyruk biraz aşağı çekmeli.
        assertTrue(fps1Low > 55.0 && fps1Low < 60.0,
                "1% low 55–60 arasında olmalı, oldu: " + fps1Low);
    }

    @Test
    @DisplayName("daha fazla yavaş kare 1% low'u düşürür")
    void moreStutterLowersFps1Low() {
        long[] few = new long[1000];
        long[] many = new long[1000];
        for (int i = 0; i < 1000; i++) {
            // 1000'de 5 yavaş kare → p99 hâlâ temiz; 1000'de 50 yavaş → p99 yavaş.
            few[i] = (i >= 995) ? 40_000_000L : BUDGET_NS;
            many[i] = (i >= 950) ? 40_000_000L : BUDGET_NS;
        }
        FramePacingRecorder fewR = recorderWith(few);
        FramePacingRecorder manyR = recorderWith(many);

        assertTrue(fewR.activeFps1Low() > manyR.activeFps1Low(),
                "daha çok tırtıklama 1% low'u düşürmeli: az sırt "
                        + fewR.activeFps1Low() + " vs çok sırt " + manyR.activeFps1Low());
    }

    @Test
    @DisplayName("0,1% low 1% low'dan yavaştır")
    void fps01LowIsSlowerThanFps1Low() {
        // q=0,999 -> rank ceil(0,999·N). Aykırı karelerin bu rank'in ÜSTÜNDE olması
        // gerekir, yoksa yüzdelik onları yapısal olarak göremez. 10.000 örnekte
        // rank 9990; 15 yavaş kare 9985..10000 aralığında, yani görünür.
        int n = 10_000;
        int slow = 15;
        long[] frames = new long[n];
        for (int i = 0; i < n; i++) {
            frames[i] = (i >= n - slow) ? 100_000_000L : BUDGET_NS;
        }
        FramePacingRecorder r = recorderWith(frames);

        assertTrue(r.activeFps01Low() < r.activeFps1Low(),
                "yavaş kuyruk 0,1% low'u 1% low'un altına çekmeli: 1%="
                        + r.activeFps1Low() + " 0,1%=" + r.activeFps01Low());
    }

    @Test
    @DisplayName("yüzdelik rank'i kaçırmaz: az örnekte aykırı kare görünmez")
    void percentileRankCannotSeeRareOutlier() {
        // Dürüstlük sınırı: 1000 örnekte q=0,999 rank 999'dur, tek aykırı kare
        // rank 1000'de kalır. Yüzdelik onu GÖREMEZ — bu bir hata değil, tanımın
        // sonucu. Aykırı kare 60 ms seçildi ki 64 ms'lik ölçüm aralığının
        // İÇİNDE olsun: görünmezlik tek başına rank'tan gelsin, taşmadan değil.
        long[] frames = new long[1000];
        for (int i = 0; i < 999; i++) {
            frames[i] = BUDGET_NS;
        }
        frames[999] = 60_000_000L;

        FramePacingRecorder r = recorderWith(frames);

        assertEquals(0, r.activeFrameTimeOverflow(),
                "60 ms ölçüm aralığının içinde, taşma sayılmamalı");
        assertEquals(1000, r.activeFrameTimeCount(),
                "hepsi histogramda olmalı");
        assertTrue(r.activeFps01Low() > 59.0,
                "1000 örnekte tek aykırı kare 0,1% low'a giremez, alınan: "
                        + r.activeFps01Low());
    }

    @Test
    @DisplayName("yüzdelik kova alt sınırını değil orta noktasını verir")
    void percentileUsesBucketMidpoint() {
        // Tam 20 ms'lik 200 kare: kova [20,0 ms, +50 µs) aralığında.
        long[] frames = new long[200];
        for (int i = 0; i < 200; i++) {
            frames[i] = 20_000_000L;
        }
        FramePacingRecorder r = recorderWith(frames);

        long p50 = r.activeFrameTimePercentileNs(0.50);

        // Alt sınır 20,000 ms döndürürdü; orta nokta 20,025 ms döndürmeli.
        assertTrue(p50 >= 20_020_000L && p50 <= 20_030_000L,
                "kova orta noktası bekleniyordu, alınan: " + p50 + " ns");
    }

    @Test
    @DisplayName("taşma yüzdeyi iyimserleştirmez: ölçülemeyen kare varsa SATURATED")
    void overflowSaturates() {
        // 50 kare ölçüm aralığında, 50 kare 500 ms (aralığın çok dışında).
        // q=0,99 -> rank 99. Ölçülebilir kova sayısı 50, yani rank taşma bölgesinde
        // kalıyor. Yüzdelik çözülemezse SATURATED dönmeli; sessizce 16 ms deyip
        // "p99 = 16 ms" demek karelerin yarısının ölçülmediğini gizlerdi.
        long[] frames = new long[100];
        for (int i = 0; i < 50; i++) {
            frames[i] = BUDGET_NS;
        }
        for (int i = 50; i < 100; i++) {
            frames[i] = 500_000_000L;
        }
        FramePacingRecorder r = recorderWith(frames);

        assertEquals(FramePacingRecorder.SATURATED, r.activeFrameTimePercentileNs(0.99),
                "yarısı ölçülemiyorsa yüzdelik çözülemez");
        assertEquals(50, r.activeFrameTimeOverflow(),
                "tavan dışı sayacı ayrıca raporlanmalı");
    }

    @Test
    @DisplayName("taşma azınlıktayken yüzdelik yine çözülür")
    void smallOverflowStillResolves() {
        // 99 ölçülebilir + 1 taşma: q=0,99 -> rank 99, ölçülebilir kovaların sonunda.
        long[] frames = new long[100];
        for (int i = 0; i < 99; i++) {
            frames[i] = BUDGET_NS;
        }
        frames[99] = 500_000_000L;

        FramePacingRecorder r = recorderWith(frames);

        assertEquals(16L, r.activeFrameTimePercentileNs(0.99) / 1_000_000L,
                "azınlık taşma yüzdeliği bozmamalı");
        assertEquals(1, r.activeFrameTimeOverflow());
    }

    @Test
    @DisplayName("sıfırlama kare süresi histogramını temizler")
    void resetClearsFrameTime() {
        FramePacingRecorder r = recorderWith(BUDGET_NS, BUDGET_NS);
        assertEquals(2, r.activeFrameTimeCount(), "önce: dolu olmalı");

        r.reset();

        assertEquals(0, r.activeFrameTimeCount());
    }

    @Test
    @DisplayName("rapor 1% low satırını yazar")
    void reportPrintsFpsLow() {
        long[] frames = new long[1000];
        for (int i = 0; i < 950; i++) {
            frames[i] = BUDGET_NS;
        }
        for (int i = 950; i < 1000; i++) {
            frames[i] = 40_000_000L;
        }
        FramePacingRecorder r = recorderWith(frames);

        String text = FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.3.0", true, 0));

        assertTrue(text.contains("1% low"),
                "rapor 1% low yazmalı, alınan:\n" + text);
        assertTrue(text.contains("0.1% low"),
                "rapor 0.1% low yazmalı, alınan:\n" + text);
    }
}