package com.fpssync;

/**
 * "FPS Sync kaydırıcısı neden görünmüyor?" sorusunun üç durumlu cevabı.
 *
 * <h2>Neden ayrı bir sınıf</h2>
 * Bu karar saf bir fonksiyondur ve <b>zamanlamaya bağlıdır</b>. 2026-10-02'de yanlış
 * zamanda sorulduğu için oyun, slider görünmesine rağmen "kaydırıcı eklenemedi"
 * uyarısı bastı. Kararı burada tek yerde, test edilebilir biçimde tutmak, aynı hatayı
 * tekrar etmeyi zorlaştırır.
 *
 * <h2>Doğru sinyal ne</h2>
 * Ölçüt <b>"Sodium'un slider metodu çalıştı mı"</b> değil,
 * <b>"karıştırma hedefe uygulandı mı"</b>dır.
 *
 * <p>Sodium ayarlar ekranı, config'i kurulurken inşa edilir. Karıştırma
 * başarıyla uygulandıysa ve kullanıcı Sodium ayarlarını açarsa slider orada
 * <em>kesinlikle</em> görünür — o an geldiğinde {@code @Inject} tetiklenir. Buna
 * karşılık {@code CLIENT_STARTED} anında henüz tetiklenmemiş olur, çünkü Sodium
 * config'i {@code MinecraftClient.onInitFinished} sonunda kuruyor. Yani
 * "çalıştı mı" bayrağı <b>daima geç</b> söyler ve uyarı <b>her açılışta</b> basılır.
 *
 * <p>Uygulama başarısız olsaydı (hedef sınıf bulunamadı, ordinal değişti) slider hiç
 * görünmezdi. O da Mixin'in kendi uyarısını basar; yine de kullanıcıya anlatmak için
 * bu durum ayrıca ele alınır.
 *
 * @see com.fpssync.mixin.SodiumPresenceMixinPlugin
 */
public enum SodiumSliderStatus {

    /** Sodium kurulu değil. Beklenen durum; kullanıcı bilgilendirilir. */
    SODIUM_ABSENT,

    /** Slider, kullanıcı Sodium ayarlarını açtığında görünecek. Sessiz geçilir. */
    MIXIN_APPLIED,

    /** Slider hiç görünmeyecek. Gerçek arıza; kullanıcı uyarılır. */
    MIXIN_NOT_APPLIED;

    /**
     * Durumu belirler.
     *
     * @param sodiumPresent Sodium kurulu mu
     * @param mixinApplied {@code SodiumFpsLimitMixin} hedefe uygulandı mı
     */
    public static SodiumSliderStatus decide(boolean sodiumPresent, boolean mixinApplied) {
        if (!sodiumPresent) {
            // Bayrak yanlışlıkla true olsa bile burada "arıza" demeyiz: Sodium yoksa
            // slider'ın görünmemesi beklenen bir durumdur.
            return SODIUM_ABSENT;
        }
        return mixinApplied ? MIXIN_APPLIED : MIXIN_NOT_APPLIED;
    }

    /** Gerçek arıza mı — yalnız bu durumda uyarı basılır. */
    public boolean isFailure() {
        return this == MIXIN_NOT_APPLIED;
    }

    /** Sodium'ın yokluğu mu — yalnız bu durumda bilgi basılır. */
    public boolean isSodiumAbsent() {
        return this == SODIUM_ABSENT;
    }
}