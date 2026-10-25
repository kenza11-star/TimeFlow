# TimeFlow (web + Android native)

## PANDUAN CEPAT (1 ZIP ini berisi semuanya)

1. **Deploy ke Vercel**: ekstrak ZIP, push folder ini ke GitHub, import di Vercel (tanpa pengaturan apa pun; `vercel.json` sudah mengarah ke `web/`). Atau di Termux: `npm i -g vercel && vercel --prod` dari folder ini. Catat URL-nya, mis. `https://timeflow-kamu.vercel.app`.
2. **Bangun APK di Termux memakai URL itu**:
   `cd android && gradle :app:assembleVercelDebug -PtimeflowUrl=https://timeflow-kamu.vercel.app`
   APK: `android/app/build/outputs/apk/vercel/debug/app-vercel-debug.apk`
3. **WebToAPK biasa TIDAK bisa dipakai** untuk ini: ia hanya membungkus website dan tidak menjalankan kode alarm native. Alarm, suara, getar, notifikasi, foto profil hanya ada di proyek `android/` (dibangun dengan Gradle, bukan WebToAPK).

Catatan: kode Kotlin belum pernah dikompilasi/diuji di perangkat (lihat "STATUS JUJUR" di bawah). Alternatif bila Termux gagal: GitHub Actions (`.github/workflows/build-apk.yml`).

---

Struktur:

```
web/        Frontend (satu index.html). Dideploy ke Vercel DAN dibundel ke APK.
android/    Proyek Android (Kotlin, Gradle). Alarm, audio, getar, notifikasi, foto, backup = native.
tools/      gen_sounds.py (pembuat 4 nada bawaan)
vercel.json Konfigurasi Vercel (outputDirectory = web)
```

## STATUS JUJUR

Diverifikasi di lingkungan pembuatan: sintaks JavaScript, alur UI web di Chromium dengan bridge native **tiruan** (migrasi alarm, form alarm, izin, validasi backup), XML valid.
**Belum** dikompilasi dan **belum** dijalankan di perangkat: seluruh kode Kotlin/Gradle. Lingkungan pembuatan tidak punya Gradle, Android SDK, maupun jaringan, jadi tidak ada APK yang dihasilkan dan tidak ada pengujian alarm nyata. Kemungkinan ada error kompilasi kecil pada build pertama. Daftar uji di bagian "Uji di perangkat" harus kamu jalankan.

## 1. Deploy website ke Vercel

1. Push repo ke GitHub. 2. Import di Vercel. 3. Framework "Other", tanpa build command. `vercel.json` sudah mengarahkan output ke `web/`. HTTPS otomatis.
Di browser biasa, UI berjalan dengan mesin alarm JavaScript lama sebagai cadangan (hanya aktif saat halaman terbuka, tanpa pesan peringatan sesuai permintaan). Alarm yang andal hanya ada di aplikasi Android.

## 2. Arsitektur Android

- UI dimuat dari **assets APK** lewat `WebViewAssetLoader` di `https://appassets.androidplatform.net/assets/index.html`. Jadi aplikasi dan alarm jalan **offline**; Vercel tidak dibutuhkan untuk membunyikan alarm. Setelah mengubah `web/`, bangun ulang APK.
- Bridge: `WebViewCompat.addWebMessageListener` dibatasi ke origin itu, hanya main frame, setiap perintah divalidasi (`NativeBridge.kt`). Navigasi ke domain lain diblokir, request non-origin dijawab 403, tidak ada izin INTERNET, tidak ada rahasia di frontend.
- Alarm: `AlarmManager.setAlarmClock` -> `AlarmReceiver` -> `AlarmService` (foreground, `mediaPlayback`) memutar `MediaPlayer` (USAGE_ALARM, looping) + `Vibrator`, notifikasi dengan **Tunda** dan **Stop**, full-screen intent ke `AlarmRingActivity`. Mesin alarm tidak memakai timer JS/HTML audio/service worker.
- Penyimpanan native: `alarms.json` atomik di penyimpanan privat (dipilih menggantikan Room agar build di Termux tidak butuh kapt/KSP). Sumber kebenaran alarm = native; web mencerminkan dan disinkronkan saat dibuka, saat kembali ke aplikasi, dan saat alarm berbunyi.
- Fitur: sekali jalan, hari tertentu, ulang tiap 5-120 menit dalam rentang jam, tunda 5/10/15/30 menit, 4 nada bawaan + audio kustom (disalin ke penyimpanan app via SAF, MP3/OGG/WAV/M4A dll. sesuai dekoder Android), getar + 3 pola, volume skala-dalam-app (volume sistem tidak diubah), dedupe event ganda, jadwal ulang setelah boot/ganti waktu/zona/update app/perubahan izin, auto-berhenti 10 menit + notifikasi "terlewat", foto profil via Photo Picker + crop + simpan file JPEG, backup JSON via SAF dengan validasi skema (v1 dan v2 diterima, v lebih baru ditolak, item rusak dilewati, audio hilang dialihkan, wajib konfirmasi sebelum menimpa).
- Izin: notifikasi (Android 13+) dan alarm tepat waktu diminta kontekstual setelah kamu menyimpan alarm; status semua izin ada di Settings > Izin & notifikasi Android. Tidak ada izin penyimpanan/mikrofon/kontak/lokasi.

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

**Opsi C, Termux (rapuh, tidak saya uji):** `pkg install openjdk-17 gradle`. aapt2 resmi Google tidak berjalan di Termux; pasang aapt2/SDK build-tools untuk aarch64 dari paket Termux atau proyek komunitas, lalu aktifkan `android.aapt2FromMavenOverride=<path aapt2>` di `android/gradle.properties`, set `ANDROID_HOME`, dan butuh RAM bebas sekitar 2 GB. Lalu `cd android && gradle :app:assembleBundledDebug` (atau `assembleVercelDebug -PtimeflowUrl=...`). Jika gagal, pakai Opsi A.

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
