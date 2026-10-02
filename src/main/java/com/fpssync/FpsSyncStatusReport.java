package com.fpssync;

import java.util.Locale;

/**
 * {@code /fpsync status} raporunu metne çevirir. Saf dize üretimi; Minecraft sınıflarına
 * dokunmaz, böylece biçim gerçek ortam olmadan ve yerelleştirmeden bağımsız doğrulanır.
 *
 * <p>Sayılar nokta ile yazılır ({@link Locale#ROOT}). Kullanıcının Türkçe kurulumda
 * virgül görmesi istenir mi sorusu burada cevaplanmaz — ölçümün karşılaştırılabilirliği
 * nokta ayırıcıyla korunur, çünkü raporlar birbirine ve hata raporlarına kopyalanıyor.
 *
 * @see FpsSyncStatusCommand
 */
public final class FpsSyncStatusReport {

    private FpsSyncStatusReport() {
    }

      /**
       * Raporun tek kare anındaki girdisi. Saf veri: biçimlendirme burada olmaz.
       *
     * @param pacing       ölçüm kayıtçısı
     * @param syncEnabled  FPS Sync açık mı
     * @param monitorHz    algılanan monitör yenileme hızı
     * @param windowW      pencere genişliği
     * @param windowH      pencere yüksekliği
     * @param modVersion   mod sürümü
     * @param sodiumSlider FPS Sync girdisi Sodium ayarlarında görünüyor mu
     * @param lastExitCode önceki koşunun çıkış kodu (0 = sorun yok)
     */
    public record Snapshot(FramePacingRecorder pacing, boolean syncEnabled, int monitorHz,
                           int windowW, int windowH, String modVersion,
                           boolean sodiumSlider, int lastExitCode) {
    }

    /**
     * Raporu okunabilir metne çevirir.
     *
     * @param s anlık girdi
     * @return çok satırlı rapor metni
     */
    public static String render(Snapshot s) {
        FramePacingRecorder r = s.pacing();
        long active = r.activeFrames();
        long idle = r.idleFrames();
        long total = active + idle;

        StringBuilder b = new StringBuilder(1024);
        b.append("FPS Sync raporu\n");
        b.append("Mod ").append(s.modVersion())
                .append("  ·  FPS Sync ").append(s.syncEnabled() ? "AÇIK" : "kapalı")
                .append("  ·  hedef ").append(s.monitorHz()).append(" Hz (algılanan)\n");
        b.append("Pencere ").append(s.windowW()).append('x').append(s.windowH())
                .append("  ·  Sodium slider: ").append(s.sodiumSlider() ? "var" : "yok")
                .append('\n');

        if (total == 0) {
            b.append("\nHenüz kare kaydedilmedi. Oyunu biraz oyna, sonra tekrar dene.\n");
            return b.toString();
        }

        b.append("\n■ BEKLEYEN KARE (sınırlayıcı aktif) — ").append(active).append(" kare\n");
        if (active == 0) {
            b.append("  sınırlayıcı hiç beklemedi; oyun hedefe hiç ulaşmadı.\n");
        } else {
            double latePct = 100.0 * r.activeLateFrames() / active;
            b.append("  gecikme     medyan ").append(ms(r.activeLatenessMedianNs()))
                    .append(" · p95 ").append(ms(r.activeLatenessPercentileNs(0.95)))
                    .append(" · p99 ").append(ms(r.activeLatenessPercentileNs(0.99)))
                    .append(" · en kötü ").append(ms(r.activeLatenessMaxNs())).append('\n');
            b.append("  geç kare    ").append(percent(latePct))
                    .append("  (").append(r.activeLateFrames()).append(" kare)\n");
            if (r.activeParkCalls() > 0) {
                b.append("  park        ").append(r.activeParkCalls())
                        .append(" çağrı · istenen ortalama ")
                        .append(ms(r.activeParkNsTotal() / r.activeParkCalls())).append('\n');
                b.append("  park aşımı  ").append(overshootLine(r)).append('\n');
                if (r.activeOvershootOverflow() > 0) {
                    b.append("               ↳ tavan dışı ")
                            .append(r.activeOvershootOverflow()).append(" çağrı (")
                            .append(percent(100.0 * r.activeOvershootOverflow()
                                    / r.activeParkCalls()))
                            .append("), taşma sayısıyla raporlandı\n");
                }
                if (r.activeParkEarlyCalls() > 0) {
                    b.append("  park erken  ").append(r.activeParkEarlyCalls())
                            .append(" çağrı · ortalama ")
                            .append(ms(r.activeParkEarlyNsTotal() / r.activeParkEarlyCalls()))
                            .append(" erken döndü (kalanı spin ile yakıldı)\n");
                }
            }
            if (r.activeSpinEntries() > 0) {
                b.append("  spin        toplam ").append(ms(r.activeSpinNsTotal()))
                        .append(" · spin eden kare başına ")
                        .append(us(r.activeSpinNsTotal() / r.activeSpinEntries())).append('\n');
            }
        }

        b.append("\n■ BEKLEME YAPILMAYAN KARE (sınırlayıcı boşta) — ").append(idle).append(" kare\n");
        if (idle == 0) {
            b.append("  sınırlayıcı hedefi hep yakaladı.\n");
        } else {
            b.append("  ortalama kare süresi ").append(ms(r.idleElapsedNs() / idle)).append('\n');
            b.append("  not: oyun hedefe ulaşmadığı için sınırlayıcı hiç beklemedi.\n");
        }

        b.append("\nÖZET\n");
        b.append("  gerçek FPS        ").append(num(r.actualFps(), 1))
                .append("  (kare ÷ geçen süre)\n");
        b.append("  limiter devrede   ").append(percent(100.0 * active / total)).append('\n');
        b.append("  ilk bekleme       ").append(r.firstWaitAtNs() < 0
                ? "henüz olmadı"
                : num(r.firstWaitAtNs() / 1_000_000_000.0, 1) + " sn sonra").append('\n');
        return b.toString();
    }

    /**
     * Park aşımı dağılımını biçimlendirir.
     *
     * <p>Bir yüzdelik {@link FramePacingRecorder#SATURATED} dönüyorsa <b>sayı basılmaz</b>;
     * bunun yerine yüzdeliğin histogram dışına düştüğü açıkça yazılır. Daha önce
     * sessizce son kovanın değeri basılıyordu: 1 ms'lik tavonda medyan ve p95 için
     * tam olarak "1000.0 µs" çıkıyor, bu da "ölçemedim" ile "tam 1 ms" arasındaki
     * farkı siliyordu.
     */
    private static String overshootLine(FramePacingRecorder r) {
        long median = r.activeOvershootMedianNs();
        long p95 = r.activeOvershootPercentileNs(0.95);
        StringBuilder b = new StringBuilder(96);
        b.append("medyan ").append(value(median)).append(" · p95 ").append(value(p95));
        if (median == FramePacingRecorder.SATURATED || p95 == FramePacingRecorder.SATURATED) {
            b.append(" · en kötü ").append(us(r.activeOvershootMaxNs()))
                    .append(" (dağılım ölçüm aralığının dışında)");
        }
        return b.toString();
    }

    private static String value(long ns) {
        return ns == FramePacingRecorder.SATURATED ? "ölçülemedi" : us(ns);
    }

    private static String ms(long ns) {
        return num(ns / 1_000_000.0, 2) + " ms";
    }

    private static String us(long ns) {
        return num(ns / 1_000.0, 1) + " µs";
    }

    private static String num(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String percent(double v) {
        return num(v, 1) + "%";
    }
}