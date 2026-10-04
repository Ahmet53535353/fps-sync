package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Başarısız park çağrısından sonra <em>bir kez daha</em> denemek.
 *
 * <h2>Neden</h2>
 * 1.3.0'ın fayda koruması şu kuralı koydu: "bir park çağrısı 45 µs'tan az uyuduysa
 * tekrar deneme, spin'e düş." Gerçek koşu bu kuralı şöyle çürüttü:
 *
 * <pre>
 * erken uyku   medyan 0,0 µs · p05 0,0 µs · p95 6.740 µs
 * kare başına park çağrısı  1,21
 * </pre>
 *
 * Dağılım <b>tek değil, iki modlu</b>: karelerin yarısı hiç uyumadan dönüyor, bir
 * kısmı 6,7 ms'ye kadar uyuyor. Kural her çağrıyı <em>tek başına</em> değerlendirdiği
 * için 0 µs popülasyonunu görüp o karelerdeki 6,7 ms'lik kuyruğu hiç görmedi.
 *
 * <h2>Ekonomi</h2>
 * Bir park çağrısının CPU maliyeti ~45 µs. Dağılımda iyi çağrı oranı p95'ten ~%5 ve
 * iyi çağrı 6.740 µs uyuyor. Beklenen değer:
 *
 * <pre>
 *   0,05 × 6.740 µs = 337 µs kazanç   karşılığında   45 µs maliyet
 * </pre>
 *
 * Yani bir kez daha denemek, guard'ın reddettiği her karede beklenen olarak
 * <b>katlanarak pozitif</b>. Doğrusu "bu çağrı başarısız" demek değil, "sonraki de
 * başarısız" demek değildir.
 *
 * <h2>Bu bir ölçüm de</h2>
 * Sayaçlar, tekrarın gerçekten işe yarayıp yaramadığını <b>aynen ölçer</b>: kare
 * başına 45 µs'lik ek gider, karşılığında 6,7 ms'lik uyku. Oran düşükse değişiklik
 * geri alınır; sayılar o kararı verir.
 */
class ParkRetryAfterFailTest {

    private static final long BUDGET_NS = 1_000_000_000L / 60L;
    private static final long SPIN_NS = 100_000L;

    /**
     * Park'ın çağrı çağrı istenen sürenin farklı oranlarını uyuduğu senaryo.
     *
     * <p>Liste bittikten sonra son oran tekrarlanır, böylece "hep 0" gibi
     * sonsuz senaryolar da kısa yazılabilir.
     */
    private static FrameLimiter runSequence(double... ratios) {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        final long startNs = 1_000_000L;
        final long deadlineNs = startNs + BUDGET_NS;
        long[] clock = {startNs};
        int[] call = {0};

        limiter.reset();
        limiter.resetFrameStats();
        limiter.nanoTime = () -> clock[0];
        limiter.sleeper = ns -> {
            int i = call[0]++;
            double ratio = ratios[Math.min(i, ratios.length - 1)];
            clock[0] += Math.max(1L, (long) (ns * ratio));
        };
        limiter.spinHook = () -> { clock[0] = deadlineNs; };
        limiter.onSpin = spin -> { };
        limiter.setEnabled(true);
        limiter.setMonitorRefreshRate(60);

        limiter.limitFrame();                 // ızgara kurulumu
        clock[0] += BUDGET_NS / 2;            // yarısına kadar ilerle
        limiter.limitFrame();                 // ölçülen kare

        return limiter;
    }

    @Test
    @DisplayName("park hiç uyumazsa guard devreye girer ve bir kez daha denenir")
    void failedCallGetsOneMoreChance() {
        FrameLimiter l = runSequence(0.0);

        assertEquals(2, l.parkCallsLastFrame,
                "başarısız ilk çağrıdan sonra tam bir kez daha denenmeli");
        assertEquals(1, l.retryAfterFailCalls,
                "fayda koruması kaç kez 'tekrar dene' dedi sayılmalı");
    }

    @Test
    @DisplayName("ikinci deneme de uyumazsa kare iki çağrıda kapanır")
    void secondFailureStopsTheFrame() {
        FrameLimiter l = runSequence(0.0, 0.0);

        assertEquals(2, l.parkCallsLastFrame,
                "iki başarısız denemeden sonra döngü çıkmalı, sonsuza kadar gitmemeli");
        assertEquals(0, l.retryAfterFailSleptCalls,
                "ikinci deneme de uyumadıysa 'faydalı tekrar' sayılmamalı");
    }

    @Test
    @DisplayName("başarısız denemeden sonraki çağrı uyarsa faydalı tekrar sayılır")
    void retryThatSleptIsCounted() {
        // İlk çağrı hiç uyumuyor, ikincisi istenenin tamamını uyuyor. Böylece kare
        // iki çağrıda kapanır ve sayaçlar kuyruktan etkilenmez.
        FrameLimiter l = runSequence(0.0, 1.0);

        assertEquals(2, l.parkCallsLastFrame, "iki çağrıda kapanmalı");
        assertEquals(1, l.retryAfterFailCalls, "bir kez tekrar denenmeli");
        assertEquals(1, l.retryAfterFailSleptCalls,
                "tekrar gerçekten uyuduysa sayılmalı");
        assertTrue(l.retryAfterFailSleptNs > 1_000_000L,
                "tekrar 8 ms'ye yakın uyumuş olmalı: " + l.retryAfterFailSleptNs + " ns");
    }

    @Test
    @DisplayName("ilk çağrı faydalıysa guard hiç devreye girmez")
    void healthyFirstCallNeverTriggersGuard() {
        FrameLimiter l = runSequence(1.0);

        assertEquals(1, l.parkCallsLastFrame, "sağlıklı park'ta tek çağrı yeter");
        assertEquals(0, l.retryAfterFailCalls,
                "faydalı çağrıdan sonra tekrar denenmemeli");
    }

    @Test
    @DisplayName("tekrar uyarsa spin çöker: 8 ms bekleme hiç yakılmaz")
    void retryThatSleptEliminatesSpin() {
        // NOT: runSequence singleton'ı döndürür; ikinci çalıştırma birincinin
        // değerlerini ezer. Bu yüzden her koşunun sonucu ANINDA yakalanır.
        runSequence(0.0, 1.0);
        long goodSpin = FrameLimiter.INSTANCE.spinNsLastFrame;
        runSequence(0.0, 0.0);
        long badSpin = FrameLimiter.INSTANCE.spinNsLastFrame;

        assertTrue(goodSpin <= SPIN_NS,
                "tekrar uyduysa yalnız spin penceresi kalır, oldu: " + goodSpin + " ns");
        assertTrue(badSpin > 5_000_000L,
                "iki deneme de boşa dönerse süre spin ile yakılmalı, oldu: "
                        + badSpin + " ns");
    }

    @Test
    @DisplayName("sıfırlama yeni sayaçları da temizler")
    void resetClearsNewCounters() {
        FrameLimiter l = runSequence(0.0, 1.0);
        assertEquals(1, l.retryAfterFailCalls, "önce: sayaç dolu olmalı");

        l.resetFrameStats();

        assertEquals(0, l.retryAfterFailCalls);
        assertEquals(0, l.retryAfterFailSleptCalls);
        assertEquals(0L, l.retryAfterFailSleptNs);
    }
}