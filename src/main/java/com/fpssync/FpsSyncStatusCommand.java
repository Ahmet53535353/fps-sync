package com.fpssync;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * {@code /fpsync status} — kare zamanlaması raporunu üretir.
 *
 * <h2>Alt komutlar</h2>
 * <ul>
 *   <li>{@code /fpsync status} — raporu üretir ve <b>sayacı sıfırlar</b></li>
 *   <li>{@code /fpsync status keep} — raporu üretir, sayaçları korur (sadece izlemek için)</li>
 *   <li>{@code /fpsync status reset} — sayaçları boşaltır, rapor üretmez</li>
 * </ul>
 *
 * <p>Sıfırlama, "ölç → bir şeyi değiştir → ölç" döngüsü içindir: her rapor tek bir
 * aralığı tanımlar, iki rapor karşılaştırılabilir.
 *
 * <h2>Çıktı</h2>
 * Üç yere yazılır: <b>dosya</b> (zaman damgalı + {@code latest.txt}), <b>panoya</b> (kopya
 * yapılabilsin diye) ve <b>sohbete</b> (yol + tek satır özet). Pano erişimi Linux'ta
 * kısıtlı olabildiği için başarısız olursa yalnız dosya bırakılır ve bu durum
 * sohbette belirtilir.
 */
public final class FpsSyncStatusCommand {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String DIR = "fps-sync";

    private FpsSyncStatusCommand() {
    }

    /** Komutu Fabric'e kaydeder. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> {
                    dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                            .literal("fpsync")
                            .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                    .literal("status")
                                    .executes(ctx -> run(ctx.getSource(), false, false))
                                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                            .literal("keep")
                                            .executes(ctx -> run(ctx.getSource(), true, false)))
                                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                                            .literal("reset")
                                            .executes(ctx -> run(ctx.getSource(), false, true)))));
                });
    }

    private static int run(FabricClientCommandSource source, boolean keepCounters, boolean resetOnly) {
        MinecraftClient client = MinecraftClient.getInstance();
        FramePacingRecorder pacing = FpsSyncMod.pacing();

        // Rapor üretimi sırasında ölçüm dursun: metin üretimi tahsis yapar ve
        // sınırlayıcının ölçtüğü şeyi bozmaması gerekir.
        pacing.setPaused(true);
        try {
            if (resetOnly) {
                pacing.reset();
                source.sendFeedback(Text.literal("FPS Sync: sayaçlar sıfırlandı."));
                return 1;
            }
            String report = buildReport(client, pacing);
            Result written = writeToDisk(report);
            boolean clipboard = copyToClipboard(report);

            if (!keepCounters) {
                pacing.reset();
            }

            source.sendFeedback(Text.literal(summary(report, written, clipboard)));
            return 1;
        } finally {
            pacing.setPaused(false);
        }
    }

    private static String buildReport(MinecraftClient client, FramePacingRecorder pacing) {
        int w = 0;
        int h = 0;
        if (client.getWindow() != null) {
            w = client.getWindow().getWidth();
            h = client.getWindow().getHeight();
        }
        boolean sync = FpsSyncMod.LIMITER.isSyncEnabled();
        int hz = MonitorInfoProvider.getRefreshRate();
        return FpsSyncStatusReport.render(new FpsSyncStatusReport.Snapshot(
                pacing, sync, hz, w, h,
                ModVersion.resolve(),
                sync || !SodiumPresence.isPresent(),
                0));
    }

      /**
       * Dosya yazma sonucu.
       *
       * @param path yazılan dosyanın yolu
       * @param ok yazma başarılıysa {@code true}
       */
      public record Result(Path path, boolean ok) {
      }

    /**
     * Raporu oyun dizinindeki {@code fps-sync/} klasörüne yazar.
     *
     * <p>Asıl giriş noktası oyun dizinini bilen {@code run}; test edilebilirlik için
     * taban dizin ayrıca alınır.
     */
    public static Result writeToDisk(String report) {
        return writeToDisk(report, MinecraftClient.getInstance().runDirectory.toPath());
    }

    /**
     * Raporu verilen taban dizin altına yazar; {@code latest.txt} eşzamanlı olarak
     * güncellenir.
     *
     * <p>Başarısızlık fırlatmaz: rapor kaybolsa bile oyun çökmez, çağıran yalnızca
     * {@code ok == false} görür.
     */
    static Result writeToDisk(String report, Path gameDir) {
        try {
            Path dir = gameDir.resolve(DIR);
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(STAMP);
            Path file = dir.resolve("status-" + stamp + ".txt");
            // Aynı milisaniyede iki rapor üretilebilir (ör. iki komut birbirine
            // yakın). İlk dosyanın üzerine yazmamak için ad çakışana kadar numara eklenir;
            // iki rapor da karşılaştırılabilir kalmalıdır.
            for (int n = 1; Files.exists(file) && n < 1000; n++) {
                file = dir.resolve("status-" + stamp + "-" + n + ".txt");
            }
            Files.writeString(file, report, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("latest.txt"), report, StandardCharsets.UTF_8);
            return new Result(file, true);
        } catch (IOException | RuntimeException e) {
            return new Result(null, false);
        }
    }

    private static boolean copyToClipboard(String report) {
        try {
            MinecraftClient.getInstance().keyboard.setClipboard(report);
            return true;
        } catch (Throwable t) {
            // GLFW pano erişimi bazı Linux kurulumlarında engellidir; dosya yeterli.
            return false;
        }
    }

    private static String summary(String report, Result written, boolean clipboard) {
        StringBuilder b = new StringBuilder();
        b.append(written.ok()
                ? "FPS Sync raporu yazıldı: " + written.path()
                : "FPS Sync raporu dosyaya yazılamadı — panoya kopyalandı.");
        if (clipboard) {
            b.append(" (panoya kopyalandı)");
        } else {
            b.append(" (pano kopyalanamadı)");
        }
        // Sohbette tek satır özet: dosyayı açmadan da durum görülsün.
        for (String line : report.split("\n")) {
            String t = line.trim();
            if (t.startsWith("gerçek FPS") || t.startsWith("limiter devrede")
                    || t.startsWith("ilk bekleme")) {
                b.append("  ·  ").append(t);
            }
        }
        return b.toString();
    }
}