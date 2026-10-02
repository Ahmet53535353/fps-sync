package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Raporun diske yazılmasını kilitler.
 *
 * <p>Asıl komut Minecraft'a bağlıdır; dosya yazma mantığı taban dizin parametresiyle
 * ayrıldığı için geçici dizinde gerçekten çalıştırılabilir. Böylece "rapor dosyaya
 * düşüyor mu" sorusu oyuna girmeden cevaplanır.
 */
class FpsSyncStatusCommandFileTest {

    private static final String REPORT = "FPS Sync raporu\ntest satırı\n";

    @Test
    @DisplayName("zaman damgalı dosya ve latest.txt yazılır")
    void writesTimestampedFileAndLatest(@TempDir Path dir) {
        FpsSyncStatusCommand.Result r = FpsSyncStatusCommand.writeToDisk(REPORT, dir);

        assertTrue(r.ok(), "yazma başarısız olmamalı");
        assertTrue(Files.exists(r.path()), "zaman damgalı dosya oluşmalı");

        Path latest = dir.resolve("fps-sync").resolve("latest.txt");
        assertTrue(Files.exists(latest), "latest.txt oluşmalı");
        assertEquals(REPORT, read(latest));
        assertEquals(REPORT, read(r.path()));
    }

    @Test
    @DisplayName("dosya adı zaman damgası taşır")
    void fileNameCarriesTimestamp(@TempDir Path dir) {
        FpsSyncStatusCommand.Result r = FpsSyncStatusCommand.writeToDisk(REPORT, dir);

        String name = r.path().getFileName().toString();
        assertTrue(name.startsWith("status-"), "ad 'status-' ile başlamalı: " + name);
        assertTrue(name.endsWith(".txt"), "uzantı .txt olmalı: " + name);
        // Uzunluk kırılgandır: ad çakışırsa numara eklenir. Desen sabittir.
        // status-YYYYMMDD-HHmmss-SSS.txt, isteğe bağlı -N çakışma numarası.
        assertTrue(name.matches("status-\\d{8}-\\d{6}-\\d{3}(-\\d+)?\\.txt"),
                "ad deseni bozuk: " + name);
    }

    @Test
    @DisplayName("klasör yoksa oluşturulur")
    void createsDirectory(@TempDir Path dir) {
        Path gameDir = dir.resolve("yeni-oyun-dizini");

        FpsSyncStatusCommand.Result r = FpsSyncStatusCommand.writeToDisk(REPORT, gameDir);

        assertTrue(r.ok());
        assertTrue(Files.isDirectory(gameDir.resolve("fps-sync")));
    }

    @Test
    @DisplayName("ikinci çağrı latest.txt'yi günceller, ilk rapor dosyasını korur")
    void latestIsOverwritten(@TempDir Path dir) throws Exception {
        FpsSyncStatusCommand.Result firstResult = FpsSyncStatusCommand.writeToDisk("birinci\n", dir);
        String firstName = firstResult.path().getFileName().toString();
        FpsSyncStatusCommand.Result second = FpsSyncStatusCommand.writeToDisk("ikinci\n", dir);

        assertEquals("ikinci\n", read(dir.resolve("fps-sync").resolve("latest.txt")));
        // Zaman damgalı ilk dosya korunur — iki koşuyu karşılaştırabilmek için.
        // Aynı milisaniyede çağrılırsa ad çakışır; o durumda numara eklenir.
        Path first = firstResult.path();
        assertEquals("birinci\n", read(first), "ilk rapor dosyası ezilmemeli");
        assertTrue(!first.equals(second.path()) || firstName.contains("-1"),
                "ad çakışırsa numara eklenmeli");
    }

    @Test
    @DisplayName("yazılamazsa fırlatmaz, yalnızca başarısız döner")
    void doesNotThrowWhenUnwritable(@TempDir Path dir) throws Exception {
        // Dizin yerine dosya koymak: alt dizin oluşturulamaz.
        Path notADir = dir.resolve("dosya");
        Files.writeString(notADir, "x");

        FpsSyncStatusCommand.Result r = FpsSyncStatusCommand.writeToDisk(REPORT, notADir);

        assertFalse(r.ok(), "yazılamamalıydı");
    }

    @Test
    @DisplayName("boş rapor da yazılır — çökmez")
    void emptyReportIsStillWritten(@TempDir Path dir) {
        FpsSyncStatusCommand.Result r = FpsSyncStatusCommand.writeToDisk("", dir);

        assertTrue(r.ok());
        assertEquals("", read(r.path()));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("dosya okunamadı: " + p, e);
        }
    }
}