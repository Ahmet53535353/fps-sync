<p align="center">
    <img src="https://github.com/ItsPasi/fps-sync/blob/1.21.11/docs/Mod%20Icon%20Animation%20Small.webp?raw=true">
    <h1 align="center">FPS-Sync</h1>
</p>

FPS-Sync automatically caps your framerate to your monitor's refresh rate. This is different to V-Sync and doesn't add input lag to the game.

The mod also allows for custom frame limits up to 1000 FPS.

The FPS slider in Minecraft now includes a new "FPS-Sync" option at the leftmost position. Select it to enable the mod.

<img src="https://github.com/ItsPasi/fps-sync/blob/1.21.11/docs/Settings%20Menu.png?raw=true">

## Requirements

- Minecraft Java Edition (Fabric)
- Fabric API
- **Sodium is optional.** FPS Sync works without it. If Sodium is installed, the same
  entry is also added to Sodium's own video settings page for convenience.

## Troubleshooting

**"I can't find FPS Sync."**
Go to Options → Video Settings → **Frame Rate Limit**. The slider's leftmost position is
"FPS Sync". It replaces the vanilla slider: the rightmost position means unlimited.

**My setting isn't remembered between launches.**
It should be. If it is not, delete `options.txt` and set it again — a corrupted or
hand-edited `options.txt` can make Minecraft drop the value.

**It locked to the wrong refresh rate.**
Automatic detection uses Minecraft's own monitor-selection logic, which can mispick a
monitor in multi-monitor or HiDPI setups. This is a limitation of Minecraft's detection,
not specific to this mod. Move the window so it sits fully inside the monitor you want.

**Does it conflict with V-Sync or with other limiters?**
Turn V-Sync off: FPS Sync works by replacing V-Sync's pacing. Running another FPS limiter
at the same time will fight it.

---

## Sürüm geçmişi

Sürüm notları: [`docs/CHANGELOG.md`](docs/CHANGELOG.md)

## Ölçüm: `/fpsync status`

Oyun içinde `/fpsync status` yazarak kare zamanlamasının dökümünü alabilirsin:

- iki rejimi ayrı raporlar — sınırlayıcı **beklerken** ve oyun hedefe ulaşmadığı
  için **boşta**
- raporu `fps-sync/` klasörüne yazar, panoya kopyalamayı dener, dosya yolunu
  sohbete basar
- `/fpsync status keep` sayaçları sıfırlamaz, `/fpsync status reset` yalnız sıfırlar

Araç kare zamanlamasını ölçer, **FPS'i yükseltmez.** Oyun hedef hızı üretemiyorsa
mod yapabileceğini yapar; "boşta" bölümü bu ayrımı gösterir.
