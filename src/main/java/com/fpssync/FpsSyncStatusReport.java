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

    /**
     * Bir park çağrısının CPU maliyeti için kabaca tahmin: <b>45 µs</b>.
     *
     * <p>Kaynak: {@code FrameLimiterCpuProbe} (çevrimdışı), 1 ms'de 24 µs, 5 ms'de
     * 45 µs, 15 ms'de 51 µs, 50 ms'de 53 µs. Süreye büyük ölçüde bağlı değil; sabit
     * bir gider.
     *
     * <p><b>Bu ölçülmüş bir oyun içi değerdir, değildir.</b> Raporda "tahmin" diye
     * yazılır. Park'ın CPU'su çekirdekte geçer ve yalnız boş bir JVM'de ölçülebilir;
     * oynanışta ölçülemez.
     */
    private static final long PARK_CPU_ESTIMATE_NS = 45_000L;

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
                b.append("  park gerçek ").append(parkElapsedLine(r)).append('\n');
                b.append("  park aşımı  ").append(overshootLine(r)).append('\n');
                appendInterruptLine(b, r);
                b.append("               dağılım ")
                        .append(r.activeOvershootLateCalls()).append(" geç dönüş üzerinden")
                        .append(", ").append(r.activeParkEarlyCalls())
                        .append(" erken dönüş sayılmadı\n");
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
          appendDuration(b, r.elapsedNs());
          b.append("  gerçek FPS        ").append(num(r.actualFps(), 1))
                  .append("  (kare ÷ geçen süre)\n");
          appendCpu(b, r.totals());
          b.append("  limiter devrede   ").append(percent(100.0 * active / total)).append('\n');
          b.append("  ilk bekleme       ").append(r.firstWaitAtNs() < 0
                  ? "henüz olmadı"
                  : num(r.firstWaitAtNs() / 1_000_000_000.0, 1) + " sn sonra").append('\n');
          appendEarlyWindow(b, r);
          return b.toString();
      }

    /**
     * Ölçüm penceresinin uzunluğunu yazar.
     *
     * <p>Süre olmadan "spin toplam 286.461,85 ms" tek başına bir anlam taşımaz: aynı
     * toplam 5 dakikalık oturumda çok ağır, 30 dakikalık oturumda hafif görünür.
     * Okuyucunun bölme yapmasına gerek bırakmamak için açıkça yazılır.
     */
    private static void appendDuration(StringBuilder b, long elapsedNs) {
          long sec = elapsedNs / 1_000_000_000L;
          b.append("  süre              ")
                  .append((int) (sec / 60)).append(':')
                  .append(String.format(Locale.ROOT, "%02d", sec % 60))
                  .append("  (").append(sec).append(" sn)\n");
      }

    /**
     * Sınırlamanın <b>CPU payını</b> yazar.
     *
     * <p>Spin, modun baskın maliyetidir: bekleme sırasında işlemci yanmayan tek
     * yoldur, park ise uyur. Bu yüzden "spin ne kadar" sorusu "mod ne kadar CPU
     * yakıyor" sorusunun cevabıdır.
     *
     * <p>Park çağrılarının kendisi de CPU harcar (bir syscall). Bu <b>ölçülmüyor</b>;
     * çevrimdışı koprobeden gelen kabaca bir çağrı başına 45 µs varsayılır ve
     * olduğu gibi "tahmin" diye yazılır. Ölçülmüş gibi sunulmaz.
     */
    private static void appendCpu(StringBuilder b, FramePacingRecorder.Totals t) {
          if (t.elapsedNs() <= 0) {
              return;
          }
          double spinPct = 100.0 * t.spinNsTotal() / t.elapsedNs();
          long parkCostNs = t.parkCalls() * PARK_CPU_ESTIMATE_NS;
          double parkPct = 100.0 * parkCostNs / t.elapsedNs();
          b.append("  spin             ")
                  .append(duration(t.spinNsTotal())).append("  =  bir çekirdeğin ")
                  .append(percent(spinPct)).append("\n");
          b.append("  park maliyeti    ~").append(duration(parkCostNs))
                  .append("  =  ~").append(percent(parkPct))
                  .append("  (TAHMİN, çevrimdışı koprobe)\n");
          b.append("  ── modun CPU'su  ≈  bir çekirdeğin ")
                  .append(percent(spinPct + parkPct)).append("\n");
      }

    /**
     * İlk {@link FramePacingRecorder#EARLY_WINDOW_NS} dakikanın özeti.
     *
     * <p>Koşular arasındaki farkı ölçülebilir kılan sabit pencere. Toplamlar
     * karşılaştırılamaz: art arda üç koşuda CPU payı %17,0 → %17,5 → %25,1 çıktı ve
     * kod değişmedi, değişen oynanan içerikti.
     *
     * <p>Kısa oturumlarda pencere oluşmamıştır; o durumda uydurma değer basılmaz,
     * yalnızca ne kadar oynandığı yazılır.
     */
    private static void appendEarlyWindow(StringBuilder b, FramePacingRecorder r) {
          FramePacingRecorder.Totals e = r.earlyWindowTotals();
          b.append('\n');
          if (e == null) {
              long sec = r.elapsedNs() / 1_000_000_000L;
              b.append("■ İLK 10 DAKİKA — dolmadı (oturum ").append(sec)
                      .append(" sn); karşılaştırma için en az 600 sn gerekir.\n");
              return;
          }
          long total = e.totalFrames();
          b.append("■ İLK 10 DAKİKA (0–600 sn) — koşular arası karşılaştırma için\n");
          b.append("  kare ").append(total)
                  .append("  ·  gerçek FPS ").append(num(e.fps(), 1))
                  .append("  ·  limiter devrede ")
                  .append(percent(100.0 * e.waitingFrames() / total)).append('\n');
          b.append("  geç kare           ")
                  .append(percent(100.0 * e.lateFrames() / e.waitingFrames()))
                  .append('\n');
          if (e.parkCalls() > 0) {
              b.append("  park              ").append(e.parkCalls())
                      .append(" çağrı · istenen ortalama ")
                      .append(ms(e.parkNsTotal() / e.parkCalls())).append('\n');
              b.append("  erken dönüş        ").append(e.parkEarlyCalls())
                      .append("  (")
                      .append(percent(100.0 * e.parkEarlyCalls() / e.parkCalls()))
                      .append(", ortalama ")
                      .append(ms(e.parkEarlyNsTotal() / e.parkEarlyCalls()))
                      .append(" erken)\n");
          }
          if (e.spinEntries() > 0) {
              b.append("  spin              ")
                      .append(us(e.spinNsTotal() / e.spinEntries()))
                      .append("/kare  =  bir çekirdeğin ")
                      .append(percent(100.0 * e.spinNsTotal() / e.elapsedNs()))
                      .append("\n");
          }
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
    /**
     * Kesinti teşhisini yazar.
     *
     * <p>Park üç koşuda ikiye bölünmüştü (yarısı tam süre, yarısı onda biri) ve
     * {@code p05} sıfırdı. En olası açıklama: interrupt bayrağı set kaldığında
     * {@code LockSupport.parkNanos} anında döner. Bu satır o varsayımı doğrular ya da
     * reddeder; <b>teşhistir, düzeltme değildir</b> — bayrağa dokunulmaz.
     *
     * <p>Sayaçlar sıfır çıkarsa varsayım yanlıştır ve park-tekrarı tek başına yeter.
     */
    private static void appendInterruptLine(StringBuilder b, FramePacingRecorder r) {
        long flagged = r.interruptFlagFrames();
        long caught = r.interruptsCaught();
        if (flagged == 0 && caught == 0) {
            b.append("  kesinti        yok (park anında bayrak set olmadı)\n");
            return;
        }
        long park = r.activeParkCalls();
        b.append("  kesinti        park anında bayrak SET: ").append(flagged).append(" kare");
        if (park > 0) {
            b.append(" (").append(percent(100.0 * flagged / park)).append(')');
        }
        b.append(" · yakalanan InterruptedException: ").append(caught).append('\n');
    }

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

    /**
     * Park'ın <em>gerçekte</em> uyuduğu süreyi biçimlendirir.
     *
     * <p>İstenen süreyle karşılaştırılabilmesi için ikisi yan yana basılır: park
     * istenenden kısa uyuyorsa bekleme yapmamış, kalan süre spin ile yakılmış demektir.
     * 2026-10-02 koşusunda karelerin yarısında fark 6,93 ms'ydı ve bu satır olmadan
     * neden görünmüyordu.
     */
    private static String parkElapsedLine(FramePacingRecorder r) {
        long p95 = r.activeParkElapsedPercentileNs(0.95);
        long p05 = r.activeParkElapsedPercentileNs(0.05);
        if (r.activeParkElapsedCalls() == 0) {
            return "ölçülmedi";
        }
        StringBuilder b = new StringBuilder(80);
        b.append("uyudu medyan ").append(value(r.activeParkElapsedMedianNs()))
                .append(" · p05 ").append(value(p05))
                .append(" · p95 ").append(value(p95));
        if (r.activeParkElapsedOverflow() > 0) {
            b.append(" · tavan dışı ").append(r.activeParkElapsedOverflow());
        }
        return b.toString();
    }

    private static String value(long ns) {
        return ns == FramePacingRecorder.SATURATED ? "ölçülemedi" : us(ns);
    }

    /**
     * Süreyi okunabilir biçimde yazar: 10 saniyenin altında milisaniye, üstünde saniye.
     *
     * <p>"170262.56 ms" okunması zor bir toplamdı; oran hesaplamak için süreye
     * ihtiyaç duyulan yerlerde sayı zaten daha anlamlı.
     */
    private static String duration(long ns) {
        return ns >= 10_000_000_000L
                ? num(ns / 1_000_000_000.0, 1) + " sn"
                : ms(ns);
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