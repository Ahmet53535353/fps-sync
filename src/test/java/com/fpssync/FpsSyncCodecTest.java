package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kurucunun FPS Sync seçeneğinin <b>kendi</b> codec'inin sentinel değerlerini kabul
 * ettiğini doğrular.
 *
 * <h2>Neden bu test var</h2>
 * Gerçek oyun logunda şu satır çıkıyor:
 * <pre>
 * Error parsing option value -10 for option Max Framerate: Value -10 outside of range [10:260]
 * </pre>
 * Bu hata <b>vanilla'nın</b> codec'inden geliyor ({@code intRange(10, 260)}), modunkinden
 * değil: mod {@code Codec.intRange(-10, 1010)} kuruyor. O hata yalnızca, vanilla'nın
 * {@code load()} çağrısı bizim seçeneğimiz kurulmadan <em>önce</em> çalıştığı için oluşuyor.
 *
 * <p>2026-10-02'de bu satır yüzünden "ayar kayboluyor" sonucu çıkarıldı; oysa
 * {@code maxFps:-10} dosyada duruyordu. Yanlış bulgunun sebebi: log satırındaki
 * "Max Framerate" ifadesi <em>ekran etiketinin</em> adıdır, dosyadaki anahtar
 * ({@code maxFps}) değil. Bu test, hatanın bizden değil vanilladan geldiğini
 * sabitleyen bir güvenlik ağıdır.
 *
 * @see FpsSyncOption
 */
class FpsSyncCodecTest {

    /** Modun kurduğu codec — GameOptionsMixin ile birebir aynı ifade. */
    private static final Codec<Integer> MOD_CODEC =
            Codec.intRange(FpsSyncOption.CODEC_MIN, FpsSyncOption.CODEC_MAX);

    /** Vanilla 1.21.1'in kullandığı codec — logdaki hatanın kaynağı. */
    private static final Codec<Integer> VANILLA_CODEC = Codec.intRange(10, 260);

    @Test
    @DisplayName("modun codec'i FPS Sync sentinel'ini kabul eder")
    void modCodecAcceptsSyncSentinel() {
        // Yazma yolu: bu değer options.txt'e -10 olarak yazılmış oluyor.
        assertTrue(canEncode(MOD_CODEC, FpsSyncOption.SYNC));
    }

    @Test
    @DisplayName("modun codec'i sınırsız sentinel'ini kabul eder")
    void modCodecAcceptsUnlimitedSentinel() {
        assertTrue(canEncode(MOD_CODEC, FpsSyncOption.UNLIMITED));
    }

    @Test
    @DisplayName("modun codec'i 360/500 Hz için gerekli yüksek elle limitleri kabul eder")
    void modCodecAcceptsHighManualLimits() {
        // Vanilla üst sınırı 260; 360 Hz monitörde 360'a, bazılarında 500'e ihtiyaç var.
        for (int v : new int[]{360, 500, 1000}) {
            assertTrue(canEncode(MOD_CODEC, v), "v=" + v);
        }
    }

    @Test
    @DisplayName("vanilla codec'i sentinel'leri reddediyor — logdaki hatanın kaynağı bu")
    void vanillaCodecIsTheOneThatRejects() {
        // Sentinel'lerin neden var olduğunu belgeleyen test: vanilla aralığı dışında
        // oldukları için ilk yüklemede reddedilirler. Modun range'i genişletmesi
        // bu çatışmanın nedenidir.
        assertFalse(canEncode(VANILLA_CODEC, FpsSyncOption.SYNC));
        assertFalse(canEncode(VANILLA_CODEC, FpsSyncOption.UNLIMITED));
    }

    @Test
    @DisplayName("vanilla codec'i normal FPS değerlerini kabul ediyor")
    void vanillaCodecAcceptsNormalValues() {
        for (int v : new int[]{10, 60, 144, 260}) {
            assertTrue(canEncode(VANILLA_CODEC, v), "v=" + v);
        }
    }

    @Test
    @DisplayName("modun codec'i vanilla'nın kabul ettiği her değeri de kabul ediyor")
    void modCodecIsASupersetOfVanilla() {
        for (int v = 10; v <= 260; v++) {
            assertTrue(canEncode(MOD_CODEC, v), "v=" + v);
        }
    }

    // --- yardımcılar ---

    /**
     * Codec'in değeri <b>yazıp</b> yazamadığını sorar.
     *
     * <p>Yazma yolu seçildi çünkü logdaki hata tam olarak burada oluşuyor:
     * Minecraft ayarı kaydederken codec'i çalıştırıyor, sentinel vanilla aralığının
     * dışında olduğu için yazma başarısız oluyor. Okuma yolu ayrı bir soru ve
     * modun kurduğu codec onu zaten sorunsuz yapıyor.
     *
     * @return {@code true} yazılabilirse
     */
    private static boolean canEncode(Codec<Integer> codec, int value) {
        try {
            return codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, value)
                    .result()
                    .isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
