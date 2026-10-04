package com.fpssync;

/**
 * {@code Window#swapBuffers()} süresini toplar.
 *
 * <h2>Neden ölçülüyor</h2>
 * Geç kalmanın iki ayrı sebebi vardır ve bugüne kadar ayırt edilemiyordu:
 * <ul>
 *   <li>oyun iş parçacığı geç kaldı (CPU tarafı),</li>
 *   <li>iş parçacığı zamanında bitti ama GPU kareyi hazırlayamadı (GPU tarafı).</li>
 * </ul>
 *
 * <p>V-Sync kapalıyken {@code glfwSwapBuffers} normalde yüzlerce mikrosaniyede döner;
 * kuyruk doluysa veya GPU gerideyse <b>bloklar</b>. Bu yüzden swap süresi "GPU bizi mi
 * bekliyor" sorusunun doğrudan cevabıdır:
 *
 * <pre>
 *   swap ≈ 200 µs  → GPU yetişiyor; geç kalmanın sebebi CPU/sınırlayıcı
 *   swap ≈ 16 ms   → swap kare süresi kadar blokluyor; GPU darboğaz
 *   swap ≈ 200 ms  → GPU belirgin şekilde geride
 * </pre>
 *
 * <h2>Neden sınıf içinde sayaçlar</h2>
 * Zaman damgaları {@link #begin(long)} ve {@link #end(long)} ile <em>dışarıdan</em>
 * verilir; sınıf hiçbir zaman kaynağını kendisi okumaz. Böylece üretim yolu
 * ({@code System.nanoTime()}) ile test yolu (sabit sayılar) ayrılır ve ölçüm davranışı
 * gerçek zamana bağlı olmadan sınanabilir.
 *
 * <h2>Sıfır ayak izi</h2>
 * {@link #drain()} tek bir kayıt döndürür; kare başına ayırma, boxing veya tahsis
 * yapılmaz. Kuyruk {@code -1} ile "başlangıç yok" durumunu tutar.
 */
final class SwapTimer {

    /** Biriken swap süresi toplamı. */
    private long nsTotal;

    /** Ölçülen swap çağrısı sayısı. */
    private long entries;

    /** En kötü tek swap süresi. */
    private long maxNs;

    /** Başlayan ama henüz bitmeyen swap'ın zaman damgası; yoksa -1. */
    private long pendingNs = -1L;

    /**
     * Swap çağrısı başladı.
     *
     * <p>Yeni başlangıç, bekleyen başlangıcın üzerine yazar. Bu bir hata değil,
     * savunmadır: başlangıçsız bitişin uydurma bir süre üretmesini engeller.
     *
     * @param startNs {@code nanoTime} değeri
     */
    void begin(long startNs) {
        pendingNs = startNs;
    }

    /**
     * Swap çağrısı bitti.
     *
     * <p>Başlangıç yoksa <b>hiçbir şey sayılmaz</b>: yalnız bitiş bilmek ölçüm için
     * yeterli değildir, süre çıkarılamaz.
     *
     * @param endNs {@code nanoTime} değeri
     */
    void end(long endNs) {
        if (pendingNs < 0L) {
            return;
        }
        long d = endNs - pendingNs;
        pendingNs = -1L;
        if (d < 0L) {
            return;
        }
        nsTotal += d;
        entries++;
        if (d > maxNs) {
            maxNs = d;
        }
    }

    /** @return ölçülen swap çağrısı sayısı */
    long entries() {
        return entries;
    }

    /** @return biriken swap süresi toplamı, nanosaniye */
    long nsTotal() {
        return nsTotal;
    }

    /** @return en kötü tek swap süresi, nanosaniye */
    long maxNs() {
        return maxNs;
    }

    /**
     * Biriken ölçümleri sıfırlar.
     *
     * <p>Kare sonunda bir kez çağrılır. Çağıran, sıfırlamadan <em>önce</em>
     * {@link #nsTotal()}, {@link #entries()} ve {@link #maxNs()} değerlerini okumalıdır.
     *
     * <p><b>Bilerek bir kayıt döndürmüyor.</b> Bir {@code Drain} record'u her karede
     * ayırma (allocation) yapardı; bu sınıf ölçüm yolunda <b>sıfır ayak izi</b> kuralına
     * tabidir (bkz. {@code FramePacingZeroAllocationTest}). Değerler ayrı okunur, tek
     * {@code Drain} nesnesi kare başına bir kez bile yaratılmaz.
     */
    void reset() {
        nsTotal = 0L;
        entries = 0L;
        maxNs = 0L;
        pendingNs = -1L;
    }
}
