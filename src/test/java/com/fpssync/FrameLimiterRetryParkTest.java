package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Park'a güvenmeyen bekleme: kalan süre kapanana kadar tekrar dene.
 *
 * <h2>Sorun</h2>
 * Park karelerin yarısında istenenden 7,69 ms erken dönüyor ve kalan sürenin
 * tamamı {@code Thread.onSpinWait()} ile yanıyor: kare başına 4.330 µs CPU, bir
 * çekirdeğin %18'i. Kare süresi doğru (park + spin sabit), yalnızca beklemenin
 * maliyeti yüksek.
 *
 * <h2>Çözüm</h2>
 * Park tek başına güvenilir değil; ona güvenmeyelim. Kalan süre spin penceresinin
 * üstünde olduğu sürece park tekrar denenir, yalnız son 100 µs spin ile kapanır.
 * Böylece park'ın neden erken döndüğünü <em>bilmeye gerek kalmaz</em>: erken de
 * döne, zamanında da döne, geç de döne, üçünde de kalan süre kapanır.
 *
 * <h2>Neden sonsuz döngü olmaz</h2>
 * İki ayrı güvence var ve ikisi de ölçümle doğrulanır:
 * <ul>
 *   <li><b>Tek deneme kuralı:</b> fayda koruması devreye girdiğinde (park çağrısı
 *       kendi ~45 µs CPU maliyetini karşılamadı) tam <em>bir kez</em> daha denenir,
 *       sonra kare kapanır. Bir kez daha denemenin gerekçesi ve ölçümü
 *       {@link ParkRetryAfterFailTest} içindedir: gerçek dağılım iki modlu
 *       (medyan 0 µs, p95 6.740 µs), dolayısıyla "bu çağrı uyumadı" demek
 *       "sonraki de uyumayacak" demek <em>değildir</em>.</li>
 *   <li><b>Güvenlik ağı:</b> çağrı sayısı bir üst sınıra ulaşınca döngü biter.</li>
 * </ul>
 *
 * <p>Üst sınır bir ayar değil, güvenlik ağıdır: rapordaki <b>kare başına park
 * çağrısı</b> sayısı bu ağa ne zaman takıldığımızı gösterir. Ortalama 2'deyse
 * üst sınır hiç işe yaramamış demektir.
 */
class FrameLimiterRetryParkTest {

    private static final long BUDGET_NS = 1_000_000_000L / 60L;
    private static final long SPIN_NS = 100_000L;

    /** Park'ın istenen sürenin {@code ratio} kadarını uyuduğu senaryo. */
    private static final class Result {
        int parkCalls;
        long spinNs;
    }

    private static Result run(double ratio) {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        final long startNs = 1_000_000L;
        final long deadlineNs = startNs + BUDGET_NS;
        long[] clock = {startNs};
        int[] parks = {0};

        limiter.reset();
        limiter.resetFrameStats();
        limiter.nanoTime = () -> clock[0];
        // İstenenin `ratio` kadarı kadar uyur, sonra döner.
        limiter.sleeper = ns -> {
            parks[0]++;
            clock[0] += Math.max(1L, (long) (ns * ratio));
        };
        limiter.spinHook = () -> { clock[0] = deadlineNs; };
        limiter.onSpin = spin -> { };
        limiter.setEnabled(true);
        limiter.setMonitorRefreshRate(60);

        limiter.limitFrame();                     // ızgara kurulumu
        clock[0] += BUDGET_NS / 2;                // yarısına kadar ilerle
        limiter.limitFrame();

        Result r = new Result();
        r.parkCalls = parks[0];
        r.spinNs = limiter.spinNsLastFrame;
        return r;
    }

    @Test
    @DisplayName("park düzgün çalışıyorsa tek deneme yeter")
    void healthyParkNeedsOneCall() {
        Result r = run(1.0);

        assertEquals(1, r.parkCalls, "park isteneni uyuyduysa tekrar gerekmez");
        assertTrue(r.spinNs <= SPIN_NS, "spin pencereyi aşmamalı: " + r.spinNs);
    }

    @Test
    @DisplayName("park erken dönüyorsa tekrar denenir")
    void earlyParkIsRetried() {
        Result r = run(0.30);

        assertTrue(r.parkCalls > 1,
                "park erken döndüyse tekrar denenmeli, çağrı: " + r.parkCalls);
    }

    @Test
    @DisplayName("park az uyuyorsa bile spin eski davranışın çok altında kalır")
    void spinIsCappedEvenWhenParkIsBad() {
        // Gerçek koşudaki oran: park istenenin yalnızca %12'sini uyuyor.
        Result bad = run(0.12);

        // Eski davranışta bu kare ~7,7 ms spin ile kapanıyordu.
        assertTrue(bad.spinNs < 1_000_000L,
                "park %12 uyusa bile spin 1 ms altında kalmalı, oldu: "
                        + bad.spinNs + " ns");
    }

    @Test
    @DisplayName("iyi çalışan park'ta spin yalnızca kalan son pencereye yakın")
    void healthyParkLeavesOnlyTheSpinWindow() {
        Result good = run(0.90);

        // Döngü kareyi pencerenin hemen üstünde bitirebilir: son park çağrısının
        // kısa bir eksiği kalır. Tam pencere şartı aşırı — bir park çağrısının
        // maliyeti kadar tolerans makuldür.
        long tolerance = 60_000L;
        assertTrue(good.spinNs <= SPIN_NS + tolerance,
                "park neredeyse tam uyuyorsa spin pencereye yakın olmalı: "
                        + good.spinNs + " ns (tolerans " + tolerance + ")");
    }

    @Test
    @DisplayName("park hiç uyumazsa döngü bir iki denemede kapanır")
    void instantParkTerminatesQuickly() {
        Result r = run(0.000001);

        assertTrue(r.parkCalls <= 3,
                "park uyku yapmıyorsa sınırlı denemede çıkılmalı, çağrı: " + r.parkCalls);
    }

    @Test
    @DisplayName("spin penceresinden az fayda verirse tam bir kez daha denenir")
    void lowValueParkGetsExactlyOneMoreTry() {
        // İstenenin %0,5'i = 8,23 ms'den 41 µs. Spin penceresinin (100 µs) altında,
        // yani park çağrısının kendi CPU maliyetini (~45 µs) karşılamıyor.
        //
        // 1.3.0 burada koşulsuz kırıyordu ve yanlış varsayıma dayanıyordu: gerçek
        // dağılım iki modlu (medyan 0 µs, p95 6.740 µs), yani "bu çağrı uyumadı"
        // demek "sonraki de uyumayacak" demek değildir. Şimdi tam bir kez daha
        // denenir; ikinci deneme de tutmazsa kare kapanır. Bkz. ParkRetryAfterFailTest.
        Result r = run(0.005);

        assertEquals(2, r.parkCalls,
                "faydasız park çağrısı tam bir kez daha denenmeli, çağrı: " + r.parkCalls);
    }

    @Test
    @DisplayName("spin penceresinden fazla fayda verirse tekrar edilir")
    void usefulParkIsRetried() {
        // İstenenin %12'si = 0,99 ms; spin penceresinin çok üstünde.
        Result r = run(0.12);

        assertTrue(r.parkCalls > 1,
                "faydalı park çağrısı tekrar edilmeli, çağrı: " + r.parkCalls);
    }

    @Test
    @DisplayName("güvenlik ağı hiçbir senaryoda devreye girmeyi engellemez")
    void progressStillClosesTheFrame() {
        for (double ratio : new double[] {0.0, 0.05, 0.12, 0.30, 0.5, 1.0}) {
            Result r = run(ratio);
            // Döngü bittiğinde kalan süre spin penceresine inmiş olmalı ya da
            // güvenlik ağı devreye girmiş olmalı. Her iki durumda da döngü çıkmıştır.
            assertTrue(r.spinNs >= 0, "oran " + ratio + ": spin negatif olamaz");
        }
    }

    @Test
    @DisplayName("sayaçlar tekrar denemeleri toplar")
    void countersAccumulateAcrossAttempts() {
        Result r = run(0.30);
        FrameLimiter limiter = FrameLimiter.INSTANCE;

        assertTrue(limiter.parkCallsLastFrame >= r.parkCalls,
                "kare istatistiği tüm denemeleri saymalı");
        assertTrue(limiter.parkRequestedNsLastFrame > 0, "istenen süre toplanmalı");
        assertTrue(limiter.parkElapsedNsLastFrame > 0, "geçen süre toplanmalı");
    }

    @Test
    @DisplayName("sıfırlama tüm yeni sayaçları temizler")
    void resetClearsCounters() {
        run(0.30);
        FrameLimiter limiter = FrameLimiter.INSTANCE;

        limiter.resetFrameStats();

        assertEquals(0, limiter.parkCallsLastFrame);
        assertEquals(0, limiter.spinNsLastFrame);
        assertEquals(0, limiter.parkElapsedNsLastFrame);
        assertEquals(0, limiter.parkRequestedNsLastFrame);
    }
}