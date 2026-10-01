package com.fpssync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Meşgul bekleme döngüsünün güvenlik sınırlarını sınar.
 *
 * <p><b>Neden ayrı bir test.</b> Spin penceresi yalnız zamanlama değil, CPU
 * maliyetidir de; maliyetin tamamı döngünün gövdesindedir. Gövde
 * {@code Thread.onSpinWait()} taşıdığı sürece bu işlemcide dönüş başına 61 ns
 * ölçüldü (bkz. {@code FrameLimiterSpinCostProbe}), gövde boşken 40 ns. Bu testler
 * gövdeyi değiştirmeyi güvenli kılmak içindir: döngü sonsuza kadar dönmemeli,
 * pencereyi aşmamalı ve beklenen sayıda tur atmalı.
 *
 * <p><b>Bu bir kırmızı-yeşil döngüsü değildir.</b> Üretim kodundaki
 * {@code Thread.onSpinWait()} kaldırılması <em>gözlenebilir davranış
 * değiştirmez</em> — sanal saat zaten {@code spinHook} ile ilerlediği için bu
 * testler kaldırma öncesi de yeşildi. Kırmızı bir ürün hatası yaratmak mümkün
 * değil; ölçüm ayrı dosyada. Buradaki testler güvenlik ağıdır, değişikliğin
 * gerekçesi değil.
 */
class FrameLimiterSpinLoopTest {

    private static final long SPIN_TICK_NS = 1_000L;
    private static final long PARK_OVERSHOOT_NS = 90_000L;

    /**
     * {@code Thread.onSpinWait()} kaldırılsa da döngü kapanmalı ve pencereyi
     * aşmamalı.
     *
     * <p>Pencerenin 100 µs olduğu ve park aşımının 90 µs olduğu bu sanal saatte
     * geçerli; kalan 10 µs spin ile geçmeli. Gövde yanlışlıkla bloklansa (ör.
     * {@code onSpinWait} yerine yanlışlıkla bir bekleme konulsa) dönüş sayısı ya
     * çok küçük ya çok büyük olurdu.
     */
    @Test
    @DisplayName("Spin penceresi 10 µs: gövde ne olursa olsun dönüş sayısı sabit")
    void turnCountIsDeterminedByTheWindowNotByTheBody() {
        List<Long> turns = new ArrayList<>();

        for (int hz : new int[]{60, 120, 144, 240, 360}) {
            FrameLimiter limiter = FrameLimiter.INSTANCE;
            long[] clock = {0};
            int[] hookCalls = {0};

            limiter.reset();
            limiter.nanoTime = () -> clock[0];
            limiter.sleeper = ns -> clock[0] += ns + PARK_OVERSHOOT_NS;
            limiter.spinHook = () -> { clock[0] += SPIN_TICK_NS; hookCalls[0]++; };
            limiter.onSpin = spin -> { };
            limiter.setEnabled(true);
            limiter.setMonitorRefreshRate(hz);

            long budget = 1_000_000_000L / hz;
            // lastFrameTime == 0 sentbeli: saat 0'da başlatmak ikinci kareyi de
            // başlatma karesi yapar. Saat sıfırdan uzak başlat.
            clock[0] = 1_000_000L;
            limiter.limitFrame();

            clock[0] += budget / 5;
            hookCalls[0] = 0;
            limiter.limitFrame();
            turns.add((long) hookCalls[0]);
        }

        // Pencere 100 µs, park aşımı 90 µs -> 10 µs spin -> 1 µs/tur -> 10 tur.
        // Bütçe ne olursa olsun kalan hep 10 µs olduğu için sayı değişmez.
        for (int i = 0; i < turns.size(); i++) {
            assertEquals(10L, turns.get(i),
                    "Dönüş sayısı " + turns.get(i) + " (hepsi=" + turns + "). "
                            + "Pencere 100 µs ve park aşımı 90 µs ise kalan süre her "
                            + "hedefte 10 µs'dur, yani hep 10 tur beklenir. Sapma "
                            + "gövdenin kalan süreyi değiştirdiğini gösterir.");
        }
    }

    /**
     * Gövde boşaltıldığında döngü hâlâ kapanır: yalnız saat okuması ilerler.
     *
     * <p>Bu, {@code onSpinWait} kaldırıldıktan sonraki üretim yolunun güvenlik
     * kontrolüdür. Sanal saatte döngü yalnız kancayla kapanır (kanca saati
     * ilerletir); kanca da ilerletmezse döngü sonsuz olurdu. Bu yüzden sanal
     * saatte gerçek üretim yolu doğrudan çalıştırılamaz — kancayı kaldırıp
     * bütçeyi aşan bir kare zorlayarak "döngüye hiç girilmiyor" yolunu kanıtlıyoruz.
     */
    @Test
    @DisplayName("Döngüye hiç girilmediğinde kare beklenen bütçeyi kullanıyor")
    void frameSkipsTheLoopWhenBudgetIsAlreadyMet() {
        FrameLimiter limiter = FrameLimiter.INSTANCE;
        long[] clock = {0};
        int[] parks = {0};

        limiter.reset();
        limiter.nanoTime = () -> clock[0];
        limiter.sleeper = ns -> { parks[0]++; clock[0] += ns + PARK_OVERSHOOT_NS; };
        // Kanca hiçbir şey iletmez: sanal saatte döngü sonsuza kadar dönerdi.
        limiter.spinHook = () -> { };
        limiter.onSpin = spin -> { };
        limiter.setEnabled(true);
        limiter.setMonitorRefreshRate(60);

        clock[0] = 1_000_000L;
        limiter.limitFrame();                        // başlatma

        clock[0] += 1_000_000_000L / 60L;            // tam bütçe: kare zamanında
        limiter.limitFrame();                        // döngüye girmemeli, ilerlemeli

        assertEquals(0, parks[0],
                "Bütçeye tam eşit karede park bekleniyordu; test ön koşulu bozuldu.");
        assertTrue(clock[0] >= 2_000_000L,
                "Saat ilerlememiş; döngüye girilmiş olabilir.");
    }

    /**
     * Gövde yavaş olsa bile dönüş sayısı pencerenin belirlediği sınırı aşmamalı.
     *
     * <p>Gövdeye yavaş bir iş eklendiğinde (kancada olduğu gibi) döngü daha az döner
     * ama <b>asla</b> pencerenin ötesine geçmez, çünkü kancayla ilerleme tur başına
     * sabit. Bu, gövde değişikliklerinin pencereyi bozmayacağının güvencesi.
     */
    @Test
    @DisplayName("Yavaşlayan gövde pencereyi aşmıyor")
    void slowBodyDoesNotExceedTheWindow() {
        for (int tickNs : new int[]{1, 10, 100, 1_000}) {
            FrameLimiter limiter = FrameLimiter.INSTANCE;
            long[] clock = {0};
            long[] spin = {0};

            limiter.reset();
            limiter.nanoTime = () -> clock[0];
            limiter.sleeper = ns -> clock[0] += ns + PARK_OVERSHOOT_NS;
            limiter.spinHook = () -> clock[0] += tickNs;
            limiter.onSpin = s -> spin[0] = s;
            limiter.setEnabled(true);
            limiter.setMonitorRefreshRate(60);

            long budget = 1_000_000_000L / 60;
            clock[0] = 1_000_000L;
            limiter.limitFrame();

            clock[0] += budget / 5;
            spin[0] = 0;
            limiter.limitFrame();

            assertTrue(spin[0] > 0 && spin[0] <= 100_000,
                    "Gövde başına " + tickNs + " ns ilerleme: spin " + spin[0]
                            + " ns oldu, beklenen aralık (0, 100000] ns. Gövde "
                            + "pencereyi aşıyor ya da hiç dönmüyor.");
        }
    }
}
