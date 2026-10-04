# Changelog

Bu dosya sürüm tarihçesini tutar. Biçim: [Keep a Changelog](https://keepachangelog.com/tr/1.1.0/).

## 1.3.0 — 2026-10-03

Park'a güvenmeyi bırakır. Bekleme maliyeti kare başına 4.330 µs'tan (bir çekirdeğin
%18'i) çok daha aşağı iner.

### Düzeltilen

- **Bekleme artık park tekrarıyla yapılıyor.** Park karelerin yarısında istenenden
  7,69 ms **erken** dönüyordu ve kalan sürenin tamamı `Thread.onSpinWait()` ile
  yanıyordu. Kare süresi baştan doğruydu (park + spin sabit); pahalı olan bekleme
  biçimiydi. Artık kalan süre spin penceresinin üstünde olduğu sürece park tekrar
  denenir, yalnızca son 0,1 ms spin ile kapanır.
  - Park'ın ne kadar güvenilir olduğunu **bilmeye gerek yok**: erken dönerse,
    zamanında dönerse ya da geç dönerse kalan süre üçünde de kapanır.
  - İlk deneme girişte zaten okunmuş saat değerini kullanır; sağlıklı bir kare
    **ek nanoTime çağrısı harcamaz**.
  - Döngüyü bitiren ölçüt deneme sayısı değil, **çağrının kendisine değer etmesi**:
    bir park çağrısı süreden bağımsız ~45 µs CPU harcar, oysa 45 µs'tan az süre
    veren çağrı spin ile geçirilecek süreden pahalıdır.
- **Erken dönüşlerin uyuma süresi ayrı histogramlanıyor.** Toplam park süresi iki
  popülasyonu karıştırıyordu: isteneni tam uyuyan çağrılar ve istenenin %12'sini
  uyuyanlar. Ortalama hangisinin baskın olduğunu söylemiyor.
- **Park aşımı artık birden çok çağrı boyunca toplanıyor** (her çağrı için atama
  yerine birikim). Üretimde her kare sonunda sıfırlandığı için doğru.

### Rapora eklenen

- `park erken uyku  medyan … · p05 … · p95 …` — erken dönen çağrıların dağılımı.
  Park tekrarının kaç deneme yapmaya değer olduğunu bu belirler.
- `kare başına …` — ortalama park çağrısı. Güvenlik ağının gerçekten işe yarayıp
  yaramadığı **ölçülür**, tahmin edilmez.

### Bilinen sınır

`1.2.0`'da park'ın neden erken döndüğü bilinmiyordu. Bu sürüm sonucu çözmez, **maliyeti
azaltır**: park tekrarı park'ın erken dönme miktarından bağımsız çalışır. Erken
dönüşlerin uyuma dağılımı bu sürümde raporlanır; dağılım bilinince park tekrarının
kaç deneme yapması gerektiği veriyle seçilebilir.

Ayrıca park'ın erken dönüşünün üç olası açıklaması bu sürümde **elenmedi**: kesinti
bayrağı (`1.2.0`'da sayılmaya başlandı, oynanışta hiç set olmadığı görüldü), NTP
saat düzeltmesi (kare başına etkili değil) ve panel kararsızlığı (panel 60,0591 Hz
ölçüldü, kararlı). Kalan olasılıklar çekirdek/iş parçacığı zamanlayıcısı
davranışı ve modun bulunduğu ortam etkileşimi.

## 1.2.0 — 2026-10-02

Ölçüm: `/fpsync status`. Sınırlayıcının gerçekten ne yaptığını oyun içinde
görebilmek için.

### Eklenen

- **`/fpsync status`** — kare zamanlamasının dökümünü üretir. Raporu oyun
  dizinindeki `fps-sync/` klasörüne yazar, panoya kopyalamayı dener ve sohbete
  dosya yolu ile kısa özet basar.
  - `/fpsync status` — rapor + sayaçları sıfırlar
  - `/fpsync status keep` — rapor, sayaçlar korunur
  - `/fpsync status reset` — yalnız sıfırlar
- İki rejim ayrı raporlanır: **sınırlayıcı beklerken** ve **oyun hedefe
  ulaşmadığı için boşta**. Boşta geçen süre, modun müdahale etmediği
  kareleri gösterir.
- Rapor sürümü `fabric.mod.json`'dan okur. Ayrı bir sabit tutulmaz; ikinci bir
  kaynak olsaydı güncelleme unutulup rapor yanlış sürümü gösterebilirdi.

### Düzeltilen

- **Rapor dosya adı çakışması.** Zaman damgası saniye çözünürlüğündeydi; aynı
  saniyede üretilen iki rapor aynı yola yazılıyor ve ikincisi birincisini
  eziyordu. Raporlar karşılaştırmak için var, ikisi de kalmalı. Milisaniye
  eklendi ve ada çakışırsa numara veriliyor.
- **Park aşımı ölçülemiyordu.** Aşım histogramı 1 ms'de doyordu; üstü sessizce
  düşüyordu. Gerçek koşuda en kötü aşım 35,8 ms çıktı, yani kararın verileceği
  aralık tamamen kayıptı. Tavan 64 ms'e ve 10 µs adıma çıkarıldı, taşma sayacı
  rapora girdi.
- **"Ölçemedim" ile "tam bu değer" ayrımı yoktu.** Yüzdelik, histogram dışına
  düştüğünde son kovanın değerini döndürüyordu; 1 ms'lik tavonda bu tam olarak
  `1000.0 µs` ediyordu. Artık açık bir işaret dönüyor ve nedeni yazılıyor.
- **Yüzdelik paydası yanlıştı.** Erken dönen park çağrıları histograma girmiyor
  ama toplam çağrı sayısından sayılıyordu; erken dönüş olan her koşuda medyan ve
  p95 okunmuyordu. Payda artık dağılımın gerçekten üzerinden tanımlandığı geç
  dönüşler.
- **Park'ın erken dönüşü görünmüyordu.** Erken dönüşler sıfıra yassılanıyordu;
  kare başına 3,5 ms CPU'nun neden oluştuğu görünemiyordu. Artık işaretli
  taşınıyor ve ayrı sayılıyor.
- **Spin etiketi yanlıştı.** "Kare başına" yazıyordu ama spin girişleriyle
  bölüyordu. Artık ne ölçtüğünü söylüyor.
- **Sodium slider uyarısı yanlış pozitif veriyordu.** Slider gerçekten
  kurulduğu hâlde "bulunamadı" deniyordu; uyarı, slider'ın uygulanıp
  uygulanmayacağı belli olmadan önce gösteriliyordu.

### Düzeltilen (Sodium)

- **Sodium opsiyonel.** Mod, Sodium kurulu değilken de çalışıyor.
- Sodium 0.8 uyumluluğu: `SodiumConfigBuilderMixin` alanları 0.8'de taşınmış.

### Ölçüm ve dokümantasyon

- `/fpsync status` çıktısının tamamı artık okunabilir: gecikme ve aşım
  histogramları, park çağrısı sayısı, spin süresi, ilk bekleme anı, gerçek FPS.
- **Sınıf dokümantasyonundaki maliyet tablosu düzeltildi.** Tablo "kare başına
  60-67 µs CPU" diyor; bu yalnızca park istenen süreyi spin penceresi kadar
  aşarak döndüğünde geçerli. Oyun içinde bu koşul sağlanmıyor ve gerçek maliyet
  **3534 µs/kare** çıktı — bir çekirdeğin yaklaşık %17,5'i. Aynı tablodaki eski
  yol 1504 µs ile ölçülmüştü, yani güncel sürüm o yoldan daha pahalı.
- Bilinen sınır bu sürümde de geçerlidir: **spin penceresi 100 µs olmasına rağmen
  kare başına 3534 µs CPU harcanıyor**, çünkü park karelerin yarısında 6,93 ms
  erken dönüyor ve kalan süre spin ile yakılıyor. Bu bir sonraki sürümün konusu.

### Bilinen sınır

FPS Sync modu FPS'i yükseltmez; sadece **kareleri hedef hıza kilitler**. 60 Hz
hedefte, oyun 60 FPS üretemiyorsa mod yapabileceğini yapar; oyunun yavaşlığı
kaynağında kalır. Raporun "sınırlayıcı boşta" bölümü bu ayrımı gösterir.

## 1.1.0

> **Bu sürüm hiç dağıtılmadı.** Numara `gradle.properties` içinde yazılıydı ama
> Modrinth'te yayınlanmadı. Geriye dönük etiket kondu, yayın olarak işaretlenmedi.

## 1.0.0

İlk yayınlanan sürüm.