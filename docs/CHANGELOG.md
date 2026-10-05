# Changelog

Bu dosya sürüm tarihçesini tutar. Biçim: [Keep a Changelog](https://keepachangelog.com/tr/1.1.0/).

## 1.6.0 — 2026-10-05

Taban koşusunu ("mod kapalıyken oyun ne kadar iyi") **ölçülebilir** hâle getirir ve
karışık oturumu raporun kendisi geçersiz ilan eder. Üretim davranışı değişmiyor.

### Düzeltilen — sayaç

- **"Faydalı retry" oranı %100'ü geçiyordu.** Gerçek bir koşuda rapor
  `665/646 faydalı (%102,9)` dedi; bu matematiksel olarak imkânsız. Sebep: başarısız
  denemeden sonraki *her* başarılı çağrı sayılıyordu, karede tek retry olsa bile.
  Artık **yalnız ilk** faydalı çağrı sayılıyor.
  - Bu, daha önce okunan iki oranı da (`%81,6` ve `%75,3`) şişiriyordu. Karar yine
    doğruydu — spin ölçümü retry'in işe yaradığını gösteriyor — ama sayı "retry ne
    kadar tuttu" değildi.

### Düzeltilen — etiketler

- **Başlık monitör hızını yazıyordu, sınırlayıcının hedefini değil.** Elle 30 FPS
  sınırı varken "hedef 60 Hz" diyordu. Artık ikisi ayrı: `sınırlayıcı hedefi 50 Hz ·
  panel 60 Hz`, sınırsızda `SINIRSIZ`.
- **`Sodium slider: var/yok` yanlış bilgi veriyordu.** Alan aslında
  `sync || !sodiumPresent` idi: sync kapalıyken "yok" diyordu, slider gayet iyi olsa
  bile; sync açıkken "var" diyordu, slider hiç uygulanmamış olsa bile. Gerçek
  enjeksiyon durumu (`sliderInjected`) hiç raporlanmıyordu. Artık gerçek durum
  yazılıyor: `uygulandı` / `uygulanmadı`.

### Yeni — boşta rejim ölçümleri

Taban koşusu sınırlayıcı **çalışmadığı** rejimde toplanır. Veri zaten dolduruluyordu
(kare süresi histogramı her kare için her iki rejimde de yazılıyor) ama erişimciler
yalnız bekleyen rejimi okuduğu için rapor burayı tek satırla geçiyordu:
`ortalama kare süresi 40.38 ms`. Artık boşta rejimde de:

- `1% low · 0.1% low (p99 · p99.9)` — "mod kapalıyken pürüzsüzlük ne"
- `swap` ortalaması, en kötüsü, bütçe yüzdesi
- `swap × geç kalma` çapraz tablosu ve GPU hükmü

**Bu olmadan taban koşusu karar vermeye yetmiyordu.**

### Yeni — GEÇERSİZ OTURUM uyarısı

Gerçek bir koşu: ilk ~25 saniye FPS Sync (oyun ~60 FPS), sonra sınırsız (oyun ~25 FPS).
Rapor `gerçek FPS 31,0` dedi — iki tamamen farklı iş yükünün ortalaması, yani hiçbir
şey. Kullanıcı "sınırsız oynadım" diyordu, kayıtların çoğu sınırlayıcıyla toplanmıştı.

Artık sınırlayıcı her karede fiilen hedefini çözüyor; hedef değişince sayaç artıyor ve
rapor şunu basıyor:

```
⚠ GEÇERSİZ OTURUM — sınır durumu 1 kez değişti
```

Doğru koşu talimatı da raporun içinde: durumu değiştirme, tek koşuyu tamamla, sonra
`/fpsync status`.

### Bilinen sınır

Çapraz tablo bir **iterasyon** kayması taşır: swap, `GameRenderer` TAIL'inden sonra
gerçekleştiği için kare N'in gönderimiyle N'in sunumu farklı iterasyonlardır. Dağılım
için bu bir kayma değildir — ikisi de aynı döngüde ölçülen olaydır — ama tek bir kare
için neden-sonuç iddiası kurulamaz.

### Doğrulama

**239 test**, build + javadoc yeşil, **11 mutasyon kanıtlandı**.

Dört mutasyon ilk denemede **kaçtı** ve üçü gerçek test boşluğuydu:
- kanca bağlantısı hiç sınanmamıştı (test `bindTargetChangeListener`'ı *kendi*
  çağırıyordu; `onInitializeClient`'daki çağrı silinse de geçiyordu)
- hedef değişimi tespiti hiç sınanmamıştı
- ilk karenin "değişim" sayılmadığı hiç sınanmamıştı
- slider etiketi testi `sync` ve `slider` aynı değer olduğu için iki hatalı durumu
  ayırt edemiyordu

Kanca testi önce referans karşılaştırmasıyla yazıldı; bu da işe yaramadı çünkü
`FpsSyncMod::onTargetChanged` her çağrıda yeni nesne üretir. Doğru sınama
**davranışsal**: başlat, hedefi değiştir, kayıtçının saydığını gör.

## 1.5.0 — 2026-10-05

Üretim davranışı **değişmiyor**; yalnız ölçüm ve raporlama. 1.4.0'ın ilk gerçek koşusunun
raporunu okurken iki hata bulundu: biri benim hatalı eşiğim, biri eski bir bölme hatası.

### Düzeltilen

- **Swap eşiği yanlış yerdeydi ve yanlış alarm üretti.** 1.4.0'ın ilk koşusunda rapor şunu
  dedi: `swap ortalama 1.629,8 µs · en kötü 12,78 ms · GPU darboğazı olası`. Eşik
  **en kötü** değere konmuştu ve 12,78 ms onu tetikledi; oysa ortalama 16,67 ms bütçenin
  **%9,8'i**'ydi. Tek bir aykırı kare tüm sistemik maliyeti gizledi.
  Artık karar **ortalamaya** bakar ve bütçeyle karşılaştırılır:
  `< %5` → GPU etkisiz · `%5–25` → sunum bedeli, darboğaz değil · `> %25` → GPU darboğazı
  olası. En kötü değer hâlâ raporlanır — bilgi kaybı yok, yalnız kararın dayanağı değişti.
- **Raporda üç bölme hatası.** `parkEarlyCalls`, `waitingFrames` ve `elapsedNs` paydaları
  sıfır olabiliyordu.
  - `parkEarlyNsTotal / parkEarlyCalls` korumasız bölüyordu ve **ArithmeticException**
    atıyordu. Bu bir kenar durum değil: 1.4.0'dan sonra tam olarak beklenen durum, çünkü
    retry park'ın erken dönüşünü düzeltiyor ve sağlıklı bir koşuda erken dönüş sayısı
    sıfıra yaklaşıyor.
  - `lateFrames / waitingFrames` çarpımı double olduğu için **çökmez**, `Infinity` basar.
    Daha kötü: rapor `Infinity%` yazar ve kullanıcı bunu ölçüm sanar. Sınırlayıcı hiç
    çalışmadığında (`waitingFrames == 0`) bu olur.
  - Satırın ikinci koruması savunma amaçlı ve **er işilemez**; testle kanıtlanamıyor.
    Yanlış güvence izlenimi vermemek için kodda not düşüldü.

### Rapora eklenen

- **Swap bütçe yüzdesiyle yazılıyor.** "1,63 ms" zihinsel bölme ister, "%9,8" istemez.
  Mutlak mikrosaniye karar vermeye de yetmez: 16,67 ms bütçede 2 ms ciddi, 240 ms bütçede
  önemsizdir.
- **Swap × geç kalma çapraz tablosu:**
  ```
  swap         ortalama 1630 µs  (bütçenin %9,8)  ·  en kötü 12.78 ms  ·  sunum bedeli, darboğaz değil
               geç karelerde 4210 µs  (%25,3)  ·  zamanında 1180 µs  (%7,1)
               ↳ GPU geç kareleri açıklıyor
  ```
  Geç kalmanın iki ayrı sebebi vardır ve genel ortalama ikisini karıştırır: iş parçacığı
  geç kaldı (CPU) ya da iş parçacığı zamanında bitti ama GPU kareyi hazırlayamadı.
  Swap kare tipine göre ayrı toplanınca bu **ayırt edilebilir** olur. Hüküm yalnız iki
  taraf da doluyken ve oran 2'den büyükken **ve** geç karelerdeki pay en az %15 iken
  verilir — aksi halde ölçümün verdiğini aşmak olurdu.

### Bilinen sınır

Çapraz tablo bir **iterasyon** kayması taşır: swap, `GameRenderer` TAIL'inden sonra
gerçekleştiği için kare N'in gönderimiyle N'in sunumu farklı iterasyonlardır. Dağılım
için bu bir kayma değildir — ikisi de aynı döngüde ölçülen "geç kaldı / zamanında"
olayıdır — ama tek bir kare için neden-sonuç iddiası kurulamaz.

### Doğrulama

**226 test**, build + javadoc yeşil, **11 mutasyon kanıtlandı** (bölme korumaları, iki
eşik, çapraz tablo tarafı, tek taraflı veri, sayaç toplama, bütçe saklama).
Kaçmayan tek mutasyon `elapsedNs` korumasının kaldırılmasıydı: o koruma erişilemez
olduğu için kanıtlanamaz, kodda bunun yerine not var.

## 1.4.0 — 2026-10-04

1.3.0'ın park tekrarı işe yaramadı: kare başına park çağrısı 1,21'de kaldı, yani
döngü çoğu karede o karedeki uzun uykuyu hiç görmeden vazgeçti. Bu sürüm o yanlış
kararı düzeltir ve park'ın erken dönüşünün iki modlu olduğunu rapora koyar. Ayrıca iki
ölçüm eksikti: pürüzsüzlük şiddeti ve geç kalmanın CPU'dan mı GPU'dan geldiği.

### Düzeltilen

- **Fayda koruması artık bir çağrıyı tek başına yargılamıyor.** 1.3.0 şu kuralı
  koyuyordu: "bir park çağrısı 45 µs'tan az uyuduysa tekrar deneme, spin'e düş."
  Gerçek dağılım tek değil, **iki modlu**:
  `erken uyku medyan 0,0 µs · p05 0,0 µs · p95 6.740 µs`. Karelerin yarısı hiç uyumadan
  dönüyor, bir kısmı 6,7 ms'ye kadar uyuyor. Kural ikinci popülasyonu hiç görmediği
  için o karelerdeki 6,7 ms'lik fırsatı da reddetti.
  Artık bir başarısız çağrıdan sonra **tam bir kez daha** denenir, sonra kare kapanır.
  - Beklenen değer hesabı: gider `45 µs`, kazanç `0,05 × 6.740 ≈ 337 µs`. Yani başına
    45 µs harcamaya ~337 µs kazanç — guard'ın reddettiği her karede katlanarak pozitif.
  - İki koşulda vazgeçilir: zaten bir kez denendiyse, ya da istenen süre zaten 45 µs'un
    altındaysa (kuyruğun sonundaki çağrılar sayacı boşa kirletmesin).
  - Kare süresi **değişmez**: `park + spin` toplamı sabit, ek gider yalnızca CPU.

### Rapora eklenen

- `başarısız park tekrarı X/Y faydalı (… %) · ortalama … kazanç · 45 µs gideri aşıyor`
  — bu satır tek başına **karar verdirir**: oran %3'ün üstündeyse kural kalır, %1'in
  altındaysa geri alınır. Tahmin değil, ölçüm.
- `1% low … · 0.1% low … (p99 … · p99.9 …)` — endüstrinin standart pürüzsüzlük ölçütü.
  `geç kare %` bir *sayı* veriyor, *şiddet* vermiyordu: 1 ms'lik tırtıklama ile 40 ms'lik
  duraklama aynı sayıda geç karedir.
  - Bunun için **kare süresi histogramı** eklendi. Mevcut gecikme histogramı yalnız
    *geç* kareleri tutar, zamanında kalanlar hiç girmez; 1% low kare süresinin tüm
    dağılımına bakar, yani mevcut histogramdan **türetilemez**.
  - Yüzdelikler kova **orta noktası** ile hesaplanır. Mevcut `percentile()` kova alt
    sınırını döndürür; gecikme için bu bir tercihtir ("ölçemedim" ile "tam bu değer"
    ayrımını silmemek için) ama kare süresinden FPS türetilir ve alt sınır süreyi eksik
    saydığı için FPS'i **gerçekten yüksek** gösterirdi.
  - Bilinen sınır: `q=0,999` rank `ceil(0,999·N)`'dir. Az örnekli bir oturumda tek bir
    aykırı kare bu rank'in üstünde kalır ve 0,1% low'a giremez. Bu bir hata değil tanımın
    sonucudur; rapor örnek sayısını da yazdığında sınır görünür olur.
- `swap ortalama … · en kötü … · N ölçüm · GPU yetişiyor / GPU darboğazı olası` —
  `Window#swapBuffers()` süresi. Geç kalmanın iki ayrı sebebi vardır ve bugüne kadar
  ayırt edilemiyordu: iş parçacığı geç kaldı (CPU) ya da iş parçacığı zamanında bitti
  ama GPU kareyi hazırlayamadı. V-Sync kapalıyken swap yüzlerce µs'de döner, kuyruk
  doluysa **bloklar** — yani bu doğrudan GPU darboğaz göstergesidir.
  - Yalnız ölçüm: HEAD/RETURN kancaları, sunum çağrısına dokunulmaz. Kare başına iki
    `nanoTime` eklenir (~58 ns, kare bütçesinin %0,0004'ü).

### Elenen hipotez

Çevrimdışı koprobe ile park'ın **sistemde** sorunu olmadığı doğrulandı: 8 ms istek
için `LockSupport.parkNanos` medyan −0,001 ms, 1 ms'den fazla erken dönüş **%0,0**.
Sorun sistemde veya JVM'de değil, **oyunun render iş parçacığında**. Elde edilen
araçlar (`FrameLimiterCpuProbe`, `FrameLimiterCostProbe`) boş bir JVM'de çalıştığı için
bu boşluğu yapısal olarak göremiyor; bu yüzden yeni bir komut değil, sınırlayıcının
kendi içindeki ölçüm genişletildi.

### Bilinen sınır — karar henüz verilmedi

**Bu sürümün davranış değişikliğinin etkisi ölçülmedi.** Üç sayı da eklendi tam olarak
bunun için: kural işe yararsa spin düşer, yaramazsa en kötü %0,14 CPU ek gider. Beklenen
değer pozitif ama gerçek koşu verisi yok. Karar raporun `başarısız park tekrarı X/Y`
satırından verilecek.

Ölçüm maliyeti ~%0,0004; retry'nin en kötü hali ~%0,14. Beklenen kazanç ise spin tamamen
çöktüğünde kare başına ~4.360 µs, yani bir çekirdeğin dörtte biri.

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