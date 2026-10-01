package com.fpssync;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Kare hızını sınırlar.
 *
 * <p>İki çalışma modu vardır. FPS Sync açıkken hedef, monitörün gerçek yenileme
 * hızıdır ve elle sınır yok sayılır. kapalıyken elle seçilen değer geçerlidir
 * ({@code 0} veya {@code >= 1010} sınırsız demektir).
 *
 * <p><b>Bekleme biçimi.</b> Bekleme {@link LockSupport#parkNanos} ile yapılır, nanosaniye
 * değeri olduğu gibi korunur. Daha önce şu iki satır vardı:
 *
 * <pre>{@code
 * long sleepMs = (nextFrameTime - now) / 1_000_000L - 1;
 * if (sleepMs > 0) Thread.sleep(sleepMs);
 * }</pre>
 *
 * İki ayrı kayıp birlikte: değer tam milisaniyeye kırpılıyor (en fazla 999 µs kayıp) ve
 * üstelik bilerek bir tam milisaniye daha düşülüyor. Kalan süre 1 ms veya üzerindeyse
 * geriye daima <b>1-2 ms</b> kalıyor ve bunun tamamı meşgul spin ile geçiyordu; kalan
 * süre 2 ms altına düşünce o 1 ms'lik fark uykuya hiç yansımıyor, sürenin tamamı spin
 * oluyor (1 ms altındaysa bu 1 ms'den az olabilir). Eski kodda ayrı bir spin penceresi
 * de yoktu: geriye kalan bu süre zaten koşulsuz spin'e gittiği için pencerenin ne kadar
 * olması gerektiğinin ayrı bir kararı yoktu.
 *
 * <p><b>Ölçülen sonuç</b> (bu makine, {@code FrameLimiterLegacyComparison}, 10 s koşu,
 * render=0 yani sınırlayıcının kendi maliyeti; CPU için {@code ThreadMXBean} ana thread):
 * 60 fps'te spin <b>1453 µs</b>/kare ve CPU <b>1504 µs</b>/kare, yani tek çekirdeğin
 * <b>%9.0</b>'ı. 144 fps'te spin 1771 µs, CPU 1816 µs — tek çekirdeğin <b>%26</b>'ı.
 * Yani hatanın maliyeti FPS'e doğrusal ölçeklenir ve <b>yüksek yenileme hızlarında, yani
 * FPS Sync'in asıl hedeflediği durumda, en kötüdür.</b>
 *
 * <p>{@code parkNanos} ile spin 60 fps'te <b>8.9 µs</b>'ya, CPU 65.9 µs'ya (tek çekirdeğin
 * %0.40'ı) indi; 144 fps'te 10.6 ve 64.5 µs (%0.93). Spin maliyeti ~<b>165 kat</b>, toplam
 * CPU ~23-28 kat azaldı. Kalan maliyet bekleme çağrısının kendisindedir: spin toplamın
 * yalnız ~%14'ü, bu yüzden {@link #SPIN_WINDOW_NS} penceresini küçültmek anlamlı kazanç
 * sağlamaz.
 *
 * <p><em>Tarihçe notu: burada daha önce "ortalama spin 532 µs, %3.2, 10.7 kat iyileşme"
 * yazıyordu. Bunlar sanal saat modelinin <b>tahminidir, ölçüm değildir</b>; model
 * {@code Thread.sleep} aşımını ~1 ms varsaymış, ölçülen aşım ~150-200 µs çıktı. Bu
 * yüzden eski yolun hasarını ~2.7 kat, düzeltmenin kazancını ~15 kat düşük tahmin etmiş.
 * Gerçek eski yol hiç ölçülmemişti; yukarıdaki rakamlar
 * {@code LegacyFrameLimiter} ile bu depodaki eski kod birebir koşularak alındı.</em>
 *
 * <p><b>Yavaş oyun.</b> Kare bütçesini aşan bir kare geldiğinde sınırlayıcı hiç
 * beklemez ve kare süresine dokunmaz — oyun zaten hedefin altındadır, yapılacak tek
 * şey karışmamaktır. Bu, FPS Sync'in en sık gerçekleşen durumudur.
 *
 * <p><b>Test edilebilirlik.</b> Zaman ve bekleme çağrıları alan üzerinden
 * değiştirilebilir ({@link #nanoTime}, {@link #sleeper}); testler sanal saatle
 * ölçer. Sanal saat bir modeldir ve mutlak spin sayıları için
 * {@link FrameLimiterCpuProbe} kullanılmalıdır.
 */
public class FrameLimiter {

    /** Oyunun kullandığı tek örnek. */
    public static final FrameLimiter INSTANCE = new FrameLimiter();

    /** Kesinti fırlatabilen bekleme geri çağrısı; testlerde sanal saatle değiştirilir. */
    @FunctionalInterface
    interface Sleeper {
        void park(long nanos) throws InterruptedException;
    }

    /**
     * Bekleme sonrası meşgul bekleme penceresi.
     *
     * <p>0.1 ms: bekleme çağrısının aşımını (ölçülen ~90 µs) tolere edecek kadar
     * uzun, ama bir çekirdeği boşuna meşgul etmeyecek kadar kısa. Bu pencerenin
     * tamamı her karede kullanılmaz — aşım büyükse döngü hiç çalışmaz.
     */
    private static final long SPIN_WINDOW_NS = 100_000L;

    private long lastFrameTime = 0;
    private boolean fpsSyncEnabled = false;
    private int manualFpsLimit = 0;
    private int monitorRefreshRate = 60;

    LongSupplier nanoTime = System::nanoTime;
    Sleeper sleeper = LockSupport::parkNanos;
    LongConsumer onSpin = spin -> { };
    Runnable spinHook = () -> { };

    public void setEnabled(boolean value) {
        fpsSyncEnabled = value;
        lastFrameTime = 0;
    }

    public void setManualLimit(int fps) {
        manualFpsLimit = fps;
        lastFrameTime = 0;
    }

    public void setMonitorRefreshRate(int hz) {
        if (hz > 0) {
            monitorRefreshRate = hz;
        }
    }

    public void reset() {
        lastFrameTime = 0;
        fpsSyncEnabled = false;
        manualFpsLimit = 0;
        monitorRefreshRate = 60;
    }

    public void limitFrame() {
        int targetFps;

        if (fpsSyncEnabled) {
            targetFps = monitorRefreshRate;
        } else if (manualFpsLimit > 0 && manualFpsLimit < 1010) {
            targetFps = manualFpsLimit;
        } else {
            return; // Unlimited (manualFpsLimit == 0 or >= 1010)
        }

        if (targetFps <= 0) return;

        long frameBudgetNs = 1_000_000_000L / targetFps;
        long now = nanoTime.getAsLong();

        if (lastFrameTime == 0) { lastFrameTime = now; return; }

        long nextFrameTime = lastFrameTime + frameBudgetNs;

        if (now >= nextFrameTime) { lastFrameTime = now; return; }

        long remaining = nextFrameTime - now;

        // Büyük kısımı uyu; yalnızca son 0.1 ms'yi spin ile tamamla.
        long sleepNs = remaining - SPIN_WINDOW_NS;
        if (sleepNs > 0) {
            try {
                // Nanosaniye değeri doğrudan korunur. Thread.sleep(ms) kullanılsaydı
                // kırpma 0.1 ms'lik spin penceresini yutardı: kalan süre 1.1 ms'nin
                // altına düşünce uyku hiç yapılmaz ve kalan sürenin tamamı spin
                // edilirdi. Ölçülen parkNanos aşımı bu makinede ~90 µs, Thread.sleep
                // aşımı ~150-200 µs; yani pencere gerçekten 0.1 ms olarak uygulanır.
                sleeper.park(sleepNs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long spinStart = nanoTime.getAsLong();
        while (nanoTime.getAsLong() < nextFrameTime) {
            spinHook.run();
            Thread.onSpinWait();
        }
        onSpin.accept(nanoTime.getAsLong() - spinStart);

        lastFrameTime = nextFrameTime;
    }
}
