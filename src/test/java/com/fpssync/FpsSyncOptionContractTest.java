package com.fpssync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FPS limit seçeneğinin sentinel sözleşmesini kilitler.
 *
 * <p>Bu sözleşme modun beş ayrı dosyasında kullanılıyordu: vanilla slider
 * {@code GameOptionsMixin}, Sodium slider {@code SodiumFpsLimitMixin}, pencere
 * dönüşümü {@code WindowMixin}, başlangıç okuması {@code FpsSyncMod} ve sınırlayıcının
 * kendisi {@code FrameLimiter}. Değerler {@code -10}, {@code 1010} ve eşleyiciler
 * elle kopyalanmıştı; biri unutulursa iki ayar yolu sessizce ayrışıyordu.
 * {@link FpsSyncOption} tek kaynak olsun diye eklendi.
 *
 * <p>Buradaki testler <b>davranışı değiştirmez</b>, mevcut davranışı kilitler: kaynak
 * değerler her dosyadan birebir kopyalandı. Bu yüzden düzeltme öncesi de yeşildirler —
 * kırmızı görmeyi gerektiren bir ürün hatası yoktur, çünkü ürün hatası yoktur; yapısal
 * risk gideriliyor. Testlerin gerçekten bağlı olduğu, kural 7 gereği sabitler
 * bilerek bozularak gösterildi.
 */
class FpsSyncOptionContractTest {

    @Nested
    @DisplayName("Sınıflandırma: hangi değer ne anlama geliyor")
    class Classification {

        @Test
        @DisplayName("sıfır ve altı FPS Sync'tir, manuel limit değil")
        void zeroOrBelowIsSync() {
            assertTrue(FpsSyncOption.isSync(-10));
            assertTrue(FpsSyncOption.isSync(0));
            assertTrue(FpsSyncOption.isSync(-1));
        }

        @Test
        @DisplayName("1010 ve üstü sınırsızdır")
        void sentinelAndAboveIsUnlimited() {
            assertTrue(FpsSyncOption.isUnlimited(FpsSyncOption.UNLIMITED));
            assertTrue(FpsSyncOption.isUnlimited(FpsSyncOption.UNLIMITED + 1));
        }

        @Test
        @DisplayName("sınırlar arası tek bir değer ne Sync ne sınırsızdır")
        void betweenBoundariesIsNeither() {
            for (int v = 1; v < FpsSyncOption.UNLIMITED; v++) {
                assertFalse(FpsSyncOption.isSync(v), "v=" + v);
                assertFalse(FpsSyncOption.isUnlimited(v), "v=" + v);
            }
        }

        @Test
        @DisplayName("sınırsız değer FPS Sync'e karışmaz")
        void unlimitedIsNotSync() {
            // İki sınıfın örtüşmediği, sentinel çakışmasının olmadığının güvencesi.
            assertFalse(FpsSyncOption.isSync(FpsSyncOption.UNLIMITED));
            assertFalse(FpsSyncOption.isUnlimited(FpsSyncOption.SYNC));
        }

        @Test
        @DisplayName("1..1009 aralığı tam olarak manuel limit")
        void manualRangeIsExact() {
            assertTrue(FpsSyncOption.isManual(1));
            assertTrue(FpsSyncOption.isManual(60));
            assertTrue(FpsSyncOption.isManual(1000));
            assertTrue(FpsSyncOption.isManual(FpsSyncOption.UNLIMITED - 1));
            assertFalse(FpsSyncOption.isManual(0));
            assertFalse(FpsSyncOption.isManual(FpsSyncOption.UNLIMITED));
        }
    }

    @Nested
    @DisplayName("Kayan çubuk eşleyicisi: konum <-> değer")
    class SliderMapping {

        @Test
        @DisplayName("en sol konum FPS Sync, en sağ konum sınırsız")
        void extremesCarryTheSentinels() {
            assertEquals(FpsSyncOption.SYNC, FpsSyncOption.sliderToValue(FpsSyncOption.SLIDER_MIN));
            assertEquals(FpsSyncOption.UNLIMITED, FpsSyncOption.sliderToValue(FpsSyncOption.SLIDER_MAX));
        }

        @Test
        @DisplayName("Sync değeri en sola, sınırsız değer en sağa geri döner")
        void sentinelsRoundTripToExtremes() {
            assertEquals(FpsSyncOption.SLIDER_MIN, FpsSyncOption.valueToSlider(FpsSyncOption.SYNC));
            assertEquals(FpsSyncOption.SLIDER_MAX, FpsSyncOption.valueToSlider(FpsSyncOption.UNLIMITED));
        }

        @Test
        @DisplayName("her konum bir değere gider ve geri döner")
        void everyPositionRoundTrips() {
            for (int pos = FpsSyncOption.SLIDER_MIN; pos <= FpsSyncOption.SLIDER_MAX; pos++) {
                int value = FpsSyncOption.sliderToValue(pos);
                assertEquals(pos, FpsSyncOption.valueToSlider(value), "pos=" + pos + " value=" + value);
            }
        }

        @Test
        @DisplayName("her kabul edilen değer bir konuma gider")
        void everyValueMapsToPosition() {
            for (int v = FpsSyncOption.CODEC_MIN; v <= FpsSyncOption.CODEC_MAX; v++) {
                int pos = FpsSyncOption.valueToSlider(v);
                assertTrue(pos >= FpsSyncOption.SLIDER_MIN && pos <= FpsSyncOption.SLIDER_MAX, "v=" + v);
            }
        }

        @Test
        @DisplayName("eşleyici 10'ar adım ilerler, kare değer üretmez")
        void mappingStepsByTen() {
            assertEquals(10, FpsSyncOption.sliderToValue(1));
            assertEquals(600, FpsSyncOption.sliderToValue(60));
            assertEquals(1000, FpsSyncOption.sliderToValue(100));
        }
    }

    @Nested
    @DisplayName("Dönüşümler: option değeri -> sınırlayıcı ve pencere girdisi")
    class Conversions {

        @Test
        @DisplayName("sınırsız değer sınırlayıcıya 0 olarak iner")
        void unlimitedBecomesZero() {
            assertEquals(0, FpsSyncOption.manualLimitOrZero(FpsSyncOption.UNLIMITED));
            assertEquals(0, FpsSyncOption.manualLimitOrZero(FpsSyncOption.UNLIMITED + 50));
        }

        @Test
        @DisplayName("manuel değer kendiliğinden geçer")
        void manualPassesThrough() {
            for (int v = 1; v < FpsSyncOption.UNLIMITED; v++) {
                assertEquals(v, FpsSyncOption.manualLimitOrZero(v));
            }
        }

        @Test
        @DisplayName("Sync ve sınırsız, pencereye sınırsız olarak gider")
        void sentinelsBecomeMaxValueForWindow() {
            assertEquals(Integer.MAX_VALUE, FpsSyncOption.toWindowLimit(FpsSyncOption.SYNC));
            assertEquals(Integer.MAX_VALUE, FpsSyncOption.toWindowLimit(0));
            assertEquals(Integer.MAX_VALUE, FpsSyncOption.toWindowLimit(FpsSyncOption.UNLIMITED));
        }

        @Test
        @DisplayName("manuel değer pencereye olduğu gibi gider")
        void manualPassesThroughToWindow() {
            assertEquals(60, FpsSyncOption.toWindowLimit(60));
            assertEquals(1000, FpsSyncOption.toWindowLimit(1000));
        }
    }

    @Nested
    @DisplayName("Sınırlamalar: değerler vanilla ile çelişmemeli")
    class Bounds {

        @Test
        @DisplayName("codec aralığı sentinel'ları kapsar")
        void codecRangeCoversSentinels() {
            assertTrue(FpsSyncOption.CODEC_MIN <= FpsSyncOption.SYNC);
            assertTrue(FpsSyncOption.CODEC_MAX >= FpsSyncOption.UNLIMITED);
        }

        @Test
        @DisplayName("slider sonu sınırsız değere tam olarak dayanır")
        void sliderMaxLandsOnUnlimited() {
            assertEquals(FpsSyncOption.UNLIMITED, FpsSyncOption.sliderToValue(FpsSyncOption.SLIDER_MAX));
        }

        @Test
        @DisplayName("vanilla üst sınırı 260; modun 1010 değerini aşması bilinçlidir")
        void vanillaMaxIsDeliberatelyExceeded() {
            // 1.21.1 vanilla maxFps codec'i intRange(10, 260). 260 FPS üstü
            // monitörler (360/500 Hz) için sınırlar genişletilmiştir. Bu değer
            // vanilla ile çelişir ve options.txt yazımını bozabildiği için
            // bilinçli bir karar: değiştirilirse README ve bu test güncellenmeli.
            assertEquals(1010, FpsSyncOption.UNLIMITED);
            assertTrue(FpsSyncOption.UNLIMITED > 260,
                    "vanilla üst sınırı aşılmış olmalı; aşılmıyorsa test güncellenmeli");
        }
    }
}