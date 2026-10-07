package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 1.6.0 düzeltmeleri: sayaç, boşta rejim ölçümü, oturum geçerliliği, etiketler.
 *
 * <h2>1 — Sayaç hatası: faydalı retry, denenen retry'den fazla olamaz</h2>
 * Gerçek bir koşuda rapor {@code 665/646 faydalı (%102,9)} dedi. Matematiksel olarak
 * imkânsız. Sebep: {@code retriedAfterFail} kare boyunca {@code true} kaldığı için
 * başarısızlıktan sonraki <em>her</em> başarılı çağrı sayılıyordu. Tek retry + üç
 * başarılı çağrı, sayaçta 1 deneme ve 3 fayda olarak görünüyordu.
 *
 * <p>Bu, daha önce okuduğumuz iki oranı da (<b>%81,6</b> ve <b>%75,3</b>) şişiriyordu.
 * Karar yine de doğru — spin ölçümü retry'in işe yaradığını gösteriyor — ama
 * <em>sayı</em> "retry ne kadar tuttu" değildi.
 *
 * <h2>2 — Boşta rejim ölçülemiyordu</h2>
 * Taban koşusunda (sınırsız) rapor tek satır veriyordu: ortalama kare süresi. Ama
 * {@code frameTime} histogramı her kare için, her iki rejimde de dolduruluyor —
 * erişimciler yalnız {@code active} okuyordu. Yani veri vardı, yazılmıyordu. Taban
 * koşusu karar vermeye yetmiyordu.
 *
 * <h2>3 — Karma oturum geçersiz, ama rapor bunu söylemiyordu</h2>
 * Gerçek bir koşu: ilk 25 saniye FPS Sync (oyun ~60 FPS), sonra sınırsız (oyun ~25 FPS).
 * {@code gerçek FPS 31,0} ikisinin ortalamasıydı ve hiçbir anlamı taşımıyordu.
 */
class HonestSessionTest {

    private static final long B = 1_000_000_000L / 60L;

    private static String render(FramePacingRecorder r) {
        return FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.6.0", SodiumSliderStatus.MIXIN_APPLIED, 0));
    }

    // ------------------------------------------------------------------ 1

    @Test
    @DisplayName("bir retry'dan sonra gelen çok sayıda çağrı tek fayda sayılır")
    void retriesSleptNeverExceedsCalls() {
        // Sanal saatle: 1. çağrı hiç uyumuyor, sonrakiler hep uyuyor.
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        final long start = 1_000_000L;
        final long deadline = start + B;
        long[] clock = {start};
        int[] call = {0};

        limiter.reset();
        limiter.resetFrameStats();
        limiter.nanoTime = () -> clock[0];
        limiter.sleeper = ns -> {
            int i = call[0]++;
            // İlk çağrı boşa dönsün, sonrakiler parça parça ilerlesin ki birden
            // fazla başarılı çağrı üretilsin.
            if (i == 0) {
                clock[0] += 1L;
            } else {
                clock[0] += Math.max(1L, ns / 4);
            }
        };
        limiter.spinHook = () -> { clock[0] = deadline; };
        limiter.onSpin = spin -> { };
        limiter.setEnabled(true);
        limiter.setMonitorRefreshRate(60);

        limiter.limitFrame();
        clock[0] += B / 2;
        limiter.limitFrame();

        assertTrue(limiter.retryAfterFailCalls >= 1, "en az bir retry denenmeli");
        assertEquals(limiter.retryAfterFailCalls, limiter.retryAfterFailSleptCalls,
                "faydalı sayısı denenen sayısını AŞAMAZ — karede tek retry vardı");
        assertTrue(limiter.parkCallsLastFrame > 2,
                "senaryoda birden fazla başarılı çağrı olmalı, çağrı: "
                        + limiter.parkCallsLastFrame);
    }

    @Test
    @DisplayName("rapor oranı tavanlamaz, olduğu gibi yazar")
    void reportNeverPrintsAboveHundred() {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        final long start = 1_000_000L;
        final long deadline = start + B;
        long[] clock = {start};
        int[] call = {0};
        limiter.reset();
        limiter.resetFrameStats();
        limiter.nanoTime = () -> clock[0];
        limiter.sleeper = ns -> {
            int i = call[0]++;
            if (i == 0) { clock[0] += 1L; } else { clock[0] += Math.max(1L, ns / 4); }
        };
        limiter.spinHook = () -> { clock[0] = deadline; };
        limiter.onSpin = spin -> { };
        limiter.setEnabled(true);
        limiter.setMonitorRefreshRate(60);
        limiter.limitFrame();
        clock[0] += B / 2;
        limiter.limitFrame();

        FramePacingRecorder r = new FramePacingRecorder();
        r.recordFrame(B, B, true, limiter.parkCallsLastFrame,
                limiter.parkRequestedNsLastFrame, limiter.parkOvershootNsLastFrame,
                limiter.spinNsLastFrame, limiter.parkElapsedNsLastFrame, 0L, 0L,
                limiter.retryAfterFailCalls, limiter.retryAfterFailSleptCalls,
                limiter.retryAfterFailSleptNs, 0L, 0, 0L);

        String text = render(r);

        assertTrue(!text.contains("102") && !text.contains("103"),
                "yüzde %100'ü geçmemeli, alınan:\n" + text);
    }

    // ------------------------------------------------------------------ 2

    @Test
    @DisplayName("boşta rejimin 1% low'u raporlanır")
    void idleRegimeReportsFpsLow() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            // Sınırlayıcı hiç çalışmadı: kareler 40 ms, bütçe 60 Hz'den gelir.
            r.recordFrame(40_000_000L, B, false, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        }

        assertEquals(40_000, r.totals().idleFrameTimeCount(),
                "boşta rejimin kare sayısı");
        assertTrue(r.idleFps1Low() > 0.0,
                "boşta rejim için 1% low hesaplanmalı, alınan: " + r.idleFps1Low());

        String text = render(r);

        assertTrue(text.contains("BEKLEME YAPILMAYAN"),
                "boşta rejim başlığı zaten var, alınan:\n" + text);
        assertTrue(text.contains("1% low"),
                "boşta rejimin de 1% low'u yazılmalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("boşta rejimde de swap çapraz tablosu yazılır")
    void idleRegimeReportsSwapCrossTab() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            boolean late = i % 100 < 60;
            // 30 ms de BÜTÇENİN ÜSTÜNDE (16,67 ms) olduğu için lateness pozitiftir
            // ve "zamanında" kare hiç oluşmuyordu. Sınırsız koşuda oyun bütçenin
            // altına da inebilir; on-time için 12 ms kullanıldı.
            r.recordFrame(late ? 45_000_000L : 12_000_000L, B, false, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, late ? 6_000_000L : 2_000_000L, 1,
                    late ? 6_000_000L : 2_000_000L);
        }

        assertTrue(r.totals().idleSwapLateEntries() > 0,
                "boşta rejimde de geç kare swap'ı toplanmalı");
        assertTrue(r.totals().idleSwapOnTimeEntries() > 0,
                "boşta rejimde de zamanında kare swap'ı toplanmalı");

        String text = render(r);

        int first = text.indexOf("BEKLEME YAPILMAYAN");
        String idleBlock = text.substring(first);
        assertTrue(idleBlock.contains("swap"),
                "boşta rejim bloğunda swap satırı olmalı, alınan:\n" + idleBlock);
    }

    // ------------------------------------------------------------------ 3

    @Test
    @DisplayName("oturumda durum değiştiyse rapor GEÇERSİZ OTURUM der")
    void mixedSessionIsDeclaredInvalid() {
        FramePacingRecorder r = new FramePacingRecorder();
        // 1496 bekleyen + 3264 boşta: gerçek karışık koşunun şekli.
        for (int i = 0; i < 1496; i++) {
            r.recordFrame(B, B, true, 1, B - 100_000L, 80_000L, 0L, B - 100_000L,
                    0L, 0L, 0, 0, 0L, 1_500_000L, 1, 1_500_000L);
        }
        for (int i = 0; i < 3264; i++) {
            r.recordFrame(40_000_000L, B, false, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0, 0, 0L, 3_000_000L, 1, 3_000_000L);
        }
        r.recordStateChange();      // slider oturumda bir kez değişti

        String text = render(r);

        assertTrue(text.contains("GEÇERSİZ OTURUM"),
                "karışık oturum raporda geçersiz ilan edilmeli, alınan:\n" + text);
        assertTrue(text.contains("1 kez"),
                "kaç kez değiştiği yazılmalı, alınan:\n" + text);
    }

    @Test
    @DisplayName("tek durumlu oturumda uyarı basılmaz")
    void pureSessionHasNoWarning() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            r.recordFrame(B, B, true, 1, B - 100_000L, 80_000L, 0L, B - 100_000L,
                    0L, 0L, 0, 0, 0L, 1_500_000L, 1, 1_500_000L);
        }

        String text = render(r);

        assertTrue(!text.contains("GEÇERSİZ OTURUM"),
                "tek durumlu oturumda uyarı basılmamalı, alınan:\n" + text);
    }

    // ------------------------------------------------------------------ 4

    @Test
    @DisplayName("başlık monitör hızını değil sınırlayıcının gerçek hedefini yazar")
    void headerShowsRealLimiterTarget() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            r.recordFrame(B, B, true, 1, B - 100_000L, 80_000L, 0L, B - 100_000L,
                    0L, 0L, 0, 0, 0L, 1_500_000L, 1, 1_500_000L);
        }

        // Elle 50 FPS sınırı, panel 60 Hz.
        String text = FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, false, 60, 1366, 768, "1.6.0",
                        true, 0, 50));

        assertTrue(text.contains("sınırlayıcı hedefi 50 Hz"),
                "sınırlayıcının gerçek hedefi yazılmalı, alınan:\n" + text);
        assertTrue(text.contains("panel 60 Hz"),
                "panel hızı AYRI ve etiketli görünmeli, alınan:\n" + text);
    }

    @Test
    @DisplayName("slider satırı gerçek enjeksiyon durumunu yazar")
    void sliderLineShowsRealInjectionState() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 40_000; i++) {
            r.recordFrame(B, B, true, 1, B - 100_000L, 80_000L, 0L, B - 100_000L,
                    0L, 0L, 0, 0, 0L, 1_500_000L, 1, 1_500_000L);
        }

// Sync kapalı ama slider GERÇEKTEN uygulanmamış: eski sürüm "var" derdi.
        String text = FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, false, 60, 1366, 768, "1.6.0",
                        SodiumSliderStatus.MIXIN_NOT_APPLIED, 0, 0));

        // 1.8.0: üç durum ayrışıyor. Arıza artık sessiz "uygulanmadı" değil,
        // kullanıcıya ne yapması gerektiğini söyleyen bir satır.
        assertTrue(text.contains("Sodium slider: UYGULANMADI"),
                "sync kapalı olması slider'ın var olduğu anlamına gelmez, alınan:\n" + text);
    }

    @Test
    @DisplayName("Sodium kurulu değilken slider satırı arıza gibi görünmemeli")
    void absentSodiumIsNotReportedAsFailure() {
        FramePacingRecorder r = new FramePacingRecorder();
        for (int i = 0; i < 100; i++) {
            r.recordFrame(B, B, true, 1, B - 100_000L, 80_000L, 0L, B - 100_000L,
                    0L, 0L, 0, 0, 0L, 1_500_000L, 1, 1_500_000L);
        }

        String text = FpsSyncStatusReport.render(
                new FpsSyncStatusReport.Snapshot(r, true, 60, 1366, 768, "1.8.0",
                        SodiumSliderStatus.SODIUM_ABSENT, 0, 60));

        String line = null;
        for (String candidate : text.split("\n")) {
            if (candidate.contains("Sodium slider")) {
                line = candidate;
            }
        }
        assertTrue(line != null && line.contains("gerekmiyor"),
                "Sodium yokken slider'ın görünmemesi beklenen durumdur, alınan:\n" + line);
        assertFalse(line.contains("UYGULANMADI"),
                "Sodium yokken arıza uyarısı yazmamalı:\n" + line);
    }
}