# TimeFlow v1.6.0 (cuaca + widget): build di GitHub Actions

Folder ini berisi proyek lengkap: tampilan web (`web/`), aplikasi Android (`android/`), alat ikon (`tools/`), dan dua workflow GitHub (`.github/workflows/`).

```
web/                  tampilan + fitur (Home + Cuaca, Calendar, Tasks, Notes, Alarm, Calculator, Focus Timer,
                      Riwayat notifikasi, Settings, Dynamic Notch)
android/              aplikasi Android (Kotlin): alarm, Dynamic Notch, riwayat notifikasi (SQLite),
                      cuaca (Open-Meteo), widget layar utama (jam, kalender, cuaca, agenda), jembatan ke web
tools/make-icon.sh    membuat ikon aplikasi dari icon/timeflow.jpg (otomatis saat build)
icon/                 timeflow.jpg = ikon aplikasimu (sudah termasuk)
keystore/             (opsional, lihat langkah 4)
.github/workflows/    build-apk.yml (bangun APK), deploy-web.yml (opsional, deploy web ke Vercel)
vercel.json           pengaturan Vercel (folder web/ jadi situs)
```

## Langkah 1. Siapkan folder di Termux
```
termux-setup-storage
pkg update -y && pkg install -y unzip git
cd ~
unzip -o ~/storage/downloads/TimeFlow-GitHub-lengkap.zip -d timeflow-github
cd timeflow-github
```

## Langkah 2. Buat repo dan token di GitHub
1. github.com > **New repository** > nama `timeflow`, pilih **Private**, jangan centang README. Buat.
2. github.com > foto profil > **Settings > Developer settings > Personal access tokens > Tokens (classic) > Generate new token (classic)**. Centang **repo** dan **workflow**. Salin tokennya.

## Langkah 3. Unggah proyek ke GitHub (dari Termux)
```
git config --global user.name "NAMAMU"
git config --global user.email "emailmu@contoh.com"
git init -b main
git add .
git commit -m "TimeFlow v1.6.0 cuaca + widget"
git remote add origin https://github.com/USERNAME/timeflow.git
git push -u origin main
```
Saat diminta: Username = username GitHub-mu, Password = **token** dari langkah 2 (bukan password akun).

## Langkah 4. Tanda tangan tetap (supaya APK baru bisa menimpa yang lama)
APK yang kamu build di Termux ditandatangani dengan kunci di HP-mu. Agar APK dari GitHub memakai kunci yang sama (dan bisa dipasang menimpa tanpa uninstall, tanpa kehilangan data), salin kuncinya ke repo. Jalankan:
```
ls ~/.android/debug.keystore
mkdir -p keystore
base64 -w0 ~/.android/debug.keystore > keystore/debug.keystore.b64
git add keystore && git commit -m "keystore debug" && git push
```
Pakai cara ini hanya untuk repo **Private**. Kalau kamu lebih suka, simpan isi file `.b64` itu sebagai Secret bernama `DEBUG_KEYSTORE_B64` (repo > Settings > Secrets and variables > Actions), lalu jangan commit file tersebut.

Kalau langkah ini dilewati, APK dari GitHub bertanda tangan acak: APK TimeFlow lama harus di-uninstall dulu dan semua data lokal hilang. Buat backup dulu di Settings > **Ekspor backup (JSON)**.

## Langkah 5. Ambil APK
1. Buka repo > tab **Actions** > **Build APK**. Proses jalan sendiri setelah push, kira-kira 6 sampai 12 menit. Atau tekan **Run workflow** untuk menjalankan manual.
2. Setelah hijau: repo > **Releases** > **TimeFlow APK terbaru** > unduh `TimeFlow.apk` > pasang.
3. Build berikutnya cukup `git add . && git commit -m "..." && git push`.

## Langkah 6. Supaya tampilan web ikut ter-update (PENTING)
Aplikasi memuat tampilannya dari `https://time-flow-steel.vercel.app`, jadi perubahan di folder `web/` baru terlihat setelah web di Vercel ter-update. Cara termudah: sambungkan Vercel ke repo ini.
1. vercel.com > project **time-flow-steel** > **Settings > Git > Connect Git Repository** > pilih repo `timeflow`, production branch `main`.
2. Setelah tersambung, setiap `git push` otomatis men-deploy web.
3. Cek: buka halaman Riwayat notifikasi. Di bawah judul harus tertulis "... notifikasi tersimpan · v3".

(Cara lain: isi secret `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID`, lalu `deploy-web.yml` yang men-deploy.)

## Langkah 7. Coba cuaca dan widget (baru di v1.6.0)
1. Buka TimeFlow > di Home ketuk kartu **Cuaca hari ini** > **Pakai lokasi saya** (izinkan lokasi) atau cari nama kotamu.
2. Pasang widget: Settings > **Cuaca & widget** > ketuk Jam / Kalender / Cuaca / Agenda, atau tahan lama layar utama > **Widget** > **TimeFlow**.
3. Tidak muncul cuaca? Pastikan internet aktif dan, untuk mode lokasi, Lokasi HP menyala. Cara cepat: pilih kota manual.
4. Ingat: setelah mengubah file di `web/` kamu harus push **dan** menunggu Vercel selesai deploy; setelah mengubah file di `android/` push akan memicu build APK baru.

## Catatan
- Build default memakai mode **vercel** dan URL di atas. Mode **bundled** (tampilan di dalam APK) memakai alamat internal yang berbeda, jadi data TimeFlow yang sudah ada tidak terbaca. Pakai hanya bila kamu mau mulai dari nol atau sudah mem-backup.
- Aplikasi hasil build debug terpasang sebagai `app.timeflow.debug`.
- Kode Kotlin belum diuji di perangkat selain yang sudah kamu build. Bila build di GitHub gagal, buka run yang merah, salin 15 baris error pertama, dan kirim ke saya.
