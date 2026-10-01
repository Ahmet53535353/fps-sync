package com.fpssync;

import java.util.function.LongConsumer;

/**
 * {@code fe04bf4} (1.21.0-1) commit'indeki {@code FrameLimiter}'ın ölçüm kopyası.
 *
 * <p><b>Neden var:</b> {@code parkNanos} düzeltmesinin gerçekten ne kadar kazandığı
 * ancak eski yol da aynı makinede, aynı ölçüm yöntemiyle koşulursa ölçülür. Eski kodun
 * maliyeti daha önce yalnız <b>sanal saat modelinden</b> tahmin edildi; o tahmin
 * "532 µs" dedi ve bu rakam {@code FrameLimiter} javadoc'unda "Ölçülen sonuç" olarak
 * yazıldı. Bu sınıf, o iddianın gerçekten doğru olup olmadığını ölçmek için var.
 *
 * <p><b>Birebir korunan kısım</b> — gecikme hesabı:
 *
 * <pre>{@code
 * long sleepMs = (nextFrameTime - now) / 1_000_000L - 1;
 * if (sleepMs > 0) Thread.sleep(sleepMs);
 * }</pre>
 *
 * <p>Milisaniyeye kırpma <b>ve</b> bir tam milisaniye düşülmesi. Kalan her şey
 * {@code Thread.onSpinWait()} içinde geçiyor; ayrı bir spin penceresi yok.
 *
 * <p><b>Bilerek değiştirilen tek nokta:</b> eski kod her karede
 * {@code MonitorInfoProvider.updateDisplayInfo()} çağırıyordu; bu Minecraft ve GLFW
 * gerektiren bir native çağrı ve uyku davranışının parçası değil. Ölçümde monitör hızı
 * sabit verilir. Ölçülen maliyet bu yüzden eski koddan <b>biraz düşüktür</b> (native
 * çağrı hariç), yani bulgu eski kod lehine bir <b>tahmin sınırıdır</b>, lehine değil.
 *
 * <p>Üretim kodunda kullanılmaz; yalnız {@link FrameLimiterLegacyComparison} çağırır.
 */
final class LegacyFrameLimiter {

    private long lastFrameTime = 0;
    private boolean fpsSyncEnabled = false;
    private int monitorRefreshRate = 60;

    LongConsumer onSpin = spin -> { };

    void setEnabled(boolean value) {
        fpsSyncEnabled = value;
        lastFrameTime = 0;
    }

    void setMonitorRefreshRate(int hz) {
        if (hz > 0) {
            monitorRefreshRate = hz;
        }
    }

    void reset() {
        lastFrameTime = 0;
        fpsSyncEnabled = false;
        monitorRefreshRate = 60;
    }

    void limitFrame() {
        int targetFps;

        if (fpsSyncEnabled) {
            targetFps = monitorRefreshRate;
        } else {
            return;
        }

        if (targetFps <= 0) return;

        long frameBudgetNs = 1_000_000_000L / targetFps;
        long now = System.nanoTime();

        if (lastFrameTime == 0) { lastFrameTime = now; return; }

        long nextFrameTime = lastFrameTime + frameBudgetNs;

        if (now >= nextFrameTime) { lastFrameTime = now; return; }

        // --- eski kodun kırpma hatası: tam ms'ye kırpıp bir ms daha düşüyor ---
        try {
            long sleepMs = (nextFrameTime - now) / 1_000_000L - 1;
            if (sleepMs > 0) Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long spinStart = System.nanoTime();
        while (System.nanoTime() < nextFrameTime) {
            Thread.onSpinWait();
        }
        onSpin.accept(System.nanoTime() - spinStart);

        lastFrameTime = nextFrameTime;
    }

    /**
     * Kırpmanın bıraktığı süreyi hesaplar — ölçümden bağımsız, elle doğrulanabilir
     * denklem. {@code sleepMs = floor(remaining/1ms) - 1} olduğu için geriye en az
     * 1 ms kalır; kalan sürenin tamamı spin olur.
     *
     * @return spin'e kalan nanosaniye (uyku aşımı hariç, yani en iyimser alt sınır)
     */
    static long spinRemainderNs(long remainingNs) {
        long sleepMs = remainingNs / 1_000_000L - 1;
        if (sleepMs < 0) sleepMs = 0;
        return remainingNs - sleepMs * 1_000_000L;
    }
}
