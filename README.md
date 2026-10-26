# TimeFlow (web + Android native)

## PANDUAN CEPAT (1 ZIP ini berisi semuanya)

**Di Termux, satu perintah:**

```
pkg install -y unzip
unzip TimeFlow-LENGKAP.zip -d timeflow && cd timeflow
export VERCEL_TOKEN=xxxxx        # opsional: token dari vercel.com/account/tokens (tanpa ini kamu login lewat vercel login)
bash termux-deploy-build.sh
```

Skrip itu: (1) memasang Java 17, Node, aapt2, (2) deploy ke Vercel dan mengambil URL produksi, (3) menyiapkan Gradle 8.7 + Android SDK, (4) membangun `TimeFlow.apk` dengan URL tadi dan menyalinnya ke folder Download. Bisa dipisah: `bash termux-deploy-build.sh deploy` lalu `bash termux-deploy-build.sh apk https://nama.vercel.app`.

Tanpa PC dan tanpa Termux yang kuat: push folder ini ke GitHub, tab Actions > "Build APK" > Run workflow (isi URL Vercel), unduh APK dari Artifacts.

**Kenapa bukan WebToAPK biasa?** WebToAPK hanya membungkus website. Alarm yang berbunyi di latar belakang, notifikasi dengan tombol Tunda/Stop, dan semua izin hanya bisa lewat proyek `android/` (kode native). Skrip di atas membangun proyek itu, jadi tampilannya tetap dari URL Vercel milikmu. Bila tetap memakai WebToAPK, semua fitur (foto, audio, alarm, notifikasi) berjalan dalam mode web, tetapi alarm hanya andal saat aplikasi terbuka atau masih hidup di latar belakang (lihat bagian 1).

**Yang diminta di versi ini**
- Foto profil dari galeri: Pengaturan > ketuk profil > Pilih dari galeri (potong + zoom). Di APK memakai Photo Picker native; di mode web memakai pemilih file dan disimpan di IndexedDB.
- Upload audio alarm: Pengaturan > Suara & upload audio alarm > Upload audio alarm, lalu pilih di form alarm (ada tombol Dengarkan). MP3/M4A/OGG/WAV, maks 15 MB di mode web.
- Kata "browser" sudah dihapus dari seluruh teks aplikasi.
- Izin: saat APK pertama dibuka, TimeFlow meminta berurutan notifikasi, foto/galeri, audio, alarm tepat waktu, layar penuh di layar kunci, dan "tidak dibatasi" hemat baterai. Kamera, mikrofon, kontak, dan lokasi sengaja tidak diminta karena tidak ada fitur yang memakainya (mudah ditambah bila kelak perlu).

## v1.3 (perbaikan + Dynamic Notch)

- **Perbaikan alarm:** hapus/nonaktifkan gagal karena kunci `id` di bridge dipakai ganda (ID permintaan vs ID alarm); sekarang ID alarm dikirim sebagai `ref`. Tombol hari (Sen-Min) sebelumnya menyimpan `[0,0]`; sekarang benar. Perintah native yang tidak dibalas kini berakhir dengan pesan error setelah 12 detik, tidak menggantung.
- **Dynamic Notch** (Pengaturan > Dynamic Notch): pil hitam di atas layar yang tetap aktif walau app ditutup. Notifikasi semua aplikasi (WhatsApp dll.) tampil sebagai banner (ketuk = buka, geser atas = tutup). Musik dari Spotify/YouTube Music/dll. tampil ringkas (sampul + gelombang); ketuk untuk kontrol sebelumnya/putar-jeda/berikutnya, bar progres bisa diketuk untuk seek. Lebar, tinggi, jarak atas, geser kiri/kanan, ukuran, lama tampil, sembunyikan saat diam, sembunyikan saat landscape bisa diatur dan langsung terlihat.
- Izin baru: "Tampil di atas aplikasi lain" (SYSTEM_ALERT_WINDOW) dan "Akses notifikasi". Android 13+ pada APK hasil pasang manual: bila akses notifikasi ditolak, buka Info aplikasi > menu titik tiga > "Izinkan setelan terbatas".
- Berkas baru: `NotchListener.kt`, `NotchView.kt`, `NotchPrefs.kt`. Belum dikompilasi di lingkungan pembuatan (tidak ada Gradle/SDK); logika web sudah diuji dengan bridge tiruan.

---

Struktur:

```
web/        Frontend (satu index.html). Dideploy ke Vercel DAN dibundel ke APK.
android/    Proyek Android (Kotlin, Gradle). Alarm, audio, getar, notifikasi, foto, backup = native.
tools/      gen_sounds.py (pembuat 4 nada bawaan)
vercel.json Konfigurasi Vercel (outputDirectory = web)
```

## STATUS JUJUR

Diverifikasi di lingkungan pembuatan: sintaks JavaScript; alur mode web di Chromium (foto dari file + crop + tetap ada setelah reload, upload audio, alarm berbunyi dengan audio kustom, Tunda, bunyi ulang, Stop, alarm berulang, service worker, tidak ada kata "browser" di layar); alur UI dengan bridge native **tiruan**; XML valid.
**Belum** dikompilasi dan **belum** dijalankan di perangkat: seluruh kode Kotlin/Gradle. Lingkungan pembuatan tidak punya Gradle, Android SDK, maupun jaringan, jadi tidak ada APK yang dihasilkan dan tidak ada pengujian alarm nyata. Kemungkinan ada error kompilasi kecil pada build pertama. Daftar uji di bagian "Uji di perangkat" harus kamu jalankan.

## 1. Deploy website ke Vercel

1. Push repo ke GitHub. 2. Import di Vercel. 3. Framework "Other", tanpa build command. `vercel.json` sudah mengarahkan output ke `web/`. HTTPS otomatis.
Di luar APK native (mis. dibungkus WebToAPK atau dibuka langsung dari URL), TimeFlow memakai mesin alarm versi web: dering berulang dengan layar Tunda/Stop, getar, layar menyala, notifikasi sistem dengan tombol Tunda/Stop lewat service worker, dan audio senyap agar halaman tidak dihentikan. Mesin ini hanya jalan selama halaman/WebView hidup; saat layar mati atau aplikasi dimatikan sistem, Android dapat menghentikannya. Alarm yang pasti berbunyi hanya ada di APK native.

## 2. Arsitektur Android

- UI dimuat dari **assets APK** lewat `WebViewAssetLoader` di `https://appassets.androidplatform.net/assets/index.html`. Jadi aplikasi dan alarm jalan **offline**; Vercel tidak dibutuhkan untuk membunyikan alarm. Setelah mengubah `web/`, bangun ulang APK.
- Bridge: `WebViewCompat.addWebMessageListener` dibatasi ke origin itu, hanya main frame, setiap perintah divalidasi (`NativeBridge.kt`). Navigasi ke domain lain diblokir, request non-origin dijawab 403, tidak ada izin INTERNET, tidak ada rahasia di frontend.
- Alarm: `AlarmManager.setAlarmClock` -> `AlarmReceiver` -> `AlarmService` (foreground, `mediaPlayback`) memutar `MediaPlayer` (USAGE_ALARM, looping) + `Vibrator`, notifikasi dengan **Tunda** dan **Stop**, full-screen intent ke `AlarmRingActivity`. Mesin alarm tidak memakai timer JS/HTML audio/service worker.
- Penyimpanan native: `alarms.json` atomik di penyimpanan privat (dipilih menggantikan Room agar build di Termux tidak butuh kapt/KSP). Sumber kebenaran alarm = native; web mencerminkan dan disinkronkan saat dibuka, saat kembali ke aplikasi, dan saat alarm berbunyi.
- Fitur: sekali jalan, hari tertentu, ulang tiap 5-120 menit dalam rentang jam, tunda 5/10/15/30 menit, 4 nada bawaan + audio kustom (disalin ke penyimpanan app via SAF, MP3/OGG/WAV/M4A dll. sesuai dekoder Android), getar + 3 pola, volume skala-dalam-app (volume sistem tidak diubah), dedupe event ganda, jadwal ulang setelah boot/ganti waktu/zona/update app/perubahan izin, auto-berhenti 10 menit + notifikasi "terlewat", foto profil via Photo Picker + crop + simpan file JPEG, backup JSON via SAF dengan validasi skema (v1 dan v2 diterima, v lebih baru ditolak, item rusak dilewati, audio hilang dialihkan, wajib konfirmasi sebelum menimpa).
- Izin: diminta berurutan saat aplikasi dibuka (maksimal 3 kali pembukaan agar tidak mengganggu): notifikasi, foto/galeri, audio, alarm tepat waktu, layar penuh, abaikan hemat baterai. Status dan tombol minta ulang ada di Settings > Izin & notifikasi Android. Tidak ada izin kamera/mikrofon/kontak/lokasi.

### Batasan yang tidak bisa dihilangkan
- Setelah **Force stop**, alarm tidak berbunyi sampai TimeFlow dibuka lagi (perilaku Android).
- Mode hemat baterai produsen (Xiaomi, Oppo, Vivo, Huawei, Samsung, dll.) bisa menunda atau membunuh app; atur baterai TimeFlow ke "Tidak dibatasi" dan aktifkan autostart bila ada.
- Volume nyata = volume alarm sistem x volume dalam app. Jika volume alarm sistem 0, alarm senyap (app menampilkan peringatan).
- Jangan Ganggu: alarm biasanya diizinkan, tetapi pengecualian dapat berbeda.
- Android 12-12L: izin "Alarm & pengingat" harus diberikan manual; tanpa itu app memakai jadwal tidak tepat waktu. Android 13+: `USE_EXACT_ALARM` otomatis. Android 14+: izin layar penuh bisa perlu diaktifkan manual. Jika Play-style kebijakan relevan: `USE_EXACT_ALARM` dan full-screen intent dibatasi Google Play untuk app alarm/kalender; untuk sideload tidak ada masalah.
- Alarm yang terlewat saat HP mati tidak dibunyikan setelah menyala; hanya jadwal berikutnya.
- Jika Android menolak memulai service foreground, app hanya menampilkan notifikasi layar penuh (tanpa audio). Jarang, tetapi belum teruji.
- Foto profil dan audio kustom tidak ikut backup JSON.
- Timer Focus di web tetap berbasis halaman (bukan alarm native).
- Reboot sebelum membuka kunci pertama: jadwal baru dipasang ulang setelah HP dibuka kuncinya (tidak memakai direct boot).

## 3. Build APK

Butuh JDK 17, Android SDK (platform 34, build-tools 34.x), Gradle 8.7 (wrapper tidak disertakan karena JAR biner tidak bisa dibuat tanpa jaringan: jalankan sekali `gradle wrapper --gradle-version 8.7` di `android/`).

**Opsi A, GitHub Actions (paling realistis dari HP):** push repo ke GitHub, tab Actions > "Build APK" > Run, unduh artefak `app-debug.apk`.

**Opsi B, Android Studio di PC:** buka folder `android/`, Build > Build APK(s).

**Opsi C, Termux (skrip `termux-deploy-build.sh` mengotomatiskan ini; rapuh dan belum diuji di perangkat):** `pkg install openjdk-17 gradle`. aapt2 resmi Google tidak berjalan di Termux; pasang aapt2/SDK build-tools untuk aarch64 dari paket Termux atau proyek komunitas, lalu aktifkan `android.aapt2FromMavenOverride=<path aapt2>` di `android/gradle.properties`, set `ANDROID_HOME`, dan butuh RAM bebas sekitar 2 GB. Lalu `cd android && gradle :app:assembleBundledDebug` (atau `assembleVercelDebug -PtimeflowUrl=...`). Jika gagal, pakai Opsi A.

Hasil debug bundel: `android/app/build/outputs/apk/bundled/debug/app-bundled-debug.apk` (package `app.timeflow.debug`).

### Mode Vercel (UI dimuat dari URL Vercel milikmu)
```
cd android
gradle :app:assembleVercelDebug -PtimeflowUrl=https://NAMA-KAMU.vercel.app
```
Hasil: `app/build/outputs/apk/vercel/debug/app-vercel-debug.apk`. Perbedaan dengan mode bundel: UI selalu versi terbaru dari Vercel (tanpa bangun ulang APK), tetapi butuh izin INTERNET dan UI tidak terbuka tanpa internet (kecuali cache WebView). Alarm yang sudah terjadwal tetap berbunyi offline karena native. Hanya origin URL itu yang boleh memanggil bridge alarm, jadi hanya deploy kode yang Anda percaya ke domain tersebut. Data localStorage terikat ke origin: berpindah antara mode bundel dan Vercel (atau ganti domain) membuat data web tampak kosong, jadi ekspor backup dulu.

Perintah mode bundel sekarang `assembleBundledDebug` / `assembleBundledRelease` (hasil di `apk/bundled/...`).

### Release bertanda tangan milikmu
```
keytool -genkeypair -v -keystore my-release.jks -alias timeflow -keyalg RSA -keysize 2048 -validity 10000
cp android/keystore.properties.example android/keystore.properties   # isi path & sandi
cd android && gradle :app:assembleBundledRelease
```
Tanpa `keystore.properties`, APK release tidak bertanda tangan; tandatangani dengan `apksigner sign --ks my-release.jks app-release-unsigned.apk`. Simpan keystore; jangan di-commit.

### Pasang
Salin APK ke HP, buka, izinkan "pasang dari sumber ini". Buka TimeFlow, buat alarm, ikuti dialog izin.

## 4. Uji di perangkat (wajib, belum dijalankan)
Settings > Suara & volume alarm > "Uji alarm (10 detik lagi)" lalu kunci layar. Lalu uji: alarm sekali; berulang hari tertentu; ulang tiap N menit; Tunda dan Stop dari notifikasi dan dari layar alarm; audio kustom (lalu hapus filenya: harus jatuh ke Klasik); getar on/off dan Stop menghentikan keduanya; layar terkunci; app dibuka lalu digeser dari recent (bukan Force stop); restart HP; izin notifikasi ditolak; izin alarm tepat waktu dicabut (Android 12); ubah zona waktu dan jam; dua alarm di menit yang sama; matikan alarm dari UI lalu cek tidak berbunyi; foto profil bertahan setelah app ditutup; ekspor lalu pulihkan backup; tema dan aksen.

## 5. Troubleshooting
- **Layar putih / "WebView terlalu lama"**: perbarui Android System WebView. Layar putih tanpa pesan: periksa logcat; jika `ERR_` terkait jaringan, tambahkan `INTERNET` ke manifest (tidak seharusnya perlu).
- **Alarm tidak berbunyi**: Settings > Izin & notifikasi Android; baterai "Tidak dibatasi"; volume alarm sistem > 0; bukan Force stop.
- **Notifikasi tanpa tombol**: izin notifikasi belum diberikan.
- **Error `aapt2` di Termux**: lihat Opsi C.
- **Debug WebView**: build debug mengizinkan `chrome://inspect`.
