package com.fpssync;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Monitor;
import net.minecraft.client.util.VideoMode;

/**
 * Pencerenin bulunduğu monitörün yenileme hızını verir.
 *
 * <p>Önceden bu bilgi elle GLFW çağrılarıyla alınıyordu. İki sorunu vardı:
 * <ul>
 *   <li>{@code glfwGetMonitors} ve {@code glfwGetVideoMode} çağrıları her seferinde
 *       yeni native bellek ayırıyor; döndürülen nesneler serbest bırakılmadığı için
 *       saniyede ~80 baytlık sızıntı oluşuyordu.</li>
 *   <li>Monitörü pencere konumundan tahmin etmek gerekiyordu.</li>
 * </ul>
 *
 * <p>Yerine Minecraft'ın {@code Window.getMonitor()} metodu kullanılıyor: aynı bilgiyi,
 * tahsis yapmadan, Minecraft'ın kendi izleme mantığıyla verir.
 *
 * <p><b>Bilinen sınır — HiDPI ve çoklu monitör.</b> {@code Window.getMonitor()} tam
 * ekranda {@code glfwGetWindowMonitor} ile birebir doğru çalışır. Pencere modunda
 * ise {@code MonitorTracker}, pencerenin dikdörtgeni ile monitörlerin
 * dikdörtgenlerinin <em>kesişim alanını</em> karşılaştırır; bu dikdörtgen
 * {@code getX() + getWidth()} ile kurulur ve {@code getWidth()} framebuffer
 * genişliğidir. HiDPI ölçeklemede ekran koordinatı ile framebuffer pikselleri
 * farklı ölçeklerdedir, bu yüzden ölçüm kayabilir ve yanlış monitör seçilebilir.
 *
 * <p>Bu, FPS-Sync'in kendi hatası değil — Minecraft'ın da aynı varsayımı var. Tek
 * monitörlü kurulumlarda bir etkisi yoktur (sonuç birincil monitöre düşer). Çoklu
 * monitörlü veya HiDPI kurulumlarda FPS Sync yanlış yenileme hızına sabitlenebilir;
 * bu durumda sorunu çözmek {@code getMonitor()}'ı bırakıp ölçülebilir bir testle
 * (çoklu monitör geometrisi simüle edilip seçilen monitör doğrulanarak) yeniden
 * yazmak gerekir.
 */
public class MonitorInfoProvider {

    private static final int FALLBACK_REFRESH_RATE = 60;
    private static final long CHECK_INTERVAL_NS = 1_000_000_000L;

    private static long lastCheckTime = 0;
    private static int lastRefreshRate = FALLBACK_REFRESH_RATE;

    /**
     * Monitör değişimlerini izler. Her karede çağrılır ancak saniyede bir
     * gerçekten monitör durumuna bakar; böylece pencere monitörler arasında
     * taşınsa bile en gecikme bir saniyedir.
     */
    public static void updateDisplayInfo() {
        long now = System.nanoTime();
        if (now - lastCheckTime < CHECK_INTERVAL_NS) {
            return;
        }
        lastCheckTime = now;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return;
        }

        Monitor monitor = client.getWindow().getMonitor();
        if (monitor == null) {
            return;
        }

        VideoMode mode = monitor.getCurrentVideoMode();
        if (mode == null) {
            return;
        }

        int refreshRate = mode.getRefreshRate();
        if (refreshRate > 0) {
            lastRefreshRate = refreshRate;
        }
    }

    public static int getRefreshRate() {
        return lastRefreshRate;
    }
}
