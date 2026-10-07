package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Window#swapBuffers()} süresi — GPU darboğaz sinyali.
 *
 * <h2>Neden</h2>
 * Bugüne kadar "kareler geç kaldı" diyorduk ve suçluyu sınırlayıcıda aradık. Ama geç
 * kalmanın iki ayrı sebebi olabilir ve bunları <em>ayırt edemiyorduk</em>:
 *
 * <ul>
 *   <li><b>CPU tarafı:</b> oyun iş parçacığı geç kaldı, sınırlayıcı düzeltemedi.</li>
 *   <li><b>GPU tarafı:</b> iş parçacığı zamanında bitirdi ama GPU kareyi hazırlayamadı.</li>
 * </ul>
 *
 * <p>V-Sync <b>kapalı</b>yken {@code glfwSwapBuffers} normalde birkaç yüz mikrosaniyede
 * döner. Kuyruk doluysa veya GPU yetişemiyorsa <b>bloklar</b>. Yani swap süresi
 * doğrudan "GPU bizi mi bekliyor" sorusunun cevabıdır:
 *
 * <pre>
 *   swap ≈ 200 µs   → GPU yetişiyor, geç kalmanın sebebi CPU/sınırlayıcı
 *   swap ≈ 16 ms    → swap <b>kare süresi kadar</b> blokluyor, GPU darboğaz
 *   swap ≈ 200 ms   → GPU belirgin şekilde geride
 * </pre>
 *
 * <h2>Kapsam</h2>
 * Yalnız <b>HEAD/RETURN</b> ölçümü. Hiçbir işlev değiştirilmez, swap çağrısına dokunulmaz.
 * V-Sync kapatma/zorlama yeteneği <b>bilerek yok</b>: Ahmet V-Sync'i kapalı tutuyor ve
 * Minecraft'ın kendi V-Sync'i bu makinede bozuk çalışıyor.
 */
class SwapTimeTest {

    private static final long BUDGET_NS = 1_000_000_000L / 60L;

    @Test
    @DisplayName("begin/end çifti bir swap ölçümü sayılır")
    void beginEndPairCountsOneSample() {
        SwapTimer timer = new SwapTimer();
        timer.begin(1_000L);
        timer.end(301_000L);

        assertEquals(1, timer.entries(), "bir çift = bir ölçüm");
        assertEquals(300_000L, timer.nsTotal(), "başlangıç ile bitiş arası");
        assertEquals(300_000L, timer.maxNs(), "tek ölçümde en kötü de aynısı");
    }

    @Test
    @DisplayName("başlangıçsız bitiş sayılmaz")
    void endWithoutBeginIsIgnored() {
        SwapTimer timer = new SwapTimer();
        timer.end(500_000L);

        assertEquals(0, timer.entries(), "başlangıç yoksa ölçüm uydurulamaz");
        assertEquals(0L, timer.nsTotal());
    }

    @Test
    @DisplayName("bitiş başlangıcı tüketir: ardışık bitişler sayılmaz")
    void endConsumesPendingStart() {
        // swapBuffers içinde istisna olursa HEAD/RETURN eşleşmesi bozulur ve
        // ikinci bir RETURN gelebilir. Ölçüm çift sayılırsa toplam şişer.
        SwapTimer timer = new SwapTimer();
        timer.begin(0L);
        timer.end(300_000L);
        timer.end(900_000L);   // yeni başlangıç YOK

        assertEquals(1, timer.entries(),
                "bitiş başlangıcı tükettiği için ikinci bitiş ölçüm saymamalı");
        assertEquals(300_000L, timer.nsTotal(), "toplam şişmemeli");
    }

    @Test
    @DisplayName("en kötü süre korunur, toplam eklenir")
    void maxAndTotalAreTracked() {
        SwapTimer timer = new SwapTimer();
        timer.begin(0L);
        timer.end(200_000L);
        timer.begin(0L);
        timer.end(16_000_000L);
        timer.begin(0L);
        timer.end(300_000L);

        assertEquals(3, timer.entries());
        assertEquals(16_500_000L, timer.nsTotal(), "üçü toplanır");
        assertEquals(16_000_000L, timer.maxNs(), "en kötü korunur");
    }

    @Test
    @DisplayName("yeni ölçüm başlayınca eski başlangıç unutulur")
    void beginResetsPending() {
        SwapTimer timer = new SwapTimer();
        timer.begin(0L);
        timer.begin(1_000L);      // başlangıç gelmeden yeni başlangıç
        timer.end(51_000L);

        assertEquals(1, timer.entries());
        assertEquals(50_000L, timer.nsTotal(), "ilk başlangıç sayılmamalı");
    }

    @Test
    @DisplayName("reset hem toplar hem sıfırlar")
    void resetCollectsAndClears() {
        SwapTimer timer = new SwapTimer();
        timer.begin(0L);
        timer.end(400_000L);

        long entries = timer.entries();
        long total = timer.nsTotal();

        timer.reset();

        assertEquals(1, entries, "reset'ten ÖNCE okunmalı");
        assertEquals(400_000L, total, "reset'ten ÖNCE okunmalı");
        assertEquals(0, timer.entries(), "reset sonrası sayaçlar sıfırlanmalı");
        assertEquals(0L, timer.nsTotal());
        assertEquals(0L, timer.maxNs());
    }

    @Test
    @DisplayName("reset bekleyen başlangıcı da düşürür")
    void resetDropsPendingStart() {
        SwapTimer timer = new SwapTimer();
        timer.begin(0L);
        timer.reset();
        timer.end(999_000L);

        assertEquals(0, timer.entries(),
                "reset'ten önce başlayan ama bitmemiş swap sayılmamalı");
    }

    @Test
    @DisplayName("reset ayırma yapmaz — ölçüm yolu sıfır ayak izlidir")
    void resetAllocatesNothing() {
        // Geri dönüşte bir Drain record'u döndürmek kare başına ayırma demekti;
        // ölçüm yolunun sıfır ayak izi kuralını bozuyordu.
        SwapTimer timer = new SwapTimer();
        Runnable cycle = () -> {
            for (int i = 0; i < 200_000; i++) {
                timer.begin(i);
                timer.end(i + 100L);
                timer.reset();
            }
        };

        long allocated = allocatedBytes(cycle);

        assertEquals(0L, allocated,
                "200.000 kez begin/end/reset ayırma yapmamalı, ayırdı: "
                        + allocated + " bayt");
    }

    private static long allocatedBytes(Runnable body) {
        // Runtime.totalMemory() farkı GC gürültüsü taşır; iş parçacığı başına ayrılan
        // bayt sayacı doğrudan ölçer. Desteklenmiyorsa sessizce geçmek yanlış olurdu:
        // kanıt üretilemez.
        java.lang.management.ThreadMXBean raw =
                java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(raw instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            throw new AssertionError(
                    "JVM iş parçacığı ayrımı ölçümü desteklemiyor; "
                            + "sıfır ayak izi kanıtlanamadı");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();

        // Isınma: JIT'in ilk turlarda farklı kararlar vermesin.
        for (int i = 0; i < 20; i++) {
            body.run();
        }
        long min = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long before = bean.getThreadAllocatedBytes(id);
            body.run();
            long delta = bean.getThreadAllocatedBytes(id) - before;
            if (delta < min) {
                min = delta;
            }
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    @Test
    @DisplayName("rapor swap satırını yazar")
    void reportPrintsSwapLine() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 600; i++) {
            // Kare başına doğru değerler: 1 ölçüm, ortalama 200 µs (%1,2).
            // ESKİ HALE bu satır 8_000_000/600/15_000_000 yazıyordu; bunlar kare
            // başına geçtiği için ortalama 13,3 µs'e düşüyor ve anlamsız oluyordu.
            // Yeni yüzde mantığı bu gizli hatayı açığa çıkardı.
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, BUDGET_NS - 100_000L,
                    96_000, 4_400, 0L, 0L, 0L, 0, 0, 0L, 200_000L, 1, 200_000L);
        }

        String text = FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.5.0", SodiumSliderStatus.MIXIN_APPLIED, 0));

        assertTrue(text.contains("swap"),
                "rapor swap satırını yazmalı, alınan:\n" + text);
        assertTrue(text.contains("200.0 µs"),
                "ortalama swap görünmeli, alınan:\n" + text);
        assertTrue(text.contains("1.2%"),
                "ortalama bütçe yüzdesi görünmeli, alınan:\n" + text);
    }

    @Test
    @DisplayName("TEK yüksek kare ortalamayı bozmaz — düzeltilen yanlış alarm")
    void oneBadFrameDoesNotRaiseBottleneck() {
        // 1.4.0'ın ilk koşusunda olan tam olarak bu: ortalama %9,8, en kötü %76,7.
        // Eşik en kötüye bakıyordu ve "GPU darboğazı olası" dedi. Ortalama %10 ise
        // sunum bedelidir, darboğaz değil.
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 600; i++) {
            boolean outlier = (i == 7);
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, BUDGET_NS - 100_000L,
                    96_000, 4_400, 0L, 0L, 0L, 0, 0, 0L,
                    outlier ? 12_780_000L : 1_500_000L, 1,
                    outlier ? 12_780_000L : 1_500_000L);
        }

        String text = FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.5.0", SodiumSliderStatus.MIXIN_APPLIED, 0));

        assertTrue(text.contains("12.78 ms"),
                "en kötü yine de görünmeli — bilgi kaybı yok, alınan:\n" + text);
        assertTrue(text.contains("darboğaz değil"),
                "ortalama %10 ise darboğaz denmemeli, alınan:\n" + text);
    }

    @Test
    @DisplayName("ölçüm yokken rapor çökmez")
    void reportSurvivesWithoutSwapSamples() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 600; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, BUDGET_NS - 100_000L,
                    96_000, 4_400);
        }

        String text = FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.3.0", SodiumSliderStatus.MIXIN_APPLIED, 0));

        assertTrue(text.contains("swap"), "satır yine de görünmeli");
        assertTrue(text.contains("ölçülmedi"),
                "ölçüm yokken 'ölçülmedi' demeli, alınan:\n" + text);
    }

    @Test
    @DisplayName("küçük swap süresi 'GPU etkisiz' der")
    void fastSwapSaysGpuIrrelevant() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 600; i++) {
            r.recordFrame(BUDGET_NS, BUDGET_NS, true, 1, BUDGET_NS - 100_000L,
                    96_000, 4_400, 0L, 0L, 0L, 0, 0, 0L, 120_000L, 1, 200_000L);
        }

        String text = FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.3.0", SodiumSliderStatus.MIXIN_APPLIED, 0));

        assertTrue(text.contains("GPU etkisiz"),
                "200 µs'lik swap bütçenin %1'i, darboğaz değildir, alınan:\n" + text);
    }
}