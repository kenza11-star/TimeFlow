#!/data/data/com.termux/files/usr/bin/bash
# TimeFlow: deploy ke Vercel -> dapat URL -> bangun APK (mode Vercel) di Termux.
# Pakai:  bash termux-deploy-build.sh            (deploy + build APK)
#         bash termux-deploy-build.sh deploy     (hanya deploy)
#         bash termux-deploy-build.sh apk https://nama.vercel.app   (hanya build APK dengan URL yang sudah ada)
# Opsional: export VERCEL_TOKEN=xxxx  (token dari vercel.com/account/tokens, supaya tidak perlu login interaktif)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
MODE=${1:-all}
URL=${2:-${TIMEFLOW_URL:-}}
TOOLS=$HOME/.timeflow-tools
SDK=${ANDROID_HOME:-$HOME/android-sdk}
GRADLE_VER=8.7

say(){ printf '\n\033[1;35m== %s\033[0m\n' "$*"; }
die(){ printf '\n\033[1;31mGAGAL: %s\033[0m\n' "$*" >&2; exit 1; }
have(){ command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- 1. paket dasar
say "1/4 Memasang paket dasar (Java 17, Node, aapt2)"
if have pkg; then
  yes | pkg update -y >/dev/null 2>&1 || true
  pkg install -y openjdk-17 nodejs-lts git curl unzip >/dev/null || die "pkg install gagal. Coba: pkg update && pkg upgrade"
  pkg install -y aapt2 >/dev/null 2>&1 || echo "(paket aapt2 tidak ditemukan di repo Termux kamu; lanjut, lihat catatan di README bila build gagal)"
fi
have node || die "Node.js tidak ada."

# ---------------------------------------------------------------- 2. deploy Vercel
deploy() {
  say "2/4 Deploy ke Vercel"
  have vercel || npm i -g vercel >/dev/null
  TOK=()
  if [ -n "${VERCEL_TOKEN:-}" ]; then TOK=(--token "$VERCEL_TOKEN"); else vercel whoami >/dev/null 2>&1 || vercel login; fi
  OUT=$(vercel deploy --prod --yes "${TOK[@]}" 2>&1 | tee /dev/stderr) || die "Deploy Vercel gagal (lihat pesan di atas)."
  # utamakan alias produksi (https://nama.vercel.app) karena URL unik per-deploy bisa dilindungi login Vercel
  URL=$(printf '%s\n' "$OUT" | grep -i 'aliased' | grep -Eo 'https://[A-Za-z0-9.-]+\.vercel\.app' | head -1 || true)
  [ -n "$URL" ] || URL=$(printf '%s\n' "$OUT" | grep -Eo 'https://[A-Za-z0-9.-]+\.vercel\.app' | tail -1 || true)
  [ -n "$URL" ] || die "URL Vercel tidak terbaca dari output. Salin URL produksinya lalu jalankan: bash termux-deploy-build.sh apk https://URL-KAMU"
  printf '\nURL Vercel terdeteksi: \033[1m%s\033[0m\n' "$URL"
  read -r -p "Tekan Enter untuk memakai URL ini, atau ketik URL produksi yang benar: " IN || true
  [ -z "${IN:-}" ] || URL=$IN
}

if [ "$MODE" = all ] || [ "$MODE" = deploy ]; then deploy; fi
URL=${URL%/}
[[ "$URL" == https://* ]] || die "URL harus diawali https:// (mis. https://timeflow-kamu.vercel.app)"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "$URL/" || echo 000)
echo "Cek URL: HTTP $CODE"
[ "$CODE" = 401 ] && echo "PERINGATAN: Vercel meminta login (Deployment Protection). Matikan di Project > Settings > Deployment Protection, atau pakai domain produksi."
[ "$MODE" = deploy ] && { say "Selesai. URL: $URL"; echo "Lanjut: bash termux-deploy-build.sh apk $URL"; exit 0; }

# ---------------------------------------------------------------- 3. Gradle 8.7 + Android SDK
say "3/4 Menyiapkan Gradle $GRADLE_VER dan Android SDK (pertama kali butuh ~1 GB unduhan)"
export JAVA_HOME=${JAVA_HOME:-${PREFIX:-/usr}/lib/jvm/java-17-openjdk}
[ -x "$JAVA_HOME/bin/java" ] || JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
export PATH=$JAVA_HOME/bin:$PATH
mkdir -p "$TOOLS"
if [ ! -x "$TOOLS/gradle-$GRADLE_VER/bin/gradle" ]; then
  curl -fL -o "$TOOLS/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip" || die "Gagal mengunduh Gradle."
  unzip -q -o "$TOOLS/gradle.zip" -d "$TOOLS" && rm -f "$TOOLS/gradle.zip"
fi
GRADLE=$TOOLS/gradle-$GRADLE_VER/bin/gradle
if [ ! -d "$SDK/platforms/android-34" ] || [ ! -d "$SDK/build-tools/34.0.0" ]; then
  mkdir -p "$SDK/cmdline-tools"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -fL -o "$TOOLS/clt.zip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" || die "Gagal mengunduh Android command-line tools."
    unzip -q -o "$TOOLS/clt.zip" -d "$SDK/cmdline-tools" && rm -f "$TOOLS/clt.zip"
    rm -rf "$SDK/cmdline-tools/latest"; mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" "build-tools;34.0.0" || die "sdkmanager gagal."
fi
export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK
echo "sdk.dir=$SDK" > android/local.properties

# aapt2 resmi Google tidak jalan di Termux -> pakai aapt2 dari paket Termux
if have aapt2 && ! grep -q '^android.aapt2FromMavenOverride' android/gradle.properties; then
  echo "android.aapt2FromMavenOverride=$(command -v aapt2)" >> android/gradle.properties
fi

# ---------------------------------------------------------------- 4. build APK
say "4/4 Membangun APK (mode Vercel) untuk $URL"
( cd android && "$GRADLE" --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m :app:assembleVercelDebug -PtimeflowUrl="$URL" ) \
  || die "Build gagal. Baca error di atas; bagian 'Troubleshooting' di README. Alternatif: GitHub Actions (.github/workflows/build-apk.yml)."

APK=$ROOT/android/app/build/outputs/apk/vercel/debug/app-vercel-debug.apk
[ -f "$APK" ] || die "APK tidak ditemukan di $APK"
DEST=$HOME/TimeFlow.apk
[ -d "$HOME/storage/downloads" ] && DEST=$HOME/storage/downloads/TimeFlow.apk
cp "$APK" "$DEST"
say "SELESAI"
echo "APK: $DEST"
echo "Pasang: buka file itu dari aplikasi File, izinkan 'pasang dari sumber ini'. (Belum ada folder Download? jalankan: termux-setup-storage)"
have termux-open && echo "Atau jalankan: termux-open \"$DEST\""
