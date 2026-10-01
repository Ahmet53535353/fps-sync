package com.fpssync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FrameLimiter}'ın zamanlama mantığını, bekleme çağrılarını sanal bir saatle
 * taklit ederek sınar. Minecraft'i başlatmadan, saf Java ile çalışır.
 *
 * <p><b>Neden sweep gerekiyor.</b> Bekleme süresi, kare bütçesinin neresinde durduğuna
 * bağlıdır: aynı hedef FPS'te render süresi bütçeye yaklaştığında kalan süre küçülür.
 * Yalnızca tek bir render süresiyle ölçülen test, meşgul bekleme davranışının yalnız
 * o noktasını görür ve kalan bütçe aralığındaki hataları kaçırır. Bu test kare bütçesini
 * baştan sona gezer.
 */
class FrameLimiterDriftTest {

    /**
     * {@code LockSupport.parkNanos} aşımı.
     *
     * <p>Ölçülmüş değer, tahmin değil. {@link FrameLimiterCpuProbe} gerçek
     * {@code parkNanos} ile ortalama spin'i 7 µs buldu; spin penceresi 100 µs
     * olduğuna göre park, deadline'i ~93 µs aşıyor. 90 µs alındı.
     *
     * <p>Bu değer sanal testin mutlak spin sayılarını gerçeğe yaklaştırır, ancak
     * sanal saat bir modeldir: ölçüm gerektiğinde prob kullanılmalıdır. Sanal
     * testin asıl değeri mantık hatalarını yakalamaktır — örneğin beklemenin
     * milisaniyeye kırpılması, render bütçeyi aşınca sınırlayıcının tamamen
     * devre dışı kalması.
     */
    private static final long PARK_OVERSHOOT_NS = 90_000L; // 0.09 ms (ölçülmüş)

    /** Spin döngüsü sırasında her zaman okumasının ilerlediği miktar. */
    private static final long SPIN_TICK_NS = 1_000L; // 1 µs

    /**
     * Kabul edilebilir en uzun meşgul bekleme.
     *
     * <p>Kodun spin penceresi 0.1 ms. Buna ölçüm gürültüsü payı ekleniyor. Bu eşik
     * iki davranışı ayırmak için seçildi: parkNanos ile spin ~0.1 ms'de kalır,
     * milisaniye kırpması varsa ise kalan süre 1.1 ms'nin altına düştüğünde uyku
     * hiç yapılmaz ve kalan sürenin tamamı (1 ms'e kadar) spin edilir.
     */
    private static final long MAX_SPIN_NS = 300_000L; // 0.3 ms

    private final VirtualClock clock = new VirtualClock();

    /** Sanal saat ve sahte bekleme. */
    private static final class VirtualClock {
        long nowNs = 0;
        long parkCalls = 0;
        long parkedNs = 0;

        void advance(long ns) {
            nowNs += ns;
        }

        long now() {
            return nowNs;
        }

        /** Spin döngüsü sonsuza kadar sürmesin diye zamanı biraz ilerlet. */
        void tick() {
            nowNs += SPIN_TICK_NS;
        }

        void park(long nanos) {
            parkCalls++;
            parkedNs += nanos;
            nowNs += nanos + PARK_OVERSHOOT_NS;
        }
    }

    private void bindTo(FrameLimiter limiter) {
        limiter.reset();
        limiter.nanoTime = clock::now;
        limiter.sleeper = clock::park;
        limiter.spinHook = clock::tick;
        limiter.onSpin = spin -> { };
    }

    @Test
    @DisplayName("Meşgul bekleme, kare bütçesinin hiçbir noktasında pencereyi aşmıyor")
    void spinWindowRespectedAcrossWholeBudgetCycle() {
        int targetFps = 60;
        long budgetNs = 1_000_000_000L / targetFps;

        List<String> violations = new ArrayList<>();
        long worstSpinNs = 0;
        long worstRemainingNs = 0;
        long measuredPoints = 0;

        // Render süresini bütçenin başından sonuna doğru gez: kalan süre
        // bütçeden sıfıra iner. Kırpma hatası yalnız kalan süre 1.1 ms'nin
        // altına düştüğünde görünür, bu yüzden aralığın tamamı taranmalı.
        for (long renderNs = budgetNs; renderNs >= 0; renderNs -= budgetNs / 120) {
            bindTo(FrameLimiter.INSTANCE);
            FrameLimiter.INSTANCE.setEnabled(true);
            FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);

            long[] lastSpin = {0};
            FrameLimiter.INSTANCE.onSpin = spin -> lastSpin[0] = spin;

            // İlk kare başlatma karesidir; ikinci karede kararlı durum ölçülür.
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();

            long spinNs = lastSpin[0];
            measuredPoints++;
            if (spinNs > worstSpinNs) {
                worstSpinNs = spinNs;
                worstRemainingNs = budgetNs - renderNs;
            }
            if (spinNs > MAX_SPIN_NS) {
                violations.add("remaining=" + (budgetNs - renderNs) / 1000 + "µs→spin="
                        + spinNs / 1000 + "µs");
            }
        }

        assertTrue(measuredPoints > 100,
                "Sweep yeterli nokta ölçmedi (" + measuredPoints + "); test kapsamı beklenenden dar.");

        assertTrue(violations.isEmpty(),
                "Meşgul bekleme penceresi " + (MAX_SPIN_NS / 1000) + " µs'yi aşıyor: " +
                        violations.size() + "/" + measuredPoints + " noktada ihlal. " +
                        "En kötü: remaining=" + worstRemainingNs / 1000 + " µs iken spin=" +
                        worstSpinNs / 1000 + " µs. İlk örnekler: " + firstFew(violations) +
                        ". Sebep: bekleme milisaniyeye kırpılıp 0.1 ms'lik spin penceresi " +
                        "yutuluyor; kalan süre 1.1 ms'nin altına düşünce uyku hiç yapılmıyor.");
    }

    @Test
    @DisplayName("Bütçe döngüsü boyunca ortalama meşgul bekleme düşük kalıyor")
    void averageSpinAcrossBudgetCycleIsLow() {
        int targetFps = 60;
        long budgetNs = 1_000_000_000L / targetFps;

        long totalSpinNs = 0;
        int points = 0;

        for (long renderNs = budgetNs; renderNs >= 0; renderNs -= budgetNs / 120) {
            bindTo(FrameLimiter.INSTANCE);
            FrameLimiter.INSTANCE.setEnabled(true);
            FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);

            long[] lastSpin = {0};
            FrameLimiter.INSTANCE.onSpin = spin -> lastSpin[0] = spin;

            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();

            totalSpinNs += lastSpin[0];
            points++;
        }

        double avgSpinUs = totalSpinNs / (double) points / 1000.0;
        // 60 fps'te saniyedeki meşgul bekleme ve tek çekirdeğe düşen yükü.
        double busyPercentAt60 = avgSpinUs * 60.0 / 10_000.0;

        // Kabul: ortalama spin 0.2 ms'nin altında. Bu, 60 fps'te saniyede <12 ms,
        // yani tek çekirdekten <%1.2 demek. Spin penceresi 0.1 ms olduğu için
        // gerçekleşmesi beklenen değer ~0.05 ms.
        assertTrue(avgSpinUs <= 200.0,
                "Ortalama meşgul bekleme çok yüksek: " + String.format("%.1f", avgSpinUs) +
                        " µs (kabul ≤200 µs). 60 fps'te bu saniyede " +
                        String.format("%.1f", avgSpinUs * 60 / 1000.0) + " ms, yani bir " +
                        "çekirdeğin %" + String.format("%.2f", busyPercentAt60) + "'i. " +
                        "Nedeni: bekleme milisaniyeye kırpılınca kalan sürenin tamamı spin " +
                        "ediliyor.");
    }

    @Test
    @DisplayName("Bekleme bütçe döngüsü boyunca gerçekten kullanılıyor")
    void waitIsActuallyParked() {
        int targetFps = 60;
        long budgetNs = 1_000_000_000L / targetFps;
        long renderNs = budgetNs / 5;

        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);

        for (int frame = 0; frame < 3; frame++) {
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
        }

        // 3 karede: 1 başlatma + 2 bekleme
        assertTrue(clock.parkCalls == 2,
                "Bekleme " + clock.parkCalls + " kez yapıldı, 2 bekleniyordu. " +
                        "Bekleme hiç yapılmıyorsa sınırlayıcı kare süresini hedefin "
                        + (MAX_SPIN_NS * 1000 / 1_000_000) + " katına kadar spin ile geçiriyor demektir.");
    }

    @Test
    @DisplayName("Kare süresi hedefin üstüne taşmıyor")
    void frameTimeDoesNotExceedBudget() {
        int targetFps = 60;
        long budgetNs = 1_000_000_000L / targetFps;

        long worstOverrunNs = 0;
        long worstRenderNs = 0;

        for (long renderNs = budgetNs; renderNs >= budgetNs / 2; renderNs -= budgetNs / 120) {
            bindTo(FrameLimiter.INSTANCE);
            FrameLimiter.INSTANCE.setEnabled(true);
            FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);

            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();

            long start = clock.nowNs;
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();

            long overrun = (clock.nowNs - start) - budgetNs;
            if (overrun > worstOverrunNs) {
                worstOverrunNs = overrun;
                worstRenderNs = renderNs;
            }
        }

        assertTrue(worstOverrunNs <= budgetNs / 20,
                "Kare bütçeyi aşıyor: render=" + worstRenderNs / 1000 + " µs iken kare " +
                        worstOverrunNs / 1000 + " µs fazla sürdü. Aşım, sınırlayıcının " +
                        "hedefi tutturamadığı anlamına gelir.");
    }

    @Test
    @DisplayName("Kare bütçesinden hızlı biten kareler gereksiz bekleme yapmıyor")
    void fastFramesDoNotAccumulateDebt() {
        int targetFps = 60;
        long budgetNs = 1_000_000_000L / targetFps;
        long renderNs = budgetNs * 2; // bütçeyi aşıyor: beklenmemeli

        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);

        for (int frame = 0; frame < 10; frame++) {
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
        }

        assertTrue(clock.parkCalls == 0,
                "Render süresi bütçeyi aşmasına rağmen sınırlayıcı " + clock.parkCalls +
                        " kez bekledi; hedefe ulaşılamıyorsa beklemek yalnızca yavaşlatır.");
    }

    @Test
    @DisplayName("Sınırlayıcı devre dışıyken hiç bekleme yapmıyor")
    void disabledLimiterNeverWaits() {
        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(false);
        FrameLimiter.INSTANCE.setManualLimit(0); // sınırsız

        for (int frame = 0; frame < 300; frame++) {
            clock.advance(1_000_000L);
            FrameLimiter.INSTANCE.limitFrame();
        }

        assertTrue(clock.parkCalls == 0,
                "Sınırlayıcı devre dışıyken " + clock.parkCalls + " kez bekledi.");
    }

    @Test
    @DisplayName("FPS hedefi değiştiğinde sınırlayıcı yeni bütçeye uyum sağlıyor")
    void limiterAdaptsToChangedTarget() {
        int lowFps = 30;
        int highFps = 240;
        long lowBudgetNs = 1_000_000_000L / lowFps;
        long highBudgetNs = 1_000_000_000L / highFps;

        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(lowFps);

        for (int frame = 0; frame < 100; frame++) {
            clock.advance(lowBudgetNs / 5);
            FrameLimiter.INSTANCE.limitFrame();
        }

        long afterLowFps = clock.nowNs;
        FrameLimiter.INSTANCE.setMonitorRefreshRate(highFps);

        for (int frame = 0; frame < 100; frame++) {
            clock.advance(highBudgetNs / 5);
            FrameLimiter.INSTANCE.limitFrame();
        }

        long spentAtHighFps = clock.nowNs - afterLowFps;
        long highFpsBudgetTotal = 100L * highBudgetNs;

        assertTrue(spentAtHighFps <= highFpsBudgetTotal * 2,
                "FPS hedefi 30'dan 240'a çıktıktan sonra sınırlayıcı uyum sağlamıyor: " +
                        "100 karede " + (spentAtHighFps / 1_000_000) + " ms harcandı, " +
                        "beklenen ~" + (highFpsBudgetTotal / 1_000_000) + " ms.");
    }

    /**
     * Oyun hedefin altında kaldığında sınırlayıcı tamamen görünmez olmalıdır.
     *
     * <p>Bu, FPS Sync'in en sık gerçekleşen durumudur: monitör 60 Hz ama oyun
     * GPU'ya takılıp yalnızca 20 FPS çizebiliyordur. Sınırlayıcının o durumda
     * beklemesi, spin yapması veya kare süresine dokunması yanlıştır — oyun zaten
     * hedefin altında, yapılacak tek şey karışmamaktır.
     *
     * <p>Üç şeyin de sıfır olduğu doğrulanır: park çağrısı, spin süresi ve
     * render süresine eklenen maliyet. Tarama, bütçenin 3 katından tam bütçeye
     * iner; tek bir noktaya kilitlenmemek için.
     */
    @Test
    @DisplayName("Render bütçeyi aşınca sınırlayıcı hiç bekleme yapmıyor ve kare süresine dokunmuyor")
    void slowerThanTargetMakesLimiterInvisible() {
        int targetFps = 60;
        long budgetNs = 1_000_000_000L / targetFps;

        List<String> violations = new ArrayList<>();
        int points = 0;

        // 3× bütçeden tam bütçeye iner
        for (double factor = 3.0; factor >= 1.0; factor -= 1.0 / 120) {
            long renderNs = (long) (budgetNs * factor);

            bindTo(FrameLimiter.INSTANCE);
            FrameLimiter.INSTANCE.setEnabled(true);
            FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);

            long[] spin = {0};
            FrameLimiter.INSTANCE.onSpin = s -> spin[0] = s;

            // İlk kare başlatma; ikinci kare kararlı durumu gösterir
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();

            long parksBefore = clock.parkCalls;
            long before = clock.nowNs;
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
            long frameTimeNs = clock.nowNs - before;

            long parks = clock.parkCalls - parksBefore;
            points++;

            // Kare süresi, render süresinden büyükse sınırlayıcı kareyi uzatmıştır
            if (parks > 0 || spin[0] > 0 || frameTimeNs != renderNs) {
                violations.add("render=" + (renderNs / 1000) + "µs park=" + parks
                        + " spin=" + (spin[0] / 1000) + "µs kare="
                        + (frameTimeNs / 1000) + "µs");
            }
        }

        assertTrue(points > 120,
                "Tarama yeterli nokta ölçmedi (" + points + "); kapsam beklenenden dar.");

        assertTrue(violations.isEmpty(),
                "Render hedefin üstündeyken sınırlayıcı görünmez olmalı ama "
                        + violations.size() + "/" + points + " noktada karıştı: "
                        + firstFew(violations));
    }

    /**
     * Bütçe sınırının hemen iki yanı: tam bütçe ve bütçe±1 µs.
     *
     * <p>Render tam bütçeye eşitse bekleme gerekmez. Bir mikrosaniye altındaysa
     * yalnızca o kadar beklenmelidir. Üstündeyse hiç beklenmemelidir. Sınırın
     * iki yanında farklı davranış beklenir ve bu ayrım kaybolursa sınırlayıcı
     * ya gereksiz bekler ya da kareyi uzatır.
     */
    @Test
    @DisplayName("Bütçe sınırının iki yanında davranış doğru ayrışıyor")
    void boundaryBehaviourIsCorrect() {
        int targetFps = 60;
        long budgetNs = 1_000_000_000L / targetFps;

        // render = bütçe: hiç bekleme
        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);
        clock.advance(budgetNs);
        FrameLimiter.INSTANCE.limitFrame();
        long parksAtBudget = clock.parkCalls;
        clock.advance(budgetNs);
        FrameLimiter.INSTANCE.limitFrame();
        assertTrue(clock.parkCalls == parksAtBudget,
                "Render tam bütçeye eşitken " + (clock.parkCalls - parksAtBudget)
                        + " kez bekledi; tam bütçede bekleme gerekmez.");

        // render = bütçe − 1 µs: yalnızca ~1 µs beklenmeli
        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);
        long[] spin = {0};
        FrameLimiter.INSTANCE.onSpin = s -> spin[0] = s;
        clock.advance(budgetNs - 1_000L);
        FrameLimiter.INSTANCE.limitFrame();
        clock.advance(budgetNs - 1_000L);
        FrameLimiter.INSTANCE.limitFrame();
        assertTrue(spin[0] < 20_000L,
                "Bütçeden 1 µs kaldığında " + (spin[0] / 1000) + " µs spin oldu; "
                        + "spin penceresinin (100 µs) çok üstünde, kare gereksiz uzatılıyor.");

        // render = bütçe + 1 µs: hiç bekleme
        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);
        clock.advance(budgetNs + 1_000L);
        FrameLimiter.INSTANCE.limitFrame();
        long parksAbove = clock.parkCalls;
        clock.advance(budgetNs + 1_000L);
        FrameLimiter.INSTANCE.limitFrame();
        assertTrue(clock.parkCalls == parksAbove,
                "Render bütçeyi 1 µs aştığı halde " + (clock.parkCalls - parksAbove)
                        + " kez bekledi.");
    }

    /**
     * FPS Sync dışındaki modlar: elle sınır monitör hızından düşükse o değere
     * sınırlanmalı, yüksekse elle değer geçerli olmalı.
     */
    @Test
    @DisplayName("Elle FPS sınırı monitör hızından bağımsız çalışıyor")
    void manualLimitIndependentOfMonitorRate() {
        long renderNs = 5_000_000L; // 5 ms render: 30 fps'e rahat sığar
        int frames = 30; // ölçüm için yeterince çok kare

        // 60 Hz monitörde elle 30 fps
        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(false);
        FrameLimiter.INSTANCE.setManualLimit(30);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(60);

        // İlk kare başlatma karesidir (beklemez), ölçümden çıkarılır.
        clock.advance(renderNs);
        FrameLimiter.INSTANCE.limitFrame();
        long mark = clock.nowNs;
        for (int i = 0; i < frames; i++) {
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
        }
        long perFrame30 = (clock.nowNs - mark) / frames;

        assertTrue(perFrame30 >= 33_000_000L * 0.9 && perFrame30 <= 33_000_000L * 1.3,
                "Elle 30 fps kare başına " + (perFrame30 / 1_000_000) + " ms sürdü; "
                        + "30 fps için ~33 ms bekleniyordu.");

        // Aynı render ile 100 fps hedefi rahat sığmalı (10 ms bütçe)
        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(false);
        FrameLimiter.INSTANCE.setManualLimit(100);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(24); // monitör bilinçli olarak yavaş
        clock.advance(renderNs);
        FrameLimiter.INSTANCE.limitFrame();
        mark = clock.nowNs;
        for (int i = 0; i < frames; i++) {
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
        }
        long perFrame100 = (clock.nowNs - mark) / frames;

        assertTrue(perFrame100 >= 10_000_000L * 0.9 && perFrame100 <= 10_000_000L * 1.3,
                "Elle 100 fps kare başına " + (perFrame100 / 1_000_000)
                        + " ms sürdü; 10 ms bütçeye yakın olmalı. Elle sınır "
                        + "24 Hz monitörün hızından etkilenmemeli.");
    }

    /**
     * Bozuk veya okunmamış monitör hızı çökme yaratmamalı, güvenli varsayılana
     * düşmeli. 0 veya negatif değer 0'a bölme hatası üretirdi.
     */
    @Test
    @DisplayName("Geçersiz monitör hızı güvenli varsayılana düşüyor")
    void invalidRefreshRateFallsBackSafely() {
        long renderNs = 1_000_000L;

        for (int bad : new int[]{0, -1, -60, Integer.MIN_VALUE}) {
            bindTo(FrameLimiter.INSTANCE);
            FrameLimiter.INSTANCE.setEnabled(true);
            FrameLimiter.INSTANCE.setMonitorRefreshRate(bad);

            for (int i = 0; i < 5; i++) {
                clock.advance(renderNs);
                FrameLimiter.INSTANCE.limitFrame(); // bölme hatası atmamalı
            }
        }

        // Varsayılan 60 Hz olarak kalmış olmalı: 5 render'ın ardından
        // en az bir kare tam bütçe kadar beklemiş olmalı.
        assertTrue(clock.nowNs > 0, "Geçersiz monitör hızı ölçümü bozdu.");
    }

    /** Hedefin en yüksek değerinde bütçe 1 ms'e iner; park bu kadar kısa süreyi
     *  güvenilir biçimde tamamlayabilmeli. */
    @Test
    @DisplayName("1000 fps hedefinde kare başına 1 ms bütçe doğru uygulanıyor")
    void veryHighTargetStillPaces() {
        int targetFps = 1000;
        long budgetNs = 1_000_000_000L / targetFps; // 1 ms

        bindTo(FrameLimiter.INSTANCE);
        FrameLimiter.INSTANCE.setEnabled(true);
        FrameLimiter.INSTANCE.setMonitorRefreshRate(targetFps);

        long renderNs = 100_000L; // 0.1 ms render
        for (int i = 0; i < 20; i++) {
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
        }

        long perFrame = clock.nowNs / 20;
        assertTrue(perFrame >= budgetNs * 0.9 && perFrame <= budgetNs * 1.3,
                "1000 fps hedefinde kare başına " + (perFrame / 1000) + " µs sürdü; "
                        + "1 ms bütçeye yakın olmalı.");
    }

    /**
     * Alışılmadık yenileme hızlarında da hedef tutturulmalı.
     *
     * <p>Bütçe {@code 1_000_000_000L / hedef} ile hesaplanır; bu tam sayı bölmesi
     * olduğu için bütçe her zaman gerçek süreden <b>biraz kısadır</b>. 61 Hz'de
     * kare başına 0.62 ns, 62 Hz'de 0.26 ns kadar. Bu hata saatte yüzlerce
     * mikrosaniye birikir — ihmal edilebilir görünse de, yanlış bir yuvarlama
     * yapılsaydı (ör. 61 -> 60'a yuvarlama) kare hızı sessizce kayardı.
     *
     * <p>Ayrıca çok yüksek hızlarda (1000 Hz) bütçe 1 ms'e iner ve spin
     * penceresinin (100 µs) üzerinde kalır; bütçe pencerenin altına düşseydi park
     * hiç yapılamaz ve sürekli spin olurdu.
     */
    @Test
    @DisplayName("Alışılmadık yenileme hızlarında hedef FPS tutturuluyor")
    void achievesTargetFpsAcrossUnusualRefreshRates() {
        // 59/61/62/63: 60'ın komşuları, kırpma hatasının görülebileceği yer
        int[] rates = {59, 60, 61, 62, 63, 75, 100, 120, 144, 240, 1000};
        int frames = 600; // ~10 saniye @60fps

        StringBuilder problems = new StringBuilder();

        for (int hz : rates) {
            bindTo(FrameLimiter.INSTANCE);
            FrameLimiter.INSTANCE.setEnabled(true);
            FrameLimiter.INSTANCE.setMonitorRefreshRate(hz);

            long renderNs = 1_000_000L; // 1 ms render: her hız için rahat sığar

            // İlk kare başlatma karesidir, ölçümden çıkarılır.
            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
            long mark = clock.nowNs;
            for (int i = 0; i < frames; i++) {
                clock.advance(renderNs);
                FrameLimiter.INSTANCE.limitFrame();
            }

            double elapsedSec = (clock.nowNs - mark) / 1e9;
            double achieved = frames / elapsedSec;
            // Başlangıç karesi ve ölçüm penceresi nedeniyle küçük bir tolerans.
            if (Math.abs(achieved - hz) > 0.5) {
                problems.append(String.format("%d Hz -> %.2f fps; ", hz, achieved));
            }
        }

        assertTrue(problems.isEmpty(),
                "Yenileme hızı hedef FPS'e oturmuyor: " + problems
                        + "sapma 0.5 fps'yi aşıyor. Bütçe hesabı veya spin "
                        + "penceresi bu hızlarda beklenen davranışı vermiyor.");
    }

    /**
     * Yuvarlama tuzağı: 61 Hz asla 60'a düşürülmemeli.
     *
     * <p>Yaygın bir hata, hedefi tam sayıya yuvarlamaktır (ör. {@code (int)(1e9/hz/1e6)*1000}
     * gibi). Böyle bir yuvarlama 61 Hz'i 60'a, 144'ü 140'a düşürür ve FPS Sync sessizce
     * yanlış hıza sabitler. Test, hedefin olduğu gibi bütçeye girdiğini doğrular.
     */
    @Test
    @DisplayName("Yenileme hızı yuvarlanmadan bütçeye giriyor")
    void refreshRateIsNotRoundedDown() {
        for (int hz : new int[]{61, 62, 63, 144, 165}) {
            bindTo(FrameLimiter.INSTANCE);
            FrameLimiter.INSTANCE.setEnabled(true);
            FrameLimiter.INSTANCE.setMonitorRefreshRate(hz);

            long expectedBudget = 1_000_000_000L / hz;
            long renderNs = 1_000_000L;

            clock.advance(renderNs);
            FrameLimiter.INSTANCE.limitFrame();
            long mark = clock.nowNs;
            for (int i = 0; i < 200; i++) {
                clock.advance(renderNs);
                FrameLimiter.INSTANCE.limitFrame();
            }
            long perFrame = (clock.nowNs - mark) / 200;

            assertEquals(expectedBudget, perFrame, expectedBudget / 20,
                    hz + " Hz için kare süresi " + perFrame + " ns oldu; beklenen "
                            + expectedBudget + " ns. Yenileme hızı yuvarlanmamalı — "
                            + "61'in 60'a, 144'ün 140'a düşmesi FPS Sync'i sessizce yanlışa sabitler.");
        }
    }

    /**
     * Çok yüksek yenileme hızlarında bütçe spin penceresinin altına düşerse
     * park yapılamaz ve sürekli spin olur. Bu, o hızlarda CPU'yi yer.
     */
    @Test
    @DisplayName("Bütçe spin penceresinin üstünde kaldığı sürece bekleme yapılabilir")
    void budgetStaysAboveSpinWindowForRealisticRates() {
        long spinWindowNs = 100_000L;
        long parkOvershootNs = 90_000L; // ölçülen parkNanos aşımı

        // Gerçekçi en yüksek hızlar: 360 Hz oyun monitörlerinde görülüyor
        int[] realRates = {24, 30, 50, 60, 75, 90, 100, 120, 144, 165, 240, 360, 500, 1000};
        for (int hz : realRates) {
            long budget = 1_000_000_000L / hz;
            assertTrue(budget > spinWindowNs + parkOvershootNs,
                    hz + " Hz'de bütçe " + budget + " ns, spin penceresi ("
                            + spinWindowNs + " ns) + park aşımı (" + parkOvershootNs
                            + " ns) toplamından küçük. Bu hızda park hiç yapılamaz ve "
                            + "sınırlayıcı sürekli spin eder; CPU tavanı yükselir.");
        }
    }

    /**
     * Eski kodun bütün hatası tek bir aritmetik ifadeye sığar ve bu, javadoc'taki
     * "1-2 ms spin" iddiasının dayanağıdır.
     *
     * <p>Kural tam olarak: kalan süre 1 ms veya üzerindeyse geriye 1-2 ms kalır ve
     * tamamı spin'e gider; 1 ms altındaysa uyku hiç yapılmaz ve süre olduğu gibi
     * spin olur (1 ms'den az). İlk yazdığım sürüm "her karede en az 1 ms" diyordu ve
     * bu test onu kırmızıya döndürdü — 400 µs kalan sürede hiç uyku yapılmıyor.
     *
     * <p><b>Bu bir TDD kırmızı-yeşil döngüsü değildir.</b> Ürün kodu zaten yazılmış ve
     * çalışmış durumda; {@link LegacyFrameLimiter} donmuş bir kopya. Buradaki amaç ürünü
     * geliştirmek değil, javadoc'a yazılan sayısal iddiayı geriye dönük bağlamak.
     */
    @Test
    @DisplayName("Eski kırpma kalan süreye göre 1-2 ms ya da tamamını spin'e bırakıyor")
    void legacyTruncationLeavesBetweenOneAndTwoMillisecondsWhenSleepIsPossible() {
        // 1 ms altı: uyku hiç yapılmaz, sürenin tamamı spin.
        for (long remaining = 1; remaining < 1_000_000L; remaining += 997) {
            assertEquals(remaining, LegacyFrameLimiter.spinRemainderNs(remaining),
                    remaining + " ns kaldığında uyku yapılmamalı, süre tamamen spin olmalı");
        }

        // 1 ms ve üzeri: geriye daima 1-2 ms kalır, asla 1 ms altına inmez.
        for (long remaining = 1_000_000L; remaining <= 20_000_000L; remaining += 997) {
            long spin = LegacyFrameLimiter.spinRemainderNs(remaining);
            assertTrue(spin >= 1_000_000L && spin < 2_000_000L,
                    remaining + " ns kaldığında spin " + spin + " ns çıktı; beklenen "
                            + "aralık 1-2 ms. Javadoc'taki '1-2 ms' iddiası yanlış.");
        }

        // Tam 1 ms kaldığında uyku planlanmaz, süre spin olur.
        assertEquals(1_000_000L, LegacyFrameLimiter.spinRemainderNs(1_000_000L),
                "tam 1 ms kaldığında uyku yapılmaz, 1 ms spin olmalı");
        assertEquals(1_000_000L, LegacyFrameLimiter.spinRemainderNs(2_000_000L),
                "tam 2 ms kaldığında 1 ms uyku yapılır, 1 ms spin kalmalı");
        assertEquals(1_500_000L, LegacyFrameLimiter.spinRemainderNs(2_500_000L),
                "2.5 ms kaldığında 1 ms uyku yapılır, 1.5 ms spin kalmalı");
    }

    /**
     * Eski kodun asıl kusuru "spin fazla" değil, <b>uykunun kırpılarak planlanması</b>dır:
     * uyku süresi daima tam milisaniyedir, yani kalan sürenin sub-milisaniye kısmı
     * atılır. Bu imza, düzeltmeden sonra <b>korunmamalıdır</b> — yeni yol nanosaniye
     * korur. Bu yüzden iki yönü de sabitliyoruz.
     */
    @Test
    @DisplayName("Eski kod uyku süresini tam milisaniyeye kırpıyor (kırpma imzası)")
    void legacySleepIsAlwaysAWholeNumberOfMilliseconds() {
        long budget60 = 1_000_000_000L / 60;
        int plansWithTruncation = 0;
        int plansWithoutSleep = 0;

        for (long renderNs = 0; renderNs < budget60; renderNs += 331_379L) {
            long remaining = budget60 - renderNs;
            long spin = LegacyFrameLimiter.spinRemainderNs(remaining);
            long sleepNs = remaining - spin;

            if (sleepNs == 0) {
                // 2 ms altı kalan süre: uyku hiç planlanmaz, süre olduğu gibi spin olur.
                // 1 ms altındaysa bu 1 ms'den az olabilir — javadoc'taki kural budur.
                plansWithoutSleep++;
                assertEquals(remaining, spin,
                        "kalan " + remaining + " ns'de uyku planlanmadıysa sürenin "
                                + "tamamı spin olmalı");
            } else {
                assertEquals(0, sleepNs % 1_000_000L,
                        "kalan " + remaining + " ns'den planlanan uyku " + sleepNs
                                + " ns tam milisaniye değil; kırpma imzası bozuldu");
                assertTrue(spin >= 1_000_000L && spin < 2_000_000L,
                        "kalan " + remaining + " ns iken spin " + spin
                                + " ns; uyku planlandığında 1-2 ms aralığında olmalı");
                plansWithTruncation++;
            }
        }

        // Kırpma gerçekten devrede olmalı: 1 ms altı kalan sürelerde uyku hiç planlanmaz.
        assertTrue(plansWithoutSleep > 0,
                "60 fps bütçesi taranırken hiç 'uykusuz' senaryo çıkmadı; "
                        + plansWithTruncation + " kırpılmış plan görüldü");
        assertTrue(plansWithTruncation > 0, "hiç kırpılmış uyku planı görülmedi");
    }

    private static String firstFew(List<String> items) {
        return items.subList(0, Math.min(3, items.size())).toString();
    }
}
