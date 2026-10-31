#!/usr/bin/env bash
# Buat ikon aplikasi dari sebuah gambar (JPG/PNG). Pakai: bash tools/make-icon.sh [/path/gambar.jpg]
# Default: icon/timeflow.jpg di dalam proyek (lalu ~/storage/downloads/timeflow.jpg bila ada).
# - Pinggiran polos di sekeliling gambar dibuang otomatis, ikon dipotong persegi dari tengah.
# - Sudut gambar dibulatkan (ikon yang berbentuk kotak membulat jadi tanpa tepi abu-abu). Matikan: TIMEFLOW_ICON_ROUND=0
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=${1:-${TIMEFLOW_ICON:-}}
if [ -z "$SRC" ]; then
  for c in icon/timeflow.jpg icon/timeflow.jpeg icon/timeflow.png "$HOME/storage/downloads/timeflow.jpg"; do [ -f "$c" ] && SRC=$c && break; done
fi
RES=android/app/src/main/res
[ -n "$SRC" ] && [ -f "$SRC" ] || { echo "Gambar ikon tidak ditemukan (taruh di icon/timeflow.jpg)"; exit 1; }
if command -v magick >/dev/null 2>&1; then IM=magick; elif command -v convert >/dev/null 2>&1; then IM=convert; else echo "ImageMagick belum ada. Jalankan: pkg install imagemagick"; exit 1; fi
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
# 1) buang pinggiran polos + sedikit bayangan tepi, lalu jadikan persegi 1024x1024 (potong tengah)
$IM "$SRC" -auto-orient -background white -alpha remove -alpha off -fuzz 4% -trim +repage \
  -shave 1.5%x1.5% +repage -resize 1024x1024^ -gravity center -extent 1024x1024 "$TMP/sq.png"
# 2) warna latar adaptive icon = warna di dalam gambar (18% dari tepi), supaya sudut yang terpotong menyatu
BG=$($IM "$TMP/sq.png" -format '%[hex:p{184,184}]' info: | tr -d '\n' | cut -c1-6)
[ ${#BG} -eq 6 ] || BG=FFFFFF
# 3) bulatkan sudut (kotak membulat) agar tidak ada tepi abu-abu
if [ "${TIMEFLOW_ICON_ROUND:-1}" != "0" ]; then
  $IM -size 1024x1024 xc:none -fill white -draw "roundrectangle 0,0 1023,1023 235,235" "$TMP/mask.png"
  $IM "$TMP/sq.png" -alpha set "$TMP/mask.png" -compose DstIn -composite "$TMP/sq.png"
fi
# 4) foreground 432x432: gambar 76% di tengah (zona aman adaptive icon), sisanya transparan
$IM "$TMP/sq.png" -resize 328x328 -background none -gravity center -extent 432x432 "$TMP/fg.png"
mkdir -p "$RES/drawable-nodpi" "$RES/values" "$RES/mipmap-anydpi-v26"
cp "$TMP/fg.png" "$RES/drawable-nodpi/ic_launcher_foreground.png"
rm -f "$RES/drawable/ic_launcher_foreground.xml"
printf '<resources>\n    <color name="ic_launcher_background">#%s</color>\n</resources>\n' "$BG" > "$RES/values/ic_launcher_background.xml"
cat > "$RES/mipmap-anydpi-v26/ic_launcher.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
XML
echo "Ikon dibuat dari $SRC (warna latar #$BG)."
