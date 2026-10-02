package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "FPS Sync slider'ı görünmüyor" uyarısının **ne zaman basılması gerektiğini**
 * belirleyen saf kararı kilitler.
 *
 * <h2>Bu test bir hatanın düzeltmesinden doğdu</h2>
 * 2026-10-02'de gerçek oyunda şu görüldü: {@code SodiumFpsLimitMixin} sorunsuz
 * uygulandı, {@code @Inject} ve iki {@code @Redirect} de eşleşti, hiç injector hatası
 * yok — ve yine de oyun "kaydırıcı eklenemedi" uyarısı bastı. Gerçekte slider
 * **görünüyordu**; uyarı yanlış pozitifti.
 *
 * <p><b>Sebep:</b> uyarı, uyaranın <em>çalıştığı anda</em> bayrağa bakıyordu.
 * Oysa Sodium config'ini {@code MinecraftClient.onInitFinished} sonunda kuruyor;
 * {@code CLIENT_STARTED}'dan sonraki bir anda. Yani bayrak o anda henüz
 * {@code false} idi — her seferinde.
 *
 * <p>Doğru sinyal "injector uygulandı mı"dır, "buildGeneralPage çalıştı mı" değil:
 * karıştırma başarıyla uygulandıysa, kullanıcı Sodium ayarlarını açtığında slider
 * <em>kesinlikle</em> orada olacaktır.
 */
class SodiumSliderStatusTest {

    @Test
    @DisplayName("Sodium kurulu değilse: bilgi durumu (arıza değil)")
    void absentSodiumIsNotAFailure() {
        assertEquals(SodiumSliderStatus.SODIUM_ABSENT,
                SodiumSliderStatus.decide(false, false));
    }

    @Test
    @DisplayName("Sodium kurulu değilse ama bayrak true ise yine bilgi durumu")
    void absentSodiumIgnoresTheFlag() {
        // Savunmacı davranış: bayrak yanlışlıkla true ise de "arıza" demeyelim.
        assertEquals(SodiumSliderStatus.SODIUM_ABSENT,
                SodiumSliderStatus.decide(false, true));
    }

    @Test
    @DisplayName("Sodium kurulu ve mixin uygulandıysa: sessiz, uyarı yok")
    void appliedMixinIsSilent() {
        // Asıl düzeltme: bu, önceden yanlış uyarı basılan durum.
        assertEquals(SodiumSliderStatus.MIXIN_APPLIED,
                SodiumSliderStatus.decide(true, true));
    }

    @Test
    @DisplayName("Sodium kurulu ama mixin uygulanmadıysa: gerçek arıza")
    void notAppliedMixinIsARealFailure() {
        assertEquals(SodiumSliderStatus.MIXIN_NOT_APPLIED,
                SodiumSliderStatus.decide(true, false));
    }

    @Test
    @DisplayName("yalnızca gerçek arıza durumu uyarı basar")
    void onlyRealFailureWarns() {
        assertTrue(SodiumSliderStatus.decide(true, false).isFailure());
        assertTrue(!SodiumSliderStatus.decide(true, true).isFailure());
        assertTrue(!SodiumSliderStatus.decide(false, false).isFailure());
    }

    @Test
    @DisplayName("yalnızca Sodium yokken bilgi basılır")
    void onlyAbsentSodiumInforms() {
        assertTrue(SodiumSliderStatus.decide(false, false).isSodiumAbsent());
        assertTrue(!SodiumSliderStatus.decide(true, true).isSodiumAbsent());
        assertTrue(!SodiumSliderStatus.decide(true, false).isSodiumAbsent());
    }
}