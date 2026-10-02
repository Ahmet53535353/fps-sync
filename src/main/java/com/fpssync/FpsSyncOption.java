package com.fpssync;

/**
 * FPS limit seçeneğinin sentinel sözleşmesi — modun <b>tek</b> kaynağı.
 *
 * <p>Bu değerler daha önce beş dosyada elle kopyalanmıştı: vanilla slider
 * ({@code GameOptionsMixin}), Sodium slider ({@code SodiumFpsLimitMixin}), pencere
 * dönüşümü ({@code WindowMixin}), başlangıç okuması ({@code FpsSyncMod}) ve
 * sınırlayıcının kendisi ({@code FrameLimiter}). {@code -10} ve {@code 1010} gibi
 * anlaşılmaz sayılar biri unutulduğunda iki ayar yolu (vanilla ve Sodium) sessizce
 * ayrışıyor ve kullanıcı hangi ekrandan baktığına göre farklı davranış görüyordu.
 *
 * <p>Değerler <b>korunmuştur</b>; bu birleştirme davranışı değiştirmez. Kilit testi:
 * {@code FpsSyncOptionContractTest}.
 *
 * <h2>Sözleşme</h2>
 * <ul>
 *   <li>{@code <= 0} → <b>FPS Sync</b>: hedef monitörün gerçek yenileme hızıdır.</li>
 *   <li>{@code 1 … 1009} → <b>manuel FPS sınırı</b>.</li>
 *   <li>{@code >= 1010} → <b>sınırsız</b>.</li>
 * </ul>
 *
 * <h2>Bilinen çelişki: vanilla aralığı</h2>
 * Minecraft 1.21.1 vanilla {@code maxFps} seçeneğini {@code Codec.intRange(10, 260)}
 * ile doğrulayan bir codec ile saklar. Bu mod {@link #UNLIMITED} değeri olarak
 * {@code 1010} kullandığı için vanilla sınırını aşar; 360/500 Hz monitörlerde
 * elle sınır koyabilmek için bu bilinçle yapılmıştır.
 *
 * <p><b>Sonucu:</b> {@code options.txt} yazılırken vanilla codec'i bu değeri
 * reddedip anahtarı hiç yazmayabilir, okunurken ise
 * {@code Value -10 outside of range [10:260]} hatası basar. Ölçülmüş kanıt:
 * {@code logs/latest.log} ve {@code options.txt}'te {@code framerateLimit} anahtarının
 * bulunmaması. Düzeltme ayrı iş olarak ele alınır; sentinel değerleri burada
 * değiştirilmez, çünkü değiştirmek 360 Hz desteğini geri alır.
 */
public final class FpsSyncOption {

    /** {@code options.txt}'te FPS Sync'i temsil eden değer. Vanilla alt sınırının altında. */
    public static final int SYNC = -10;

    /** {@code options.txt}'te sınırsız FPS'i temsil eden değer. Vanilla üst sınırının üstünde. */
    public static final int UNLIMITED = 1010;

    /** Kayan çubuğun en sol konumu: FPS Sync. */
    public static final int SLIDER_MIN = 0;

    /** Kayan çubuğun en sağ konumu: sınırsız. */
    public static final int SLIDER_MAX = 101;

    /** Elle seçilebilen en küçük FPS. */
    public static final int MANUAL_MIN = 10;

    /** Elle seçilebilen en büyük FPS (slider'ın son manuel konumu). */
    public static final int MANUAL_MAX = 1000;

    /** Oyun başlangıcında seçenek bu değere döner. */
    public static final int DEFAULT_FPS = 120;

    /** Codec alt sınırı — {@link #SYNC} değerini kapsamak zorunda. */
    public static final int CODEC_MIN = SYNC;

    /** Codec üst sınırı — {@link #UNLIMITED} değerini kapsamak zorunda. */
    public static final int CODEC_MAX = UNLIMITED;

    /** Kayan çubuğun adımı. */
    public static final int STEP = 10;

    private FpsSyncOption() {
    }

    /** Değer FPS Sync'i mi temsil ediyor? */
    public static boolean isSync(int value) {
        return value <= 0;
    }

    /** Değer sınırsız FPS'i mi temsil ediyor? */
    public static boolean isUnlimited(int value) {
        return value >= UNLIMITED;
    }

    /** Değer elle FPS sınırı mı? */
    public static boolean isManual(int value) {
        return value > 0 && value < UNLIMITED;
    }

    /**
     * Kayan çubuk konumunu option değerine çevirir.
     *
     * <p>En sol konum {@link #SYNC}, en sağ konum {@link #UNLIMITED}; ara konumlar
     * 10'ar artar, böylece 360/500 Hz monitörler için 1010'a kadar erişilir.
     */
    public static int sliderToValue(int sliderPos) {
        if (sliderPos <= SLIDER_MIN) {
            return SYNC;
        }
        if (sliderPos >= SLIDER_MAX) {
            return UNLIMITED;
        }
        return sliderPos * STEP;
    }

    /** Option değerini kayan çubuk konumuna çevirir. */
    public static int valueToSlider(int value) {
        if (value <= 0) {
            return SLIDER_MIN;
        }
        if (value >= UNLIMITED) {
            return SLIDER_MAX;
        }
        return Math.min(value / STEP, SLIDER_MAX - 1);
    }

    /**
     * Sınırlayıcıya verilecek manuel limiti döndürür.
     *
     * <p>Sınırsız sentinel'i 0'a indirir; çünkü sınırlayıcı 0'ı "sınırsız" sayar.
     */
    public static int manualLimitOrZero(int value) {
        return isUnlimited(value) ? 0 : value;
    }

    /**
     * Değeri {@code Window.setFramerateLimit} girdisine çevirir.
     *
     * <p>Sync ve sınırsız durumda pencere sınırsıza alınır: mod kare hızını kendisi
     * yönetiyor, vanilla'nın ayrıca sınırlaması gereksiz ve çakışıcı olurdu.
     */
    public static int toWindowLimit(int value) {
        return (isSync(value) || isUnlimited(value)) ? Integer.MAX_VALUE : value;
    }
}