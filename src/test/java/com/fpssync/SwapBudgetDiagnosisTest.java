package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Swap süresinin <b>bütçeye oranı</b> ve <b>geç kalma ile çapraz tablosu</b>.
 *
 * <h2>Sorun 1 — eşik yanlış yerdeydi (düzeltilen hata)</h2>
 * 1.4.0'ın ilk gerçek koşusunda rapor şunu dedi:
 * <pre>
 *   swap ortalama 1629,8 µs  ·  en kötü 12,78 ms  ·  GPU darboğazı olası
 * </pre>
 * Eşik <b>en kötü</b> değere konmuştu ve 12,78 ms tetikledi. Oysa ortalama
 * 1,63 ms, yani 16,67 ms bütçenin <b>%9,8'i</b>. Bir <b>tek aykırı kare</b> tüm
 * sistemik maliyeti gizledi ve "dur, sorun GPU" dedi. Gerçekte sunum bedeli
 * %10'dur; darboğaz değil.
 *
 * <p>Düzeltme: karar <b>ortalama</b>ya bakar, ve bütçeye oranla karşılaştırılır. En
 * kötü değer hâlâ raporlanır — bilgi kaybı yok, sadece karar dayanağı değişir.
 *
 * <h2>Sorun 2 — geç kalmanın CPU mu GPU mu olduğu ayırt edilemiyordu</h2>
 * Geç kalmanın iki ayrı sebebi vardır: iş parçacığı geç kaldı (CPU) ya da iş parçacığı
 * zamanında bitti ama GPU kareyi hazırlayamadı. Genel ortalama ikisini birbirine
 * karıştırır. Bu testler swap'ı <b>kare tipine göre ayrı</b> toplar:
 *
 * <pre>
 *   swap geç karelerde    ortalama X µs  (bütçenin %Y'si)
 *   swap zamanında        ortalama Z µs  (bütçenin %W'si)
 * </pre>
 *
 * <p>Geç karelerde swap belirgin yüksekse, geç kalmanın GPU tarafından açıklandığı
 * ölçülmüş olur — tahmin değil.
 */
class SwapBudgetDiagnosisTest {

    private static final long B = 1_000_000_000L / 60L;   // 16,67 ms

    /** Verilen kareler için kayıtçı kurar; swap süreleri kare tipine göre verilir. */
    private static FramePacingRecorder recorder(long budgetNs,
            long[] frameNs, long[] swapNs) {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < frameNs.length; i++) {
            long ft = frameNs[i];
            long spin = Math.max(0L, budgetNs - ft);
            r.recordFrame(ft, budgetNs, true, 1, budgetNs - 100_000L,
                    Math.max(0L, 80_000L - ft), spin,
                    budgetNs - 100_000L, 0L, 0L, 0, 0, 0L,
                    swapNs[i], 1, swapNs[i]);
        }
        return r;
    }

    private static FramePacingRecorder uniform(long budgetNs, long frameNs, long swapNs, int n) {
        long[] f = new long[n];
        long[] s = new long[n];
        for (int i = 0; i < n; i++) {
            f[i] = frameNs;
            s[i] = swapNs;
        }
        return recorder(budgetNs, f, s);
    }

    private static String render(FramePacingRecorder r) {
        return FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.5.0", SodiumSliderStatus.MIXIN_APPLIED, 0));
    }

    // ---------------------------------------------------------------- A

    @Test
    @DisplayName("1.4.0 koşusunun verisi artık darboğaz DEMEZ — regresyon testi")
    void realRunNoLongerRaisesFalseAlarm() {
        // Gerçek koşu: ortalama 1629,8 µs (%9,8), en kötü 12,78 ms (%76,7).
        FramePacingRecorder r = uniform(B, B + 3_900_000L, 1_629_800L, 400);

        String text = render(r);

        assertTrue(text.contains("darboğaz değil"),
                "ortalama bütçenin %10'u ise darboğaz denmemeli, alınan:\n" + text);
        assertTrue(!text.contains("GPU darboğazı olası"),
                "TEK aykırı kare (en kötü) kararı değiştirmemeli, alınan:\n" + text);
    }

    @Test
    @DisplayName("ortalama bütçe yüzdesiyle birlikte yazılır")
    void averageShownAsBudgetPercentage() {
        FramePacingRecorder r = uniform(B, B, 1_667_000L, 400);   // tam %10

        String text = render(r);

        // Parantez içi biçimle aranır: çapraz tablo da yüzde bastığı için gevşek bir
        // "10.0%" araması mutasyonu kaçırırdı. Ortalamanın kendi yüzdesi ayrı
        // doğrulanmalı.
        assertTrue(text.contains("(bütçenin 10.0%)"),
                "ortalamanın bütçe yüzdesi parantez içinde yazılmalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("en kötü değer de yazılır — bilgi kaybı yok")
    void maxIsStillReported() {
        FramePacingRecorder r = uniform(B, B, 1_000_000L, 100);

        String text = render(r);

        assertTrue(text.contains("en kötü"),
                "en kötü swap hâlâ raporlanmalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("ortalama düşükse 'GPU etkisiz' der")
    void tinyAverageSaysGpuIrrelevant() {
        FramePacingRecorder r = uniform(B, B, 200_000L, 400);    // %1,2

        String text = render(r);

        assertTrue(text.contains("GPU etkisiz"),
                "bütçenin %1'i GPU'yu hiç etkilemiyor, alınan:\n" + text);
    }

    @Test
    @DisplayName("ortalama yüksekse 'GPU darboğazı olası' der")
    void largeAverageRaisesBottleneck() {
        FramePacingRecorder r = uniform(B, B + 8_000_000L, 6_000_000L, 400);  // %36

        String text = render(r);

        assertTrue(text.contains("GPU darboğazı olası"),
                "ortalama bütçenin %36'sı ise darboğaz denmeli, alınan:\n" + text);
    }

    @Test
    @DisplayName("bütçe bilinmiyorsa yüzde yazılmaz ama satır bozulmaz")
    void missingBudgetDoesNotBreak() {
        FramePacingRecorder r = uniform(0L, B, 1_000_000L, 400);

        String text = render(r);

        assertTrue(text.contains("swap"), "satır yine de görünmeli");
        assertTrue(!text.contains("%NaN") && !text.contains("Infinity"),
                "NaN/Inf sızmamalı, alınan:\n" + text);
    }

    // ---------------------------------------------------------------- B

    @Test
    @DisplayName("geç karelerdeki swap ayrı toplanır")
    void lateFrameSwapIsTrackedSeparately() {
        long[] frames = new long[600];
        long[] swaps = new long[600];
        for (int i = 0; i < 600; i++) {
            frames[i] = B;              // zamanında
            swaps[i] = 1_000_000L;      // %6
        }
        for (int i = 0; i < 400; i++) {
            frames[i] = B + 5_000_000L; // GEÇ kare
            swaps[i] = 5_000_000L;      // %30
        }
        FramePacingRecorder r = recorder(B, frames, swaps);

        assertEquals(400, r.activeSwapLateEntries(), "geç kare sayısı");
        assertEquals(200, r.activeSwapOnTimeEntries(), "zamanında kare sayısı");
        assertEquals(5_000_000L, r.activeSwapLateNsTotal() / 400, "geç karelerde ortalama");
        assertEquals(1_000_000L, r.activeSwapOnTimeNsTotal() / 200, "zamanında ortalama");
    }

    @Test
    @DisplayName("rapor çapraz tabloyu iki taraflı yazar")
    void reportPrintsCrossTab() {
        long[] frames = new long[600];
        long[] swaps = new long[600];
        for (int i = 0; i < 600; i++) {
            frames[i] = B;
            swaps[i] = 1_000_000L;
        }
        for (int i = 0; i < 400; i++) {
            frames[i] = B + 5_000_000L;
            swaps[i] = 5_000_000L;
        }
        FramePacingRecorder r = recorder(B, frames, swaps);

        String text = render(r);

        assertTrue(text.contains("geç karelerde"),
                "geç karelerdeki swap yazılmalı, alınan:\n" + text);
        assertTrue(text.contains("zamanında"),
                "zamanındakiler de yazılmalı — karşılaştırma için, alınan:\n" + text);
    }

    @Test
    @DisplayName("geç karelerde swap yüksekse GPU'yu doğrular")
    void highLateSwapConfirmsGpu() {
        long[] frames = new long[600];
        long[] swaps = new long[600];
        for (int i = 0; i < 600; i++) {
            frames[i] = B;
            swaps[i] = 1_000_000L;              // %6
        }
        for (int i = 0; i < 400; i++) {
            frames[i] = B + 5_000_000L;
            swaps[i] = 5_000_000L;              // %30
        }
        FramePacingRecorder r = recorder(B, frames, swaps);

        String text = render(r);

        assertTrue(text.contains("GPU geç kareleri açıklıyor"),
                "geç karelerde swap 5 kat yüksekse GPU doğrulanmalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("geç karelerde swap yüksek değilse GPU'yu suçlamaz")
    void lowLateSwapDoesNotBlameGpu() {
        // Geç ve zamanında kareler bir arada ama swap neredeyse aynı (%6 vs %6,5).
        // Geç kalma sunumdan değil; hüküm GPU'yu görmemeli.
        long[] frames = new long[600];
        long[] swaps = new long[600];
        for (int i = 0; i < 600; i++) {
            frames[i] = B;              // zamanında
            swaps[i] = 1_000_000L;
        }
        for (int i = 0; i < 300; i++) {
            frames[i] = B + 5_000_000L; // GEÇ
            swaps[i] = 1_080_000L;      // neredeyse aynı
        }
        FramePacingRecorder r = recorder(B, frames, swaps);

        String text = render(r);

        assertTrue(text.contains("geç kalma GPU'dan değil"),
                "çapraz tablo eşitse GPU'yu suçlamamak gerekir, alınan:\n" + text);
        assertTrue(!text.contains("GPU geç kareleri açıklıyor"),
                "tersi de yazılmamalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("tek taraflı veride çapraz tablo uydurma oran yazmaz")
    void oneSidedDataPrintsNoRatio() {
        // Hep geç kare → "zamanında" tarafı boş. Oran yazmak yanlış olurdu.
        FramePacingRecorder r = uniform(B, B + 5_000_000L, 1_000_000L, 600);

        String text = render(r);

        assertTrue(text.contains("zamanında"),
                "boş taraf da görünmeli ki eksik olduğu belli olsun, alınan:\n" + text);
    }

    @Test
    @DisplayName("sıfırlama çapraz tabloyu da temizler")
    void resetClearsCrossTab() {
        FramePacingRecorder r = uniform(B, B + 5_000_000L, 1_000_000L, 600);
        assertEquals(600, r.activeSwapLateEntries(), "önce: dolu olmalı");

        r.reset();

        assertEquals(0, r.activeSwapLateEntries());
        assertEquals(0, r.activeSwapOnTimeEntries());
    }

    // ------------------------------------------------- çökme yolu (bulundu)

    @Test
    @DisplayName("park hiç erken dönmezse rapor ÇÖKMEZ")
    void reportSurvivesWhenParkNeverReturnsEarly() {
        // Gerçek hata: 10 dakikalık blok "erken dönüş" satırında
        // parkEarlyNsTotal / parkEarlyCalls yapıyordu, ama koruması yalnız
        // parkCalls > 0 idi. Park hiç erken dönmezse parkEarlyCalls == 0 olur ve
        // ArithmeticException atılırdı.
        //
        // Bu bir kenar durum değil: 1.4.0'dan SONRA tam olarak beklenen durum.
        // Retry park'ın erken dönüşünü düzeltiyor, yani sağlıklı koşularda erken
        // dönüş sayısı sıfıra yaklaşır ve rapor çöker.
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            r.recordFrame(B, B, true, 1, B - 100_000L,
                    80_000L,                    // aşım POZİTİF → erken dönüş YOK
                    0L, B - 100_000L, 0L, 0L, 0, 0, 0L,
                    200_000L, 1, 200_000L);
        }

        assertEquals(0, r.totals().parkEarlyCalls(),
                "önce koşul: park hiç erken dönmemiş olmalı");

        String text = render(r);      // çökerse test kırılır

        assertTrue(text.contains("İLK 10 DAKİKA"),
                "10 dakikalık blok yine de yazılmalı, alınan:\n" + text);
        // Etiket boşluğuyla aranır: "0 erken dönüş sayılmadı" ifadesi her zaman
        // bulunur ve gevşek bir arama yanlış tetiklenirdi.
        assertTrue(!text.contains("erken dönüş        "),
                "erken dönüş yokken 10 dakikalık o satır yazılmamalı, alınan:\n" + text);
        assertTrue(text.contains("0 erken dönüş sayılmadı"),
                "dağıtım satırı sayının sıfır olduğunu doğrulamalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("erken dönüş varken 10 dakikalık blok erken dönüşü yine yazar")
    void reportPrintsEarlyReturnWhenPresent() {
        FramePacingRecorder r = uniform(B, B + 3_000_000L, 1_000_000L, 40_000);

        String text = render(r);

        assertTrue(text.contains("erken dönüş"),
                "erken dönüş varsa satır yazılmalı, alınan:\n" + text);
    }

    // -------------------- 10 dakikalık bloğun payda korumaları

    @Test
    @DisplayName("sınırlayıcı hiç devrede olmadıysa rapor ÇÖKMEZ")
    void reportSurvivesWhenLimiterNeverWaited() {
        // İkinci bölme yolu: 10 dakikalık blok "geç kare" için lateFrames /
        // waitingFrames yapıyordu, koruması yoktu. Oyun 60'a hiç ulaşamazsa
        // waitingFrames == 0 olur ve ArithmeticException atılırdı.
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            r.recordFrame(37_000_000L, B, false, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 1_000_000L, 1, 1_000_000L);
        }

        assertEquals(0, r.totals().waitingFrames(),
                "önce koşul: sınırlayıcı hiç beklememiş olmalı");

        String text = render(r);      // çökerse test kırılır

        assertTrue(text.contains("İLK 10 DAKİKA"), "blok yazılmalı");
        assertTrue(text.contains("limiter devrede 0.0%"),
                "sınırlayıcının hiç çalışmadığı görünmeli, alınan:\n" + text);
    }

    // -------------------- çapraz tablo eşikleri

    @Test
    @DisplayName("oran yüksek ama geç karelerdeki pay küçükse GPU'yu suçlamaz")
    void highRatioButSmallShareDoesNotBlameGpu() {
        // Geç karelerde swap 3 kat (oran geçer) ama payı %9 — %15 eşiğinin altında.
        // Yalnız orana bakılırsa yanlışlıkla GPU'yu suçlardı.
        long[] frames = new long[600];
        long[] swaps = new long[600];
        for (int i = 0; i < 600; i++) {
            frames[i] = B;
            swaps[i] = 500_000L;              // %3,0
        }
        for (int i = 0; i < 300; i++) {
            frames[i] = B + 5_000_000L;
            swaps[i] = 1_500_000L;            // %9,0  → oran 3, pay %9
        }
        FramePacingRecorder r = recorder(B, frames, swaps);

        String text = render(r);

        assertTrue(text.contains("geç kalma GPU'dan değil"),
                "oran 3 ama pay %9 ise GPU'yu suçlamak ölçümü aşar, alınan:\n" + text);
    }

    @Test
    @DisplayName("çapraz tablo iki tarafı karıştırmaz")
    void crossTabDoesNotSwapSides() {
        // Asimetrik ve güçlü: geç karelerde %48, zamanında %3.
        // Yanlışlıkla taraflar yer değiştirirse hüküm tamamen ters döner.
        long[] frames = new long[600];
        long[] swaps = new long[600];
        for (int i = 0; i < 600; i++) {
            frames[i] = B;
            swaps[i] = 500_000L;              // %3,0
        }
        for (int i = 0; i < 500; i++) {
            frames[i] = B + 6_000_000L;
            swaps[i] = 8_000_000L;            // %48,0
        }
        FramePacingRecorder r = recorder(B, frames, swaps);

        String text = render(r);

        assertTrue(text.contains("geç karelerde 8000.0 µs"),
                "yüksek değer geç kareler altında olmalı, alınan:\n" + text);
        assertTrue(text.contains("zamanında 500.0 µs"),
                "düşük değer zamanında altında olmalı, alınan:\n" + text);
        assertTrue(text.contains("GPU geç kareleri açıklıyor"),
                "%48 vs %3 → GPU doğrulanmalı, alınan:\n" + text);
    }
}