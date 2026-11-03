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
- Izin: saat APK pertama dibuka, TimeFlow meminta berurutan notifikasi, foto/galeri, audio, alarm tepat waktu, layar penuh di layar kunci, dan "tidak dibatasi" hemat baterai. Kamera, mikrofon, dan kontak sengaja tidak diminta. Lokasi (perkiraan) baru diminta mulai v1.6, hanya saat kamu memilih "Pakai lokasi saya" untuk cuaca.

## v1.6 (cuaca + widget layar utama)

- **Cuaca hari ini** di Home: suhu, terasa seperti, kondisi, naik/turun, peluang hujan, 8 jam ke depan, kelembapan, angin, terbit/terbenam. Data dari Open-Meteo (gratis, tanpa API key). Ketuk kartunya (atau Settings > Cuaca & widget) untuk memilih lokasi: **Pakai lokasi saya** (izin lokasi perkiraan, hanya diminta saat kamu menekan tombol itu) atau **cari kota** secara manual.
- **Widget layar utama (4 jenis):** Jam (jam + tanggal + cuaca singkat), Kalender (bulan ini, hari ini ditandai), Cuaca, Agenda (acara hari ini + jumlah tugas/alarm). Pasang dari Settings > Cuaca & widget, atau tahan lama layar utama > Widget > TimeFlow. Warna widget mengikuti tema gelap/terang dan warna aksen aplikasi. Agenda 7 hari ke depan disinkronkan dari app ke widget otomatis setiap ada perubahan.
- **Cara kerja internet:** hanya kode native yang memanggil Open-Meteo (`WeatherRepo.kt`); WebView tetap terkunci ke origin aplikasi (request lain tetap diblokir). Koordinat terakhir disimpan, jadi widget Cuaca/Jam bisa memperbarui cuaca di latar belakang (kira-kira tiap 30 menit) tanpa izin lokasi latar belakang. Kalau kamu berpindah kota, buka TimeFlow sekali agar lokasi (mode GPS) diperbarui.
- Izin baru: `INTERNET` (juga untuk build bundled; artinya build bundled tidak lagi 100% tanpa internet) dan `ACCESS_COARSE_LOCATION`.
- Perintah bridge baru: `weather.get/refresh/search/setCity/useGps`, `widget.sync`, `widget.pin`. **Web harus di-deploy ulang ke Vercel dan APK dibuild ulang.**
- Belum dikompilasi dan belum diuji di perangkat (Kotlin, layout widget). Bagian web (parser cuaca, sintaks JS) sudah diuji di lingkungan pembuatan.

## v1.7 (Control Center gaya iPhone)

- **Control Center** (Settings > Control Center): panel kontrol cepat, dibuka dengan geser ke bawah dari area kanan atas layar (bar status). Geser dari tengah/kiri tetap membuka bar notifikasi biasa. Isi: Mode pesawat, Data seluler, Wi-Fi, Bluetooth, kartu musik (sebelumnya/putar/berikutnya), Rotasi, Cast, Jangan Ganggu, slider kecerahan dan volume musik, serta baris kontrol cepat pilihan (Senter, TimeFlow, Kalkulator, Kamera, Hotspot, Lokasi, Hemat daya, Tampilan).
- Android tidak mengizinkan aplikasi biasa menyalakan Wi-Fi/Bluetooth/data/mode pesawat langsung. Tombol itu menampilkan status asli dan, saat diketuk, membuka panel pengaturan sistem (tahan = halaman lengkap). Senter, volume, kecerahan, rotasi, Jangan Ganggu, dan musik langsung bekerja (kecerahan/rotasi butuh izin "Ubah pengaturan sistem", Jangan Ganggu butuh akses Jangan ganggu; keduanya diminta dari halaman pengaturan).
- Memakai layanan yang sama dengan notch (izin tampil di atas aplikasi lain + akses notifikasi), jadi tetap aktif walau app ditutup dan tetap jalan walau notch dimatikan.
- Cara membuka utama: geser dari kanan atas (portrait dan landscape). Agar menang atas gestur bawaan HyperOS/MIUI, nyalakan layanan aksesibilitas "TimeFlow Control Center" (`CcAccessibilityService.kt`; tidak membaca isi layar) yang menampilkan panel di atas bar status. Cadangan: pegangan kecil di tepi kanan layar, ubin "Control Center" di Quick Settings (`CcTile.kt`), atau geser dari kanan atas bar status (bisa diambil alih sistem di HyperOS/MIUI).
- Mode website (Vercel) dihapus: semua build, termasuk `assembleVercelDebug`, sekarang menghasilkan aplikasi penuh dengan UI di dalam APK (offline dan online).
- Build default sekarang **bundled** (UI di dalam APK, offline dan online). Pindah dari APK mode Vercel: ekspor backup di app lama, pasang APK baru, lalu Pulihkan dari backup (data web terikat ke origin).
- Berkas baru: `ControlCenter.kt`, `ControlCenterViews.kt`, `ControlCenterData.kt`. Belum dikompilasi di lingkungan pembuatan (tidak ada Gradle/SDK); sisi web diuji dengan bridge tiruan.

## v1.5 (riwayat notifikasi + alarm & timer di notch)

- **Riwayat Notifikasi** (More > Riwayat notifikasi): semua notifikasi yang masuk disimpan di perangkat (SQLite, tidak dikirim ke server). Bisa dicari berdasarkan nama aplikasi, judul, atau isi; difilter per aplikasi (dengan ikon) dan per periode (hari ini / 7 hari / 30 hari); dikelompokkan per tanggal. Tiap item menampilkan "x menit lalu", hari, tanggal, tahun, dan jam; ketuk untuk melihat teks lengkap, lalu Buka aplikasi, Salin, atau Hapus. Pengaturan: simpan riwayat on/off, lama simpan (30 hari / 90 hari / 1 tahun / selamanya), hapus semua. Batas 5000 notifikasi terbaru. Merekam butuh izin Akses notifikasi (sama dengan notch), dan tetap merekam walau notch dimatikan.
- **Alarm di notch:** saat alarm berbunyi, notch menampilkan kartu berisi label, jam, tombol Tunda dan Matikan (butuh notch aktif + izin tampil di atas aplikasi lain).
- **Timer di notch:** hitung mundur Focus Timer tampil di pil notch (atau di pil musik bila musik sedang diputar); ketuk untuk membuka aplikasi. Tombol uji ada di Settings > Dynamic Notch.
- Halaman riwayat ada di `web/index.html`, jadi **web harus di-deploy ulang ke Vercel** sebelum APK dipakai. APK juga harus dibuild ulang (perintah baru `history.*`, `focus.sync`).
- Belum dikompilasi dan belum diuji di perangkat.

---

## v1.4 (notch lebih cepat + balas dari notch)

- **Lebih responsif:** jendela overlay tidak lagi di-resize tiap frame (itu penyebab ketukan terasa lambat). Pil digambar ulang di dalam jendela, jendela hanya diubah ukurannya dua kali per transisi. Deteksi ketukan lebih toleran, ada efek tekan, animasi lebih singkat.
- **Semua notifikasi di bar masuk ke notch** (kecuali ringkasan grup, notifikasi musik yang sudah jadi kontrol media, dan notifikasi TimeFlow sendiri). Notifikasi menetap (unduhan/layanan) tampil sekali saja. Yang datang berurutan masuk antrean (lencana "+N"). Hilang dari notch bila dihapus dari bar.
- **Ketuk banner = kartu notifikasi:** pesan lengkap, tombol Buka, tombol aksi lain milik notifikasi (mis. "Tandai dibaca"), dan **kolom balas + tombol kirim** bila notifikasinya punya aksi Balas (WhatsApp, Telegram, dll.). Keyboard muncul otomatis. Geser atas / tombol X / ketuk di luar menutup kartu.
- Belum dikompilasi dan belum diuji di perangkat. Balasan memakai RemoteInput milik aplikasi asal; aplikasi yang tidak menyediakan aksi Balas hanya menampilkan tombol Buka.

---

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

Hasil debug bundel: `android/app/build/outputs/apk/bundled/debug/app-bundled-debug.apk` (package `com.timeflow.app`).

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

### v1.8 – Control Center lengkap (tile Poco C71)
- Semua kontrol Quick Settings Poco C71 ada di panel: Cahaya Malam, Ekstra Redup, Inversi/Koreksi Warna, Ukuran Font, Penghemat Data/Baterai, Alarm, Quick Share, Pemindai QR, Akses Mikrofon/Kamera, Keamanan & Privasi, Alat Bantu Dengar, Teks ke Ucapan, Fokus, Remote TV, Kontrol Perangkat, Penelusuran Lagu, Foto untuk Bertanya, Bayar, ChatGPT, DeepSeek, 1.1.1.1, Floatee, Perekam Layar, dst. Pilih mana yang tampil di Settings > Control Center (semua aktif bawaan).
- Android melarang aplikasi biasa menyalakan fitur sistem ini langsung: ketukan membuka halaman/aplikasinya, status aktif/mati dibaca langsung bila bisa. Aplikasi pihak ketiga memakai ikon aslinya dan hanya jalan bila terpasang.
- Kartu musik lebih besar (selebar layar di potret). Panel bisa digulir bila kontrol banyak.

### v1.9 (v6)
- Sampul album memenuhi tinggi kartu musik; kartu musik 4 kolom di landscape.
- Landscape: pemicu geser di atas dimatikan (tidak lagi menelan ketukan di game), Control Center dibuka lewat pegangan di tepi kanan. Portrait: area pemicu lebih tipis.

### v2.0 (v7)
- Ketuk tile Wi-Fi, Bluetooth, Data seluler, Mode pesawat, Hotspot, Lokasi, Penghemat Baterai, Cahaya Malam, dst. langsung menyalakan/mematikan: layanan aksesibilitas membuka panel sistem sesaat, menekan tilenya, lalu menutupnya. Tekan lama = halaman pengaturan. Tanpa layanan aksesibilitas, ketukan kembali membuka pengaturan.
- Layanan aksesibilitas kini membaca isi layar hanya saat menekan tile sistem (perlu dinyalakan ulang sekali setelah update).

### v2.1 (v8)
- Latar panel diburamkan ala iPhone (tangkapan layar sesaat sebelum panel muncul lalu di-blur; hanya saat panel terbuka). Bila sistem menolak tangkapan layar (mis. game/aplikasi yang melarang), dipakai latar gelap biasa.
- Jam dan tanggal berbahasa Inggris di atas panel (mis. "Friday, October 30, 2026").
- Daftar tile ringkas secara bawaan; tombol "Lihat semua / Sembunyikan" untuk menampilkan seluruhnya.

### v2.2 (v9)
- Notch kini dipasang lewat layanan aksesibilitas bila aktif, sehingga notifikasi sistem "TimeFlow ditampilkan di atas aplikasi lain" hilang (izin "tampil di atas aplikasi lain" tidak diperlukan lagi). Tanpa layanan aksesibilitas, perilaku lama dipakai.

### v2.3 (v10)
- Jam dan tanggal dipindah ke kiri atas (jam besar, hari dan tanggal di sampingnya), font gaya iPhone.

### v2.4 (v11)
- Kartu musik menampilkan lirik berjalan (bersinkron) dari LRCLIB (lrclib.net, perlu internet sekali per lagu; lalu disimpan di cache untuk dipakai offline). Bila lagu tidak punya lirik bersinkron, area lirik kosong.

### v2.5 (v12)
- Lirik: ukuran huruf menyesuaikan area (tidak terpotong), pergantian baris dengan transisi halus (memudar + bergeser), pembaruan posisi tiap 0,2 detik.

### v2.6 (v13)
- Judul dan artis satu baris, artis lebih kecil; teks panjang berjalan (marquee) kanan ke kiri sehingga lirik mendapat ruang penuh.

### v2.7 (v14)
- Blur latar lebih andal: bila tangkapan layar ditolak karena terlalu cepat dibuka ulang, diulang sekali setelah ~1 detik.
- Tanda tangan tetap (folder keystore/): APK baru memperbarui APK lama tanpa uninstall, data (alarm, username, dll.) tetap. versionCode naik otomatis di GitHub Actions.

### v2.8 (v15)
- Layar Kunci TimeFlow (Settings > Layar Kunci): wallpaper pilihan sendiri, jam besar, kartu musik + lirik, geser ke atas untuk membuka (PIN/pola/sidik jari/wajah oleh sistem). Tampil di atas layar kunci bawaan (Android tidak mengizinkan menggantinya). Di Poco/HyperOS nyalakan "Tampilkan di layar kunci" dan pop-up latar belakang.

### v2.8 (v15)
- Nama paket baru `com.timeflow.app` (tanpa ".debug"). Karena paket baru, APK ini terpasang berdampingan dengan APK lama (tidak lagi "bentrok"); pindahkan data lewat Ekspor backup di aplikasi lama > Pulihkan dari backup di aplikasi baru, lalu hapus aplikasi lama.
- Widget layar utama "TimeFlow Musik" bergaya kaca: sampul, judul, artis, progres + waktu, tombol sebelumnya/putar-jeda/berikutnya.

### v2.9 (v16)
- Lirik lebih andal: dicoba lewat beberapa pencarian LRCLIB, hasil kosong/gagal tidak lagi diingat selamanya (coba lagi setelah 40 detik), dan hasilnya dicatat di catatan teknis.
- Widget musik: latar kaca (kilau + pinggir terang, tembus pandang), baris lirik, pembaruan tiap detik.

### v18 – Stage 3: widget Waktu Layar (Screen Time)
- Widget baru **TimeFlow Waktu Layar** (kaca beku, latar blur lewat `GlassBg`/`GlassSlots` sama seperti widget lain): total hari ini/hari terpilih, grafik Minggu–Sabtu (ketuk batang untuk memilih hari), aplikasi teratas lengkap dengan ikon, nama, dan durasi, tombol ↻, dan keadaan kosong.
- Sumber data: `UsageStatsManager` (event ACTIVITY_RESUMED/PAUSED, seperti Digital Wellbeing). Dihitung dan disimpan hanya di perangkat; tidak ada yang diunggah. Data tersimpan dibuang saat widget terakhir dihapus atau izin dicabut.
- Izin `PACKAGE_USAGE_STATS` tidak bisa diminta lewat dialog. **Cara menyalakan:** Pengaturan > Aplikasi > Akses khusus > Akses penggunaan > TimeFlow > nyalakan (atau ketuk tombol "Buka pengaturan" di widget). Setelah dinyalakan, widget terisi sendiri; bila belum, ketuk ↻ atau buka TimeFlow.
- Hemat daya: tanpa polling. Pembaruan hanya tiap 30 menit dari sistem, tengah malam, ↻, saat TimeFlow dibuka, dan saat izin diberikan; hitung ulang hanya bila data hari ini lebih dari 5 menit. Hari yang sudah lewat disimpan final.
- Kode: `ScreenTimeRepo.kt` (data), `ScreenTimeWidget.kt` (provider + gambar), `widget_screentime.xml`, `widget_screentime_info.xml`, `st_bar.xml`. Tombol pasang ada di Cuaca & widget.
- Stage 4d: ketuk widget Waktu Layar = halaman rincian sendiri (`ScreenTimeActivity`: total, grafik Minggu–Sabtu dengan sumbu jam, panah/ketuk batang untuk ganti hari, daftar semua aplikasi); ketuk satu batang di widget = halaman itu di hari tersebut. Perhitungan diperbaiki: hanya satu aplikasi di layar depan pada satu waktu (tidak ada lagi total melebihi 24 jam); data hasil hitung lama dibuang otomatis. Grafik widget tidak lagi gepeng. Layout Kalender/Cuaca/Agenda tidak lagi memakai `<View>` (tidak diizinkan di widget).
- Stage 5: Halaman Waktu Layar punya 3 menu di atas (Waktu pemakaian perangkat / Notifikasi diterima / Frekuensi dibuka). Aplikasi bisa disembunyikan dari widget lewat tombol "Tampil/Tersembunyi" di halaman itu (juga dibuka dari Settings > Cuaca & widget). Widget memprioritaskan daftar aplikasi; grafik 7 hari hanya muncul bila widget besar. Tinggi widget kini dibaca benar (MAX_HEIGHT di potret).
- Pemasangan: `versionCode` sekarang = menit sejak 1970 (selalu naik dari mana pun APK dibangun), supaya Android tidak menolak update karena versi "lebih rendah". `applicationId` dan tanda tangan tidak diubah.
- Stage 6: **Gaya widget** (Settings > Cuaca & widget > "Gaya widget"): satu pengaturan untuk semua widget kaca. Tahap 1: kekuatan blur (rendah/sedang/tinggi), kepekatan, kilau, sudut. Tahap 2: gaya Gelap / Terang / Otomatis. Terang = kabut putih + teks, ikon, dan pil gelap (`WidgetTheme.kt`, dihasilkan dari daftar view di `res/layout/widget_*.xml`); Otomatis memilih per widget menurut kecerahan wallpaper di belakangnya. Latar dibuat ulang otomatis saat pengaturan berubah (pengaturan ikut dalam kunci latar). Syarat tetap: layanan aksesibilitas TimeFlow aktif dan Android 11+; tanpa itu widget memakai kaca gelap bawaan dengan teks putih, apa pun pilihannya. Bar progres (musik, waktu layar) di gaya Terang hanya berubah warna di Android 12+.

## Stage 7 (2.9.1): pengaturan widget pindah ke widget
- Pengaturan gaya/blur/sudut dibuka dari widget: tahan lama widget > Pengaturan (halaman Waktu Layar juga punya ikon roda gigi). Tombolnya dihapus dari Settings > Cuaca & widget.
- Kaca cadangan (saat latar blur belum/tidak bisa dibuat) kini mengikuti pilihan Gelap/Terang dan lebih pekat. Mengubah gaya membuang latar lama supaya perubahan langsung terlihat.
- Ukuran widget Kalender/Cuaca/Agenda kini dibaca dengan benar (potret: MIN_WIDTH x MAX_HEIGHT). Cuaca sempit (2 kolom) memakai tampilan ringkas.
- Halaman pengaturan menampilkan catatan latar kaca terakhir untuk membantu mencari penyebab blur tidak muncul.

## Stage 8 (2.9.3): tampilan kaca jadi glassmorphism
Semua kaca/blur (widget layar utama + Control Center) memakai satu bahasa visual seperti kartu frosted di atas foto: blur tebal, warna wallpaper tetap tembus, tepi bercahaya tipis, sudut sangat bulat.

- **Latar widget (`GlassBg.kt`)**: alur tetap (tangkapan layar > crop > blur > bitmap), kualitas naik. Pengecilan bertahap sebelum blur, blur multi-pass (3-4 putaran) setara ~5 / 9 / 17dp (Rendah / Sedang / Tinggi), cukup lembut sehingga isi di belakang kaca masih terbaca bentuknya, saturasi 140%, tint putih tipis ~12%, tint hitam tipis (gaya gelap) dengan tambahan otomatis bila wallpaper terang agar teks putih tetap terbaca, gradasi kilau terang kiri atas ke bening kanan bawah, sudut dihaluskan (anti-alias), rim light 1dp sangat halus (tanpa pita cahaya tebal di tepi). Bitmap tetap maks 640 px, cache 3 MB dan berkas tetap dipakai.
- **`GlassPrefs.kt`**: pengaturan gaya lama direset sekali ke bawaan baru (gelap, teks putih) dan kunci latar diberi versi supaya latar abu-abu lama dibuat ulang; pemetaan blur dan tint baru (blur lebih tebal, tint jauh lebih tipis dari sebelumnya yang membuat latar abu-abu rata); sudut Rendah/Sedang/Tinggi = 20 / 28 / 38dp. Nama pengaturan dan penyimpanan tidak berubah.
- **`WidgetTheme.kt`**: teks di atas pil/hari terpilih kini putih bersih (pil terpilih berupa kaca terang, bukan putih solid).
- **Kaca cadangan** (blur belum siap / tanpa aksesibilitas / Android 10): tetap tampak seperti kaca (tint tipis + kilau diagonal + tepi putih), bukan kotak abu-abu polos. Tombol/chip/pil: outline putih tipis + isi kaca transparan.
- **Control Center**: file baru `CcGlass.kt` (`CcGlassDrawable` + shader kilau dan rim light). Kartu, tombol bulat, slider, kartu musik, dan tombol "Lihat semua" memakai kaca putih tipis (bukan abu-abu solid), kilau diagonal, dan rim light. Latar panel: blur 22 + saturasi 140% (Android 12+; di bawahnya blur kasar + filter saturasi), peredup hitam hanya ~20%.
- **`web/index.html`**: tidak diubah. Kartu web memakai warna solid dan tidak punya efek kaca/backdrop-filter, jadi tidak ada yang perlu dikonversi.
- Tidak menyentuh alarm, notch, lock screen, cuaca, musik. Folder `.github/workflows` tidak diubah dan tidak disertakan.
- Belum dikompilasi dan belum diuji di perangkat; sintaks Kotlin/XML diperiksa manual (XML sudah di-parse).

File yang diganti/ditambah: `GlassBg.kt`, `GlassPrefs.kt`, `WidgetTheme.kt`, `CcGlass.kt` (baru), `ControlCenter.kt`, `ControlCenterViews.kt`, `app/build.gradle.kts` (versionName), `README.md`, layout `widget_calendar.xml` dan `widget_screentime.xml` (warna teks tombol), serta drawable `gw_bg`, `gw_bg_l`, `mw_bg`, `gw_btn(_l)`, `gw_chip(_l)`, `gw_col(_l)`, `gw_col_on(_l)`, `gw_pill_on(_l)`, `gw_pill_off(_l)`, `gw_pill_dark(_l)`, `gw_sel(_l)`.

## Stage 9 (2.10.0): Flow Launcher (peluncur) + Folder App + perbaikan tampilan
**Aplikasi ini kini bernama Flow Launcher dan menjadi peluncur (layar utama) sendiri**, dengan kaca liquid glass di dock, folder, laci aplikasi, dan widget.

- **Pasang**: install APK, tekan Home, pilih "Flow Launcher" > Selalu (atau Pengaturan > Aplikasi bawaan > Aplikasi layar utama). Pengaturan peluncur: tahan area kosong di layar utama.
- **Layar utama** (`HomeActivity.kt`): halaman 4x6, dock kaca (4 slot + tombol semua aplikasi), titik indikator halaman, geser ke atas untuk laci aplikasi (dengan pencarian), tombol Home kembali ke halaman 1.
- **Seret dan lepas** (tekan lama): pindah ikon antar halaman/dock, tarik ke tepi untuk pindah halaman (halaman baru otomatis), tumpuk ikon di atas ikon lain untuk membuat folder, tarik ke "Hapus / Info / Copot" di bagian atas. Dari laci aplikasi juga bisa diseret ke layar.
- **Folder** (`HomeActivity.kt`): ketuk untuk membuka panel kaca, nama bisa diubah, ikon bisa diseret keluar, ketuk ikon membuka aplikasi asli. Folder dengan 1 aplikasi otomatis kembali jadi ikon.
- **Ikon kaca**: ubin kaca + ikon putih (siluet lapisan depan/monokrom ikon adaptif), otomatis memakai ikon berwarna bila siluet tidak cocok. Bisa diganti ke "Warna asli" di Pengaturan peluncur.
- **Wallpaper** (`HomeWall.kt`): pilih dari galeri (Ganti wallpaper). Wallpaper diburamkan sekali, lalu semua panel kaca hanya menggambar potongan blur tepat di belakangnya, jadi ringan dan selalu sinkron saat digeser. Blur, tint, sudut mengikuti Pengaturan "Blur dan gaya kaca".
- **Widget**: tahan layar kosong > Tambah widget. Widget TimeFlow (jam, kalender, cuaca, agenda, musik, waktu layar, Folder App) memakai kaca langsung dari peluncur: tanpa layanan aksesibilitas/tangkapan layar, dan kaca ikut bergeser mulus saat ganti halaman.
- **Widget Folder App** (`FolderWidget.kt`, `FolderConfigActivity.kt`, `FolderActivity.kt`): untuk launcher lain, kartu kaca 2x2 berisi ikon aplikasi pilihan; ketuk ikon membuka aplikasi, ketuk kartu membuka folder penuh.
- **Teks tidak terpotong lagi**: nama bulan/tanggal di kalender mengecil mengikuti lebar, baris cuaca widget jam sempit tanpa nama kota, "Kmrn" di widget waktu layar sempit.
- **Tint putih kaca dinaikkan** (lebih terang, lebih mirip referensi).
- Catatan: `.github/workflows` tidak diubah. Belum dikompilasi dan belum diuji di perangkat; sintaks diperiksa manual (XML sudah di-parse).

File baru: `HomeActivity.kt`, `HomeData.kt`, `HomeWall.kt`, `FolderWidget.kt`, `FolderActivity.kt`, `FolderConfigActivity.kt`, `widget_folder.xml`, `widget_folder_info.xml`. File diubah: `AndroidManifest.xml`, `themes.xml`, `strings.xml`, `GlassBg.kt`, `WidgetTheme.kt`, `Widgets.kt`, `ScreenTimeWidget.kt`, `build.gradle.kts` (versionName), `README.md`.

### Tambahan Stage 9 (tanpa batasan)
- Nama aplikasi: **Flow Launcher** (nama widget jadi "Flow ..."). Nama paket dan kelas tidak diubah agar build/update tetap mulus.
- Layar utama terisi otomatis saat pertama dipakai (semua aplikasi, urut abjad, maks 4 halaman); menu tahan-layar "Susun semua aplikasi ke layar" untuk mengisi sisanya kapan saja. Maks halaman naik ke 16 (halaman baru dibuat otomatis).
- Geser turun di layar utama: panel notifikasi (izin EXPAND_STATUS_BAR); dari sisi kanan = Control Center bila layanan aksesibilitas aktif.
- Pengaturan: nama ikon tampil/sembunyi, gaya ikon kaca/warna asli, blur dan gaya kaca.
- Bila wallpaper sistem tidak bisa dibaca, peluncur menawarkan memilih wallpaper dari galeri satu kali.

## Stage 10 (2.11.0): tampilan seperti iPhone + prompt launcher bawaan
- **Prompt launcher bawaan**: ikon "Flow Launcher" kini membuka peluncur; bila belum jadi launcher utama muncul dialog "Jadikan Flow Launcher launcher utama?" (dialog sistem RoleManager, cadangan: pengaturan aplikasi layar utama). Ada "Nanti" dan "Jangan tanya lagi". Aplikasi pengaturan lama tetap ada dengan nama "TimeFlow".
- **Gaya iPhone** (`HomeActivity.kt`, `FolderWidget.kt`): ikon berwarna squircle penuh (latar + depan ikon adaptif dipotong sudut iOS), nama ikon medium, folder berupa kotak frosted 3x3 ikon mini dengan kaca asli dari wallpaper, dock frosted 4 ikon bersudut besar (tanpa tombol laci), tombol kaca "Cari" di atas dock (membuka laci dengan kolom pencarian aktif), titik halaman di atasnya. Geser ke atas tetap membuka laci. Gaya "Kaca putih" (ikon clear) tetap ada di Pengaturan peluncur.
- **Blur dikurangi** (`GlassPrefs.kt`, `HomeWall.kt`): Rendah / Sedang / Tinggi kini setara sekitar 3 / 4,5 / 8dp (sebelumnya 5 / 9 / 17dp), dock dan folder lebih terang dan tembus.
- **Lirik**: pencarian LRCLIB diperluas (judul saja, pencarian bebas) dan bila hanya ada lirik tanpa waktu, baris dibagi rata sepanjang lagu sebagai perkiraan. Kode ambil lirik lainnya tidak berubah; lirik tetap membutuhkan internet dan lagu yang ada di LRCLIB.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File diubah: `AndroidManifest.xml`, `HomeActivity.kt`, `HomeWall.kt`, `FolderWidget.kt`, `GlassPrefs.kt`, `CcLyrics.kt`, `build.gradle.kts`, `README.md`.

## Stage 11 (2.12.0): jadi launcher lebih mudah, pengaturan folder, lirik lebih andal
- **Jadikan launcher utama**: dialog "Jadikan Flow Launcher launcher utama?" kini muncul dari ikon "Flow Launcher" maupun ikon "TimeFlow" (aplikasi lama). Urutan cara: dialog sistem (RoleManager) > layar Aplikasi layar utama > layar Aplikasi bawaan > pemilih Home. Juga ada di Pengaturan peluncur ("Jadikan peluncur bawaan"). Manual: Pengaturan > Aplikasi > Aplikasi bawaan > Aplikasi layar utama > Flow Launcher.
- **Halaman Atur folder** (`FolderConfigActivity.kt`, mode baru): pilih aplikasi (maks 24), ubah nama, hapus folder. Dibuka dari tombol "Atur folder" di panel folder atau Pengaturan peluncur > "Atur folder…". Halaman ini tidak muncul di laci aplikasi maupun di daftar aplikasi terbaru; hanya bisa dibuka dari pengaturan folder.
- **Lirik**: bila LRCLIB tidak punya lagunya, dicoba sumber cadangan (NetEase, dicocokkan dengan artis dan durasi). Tempat lirik kini menampilkan status ("Mencari lirik…", "Lirik tidak ditemukan", "Tidak terhubung ke server lirik") supaya jelas penyebabnya bila kosong.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File diubah: `HomeData.kt`, `HomeActivity.kt`, `FolderConfigActivity.kt`, `MainActivity.kt`, `CcLyrics.kt`, `MusicWidget.kt`, `ControlCenterViews.kt`, `AndroidManifest.xml`, `build.gradle.kts`, `README.md`.

## Stage 12 (2.13.0): mode edit gaya Nova, pemilih widget, animasi pindah ikon
- **Mode edit** (tekan lama area kosong, `HomeActivity.kt`): halaman mengecil halus, dock dan tombol Cari memudar, muncul tiga tombol kaca Wallpaper / Widget / Pengaturan. Ketuk area kosong atau tombol kembali untuk keluar. Menu "Susun semua aplikasi ke layar" pindah ke Pengaturan peluncur.
- **Pemilih widget** (bottom sheet): kolom "Telusuri widget", daftar per aplikasi yang bisa dibuka-tutup (ikon, nama, jumlah widget), pratinjau gambar tiap widget, ukuran (mis. 4 × 2) dan deskripsi. Ketuk widget untuk menaruhnya di halaman aktif. Tarik pegangan ke bawah untuk menutup.
- **Pratinjau widget Flow**: tujuh gambar pratinjau (`drawable-nodpi/preview_*.png`) dipasang lewat `android:previewImage`, sehingga di pemilih widget launcher mana pun tidak lagi berupa kotak putih berisi ikon. Widget Musik juga mendapat deskripsi.
- **Animasi**: ikon mengecil saat disentuh dan memantul saat dilepas; saat menyeret, sel tujuan disorot dan mengikuti jari; ikon yang dilepas mendarat meluncur dari titik jari ke selnya; ikon baru muncul membesar; folder yang menerima ikon memantul; pindah halaman dan dock ikut meluncur halus.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File baru: `drawable-nodpi/preview_*.png` (7). File diubah: `HomeActivity.kt`, `widget_*_info.xml` (7), `strings.xml`, `build.gradle.kts`, `README.md`.

## Stage 13 (2.14.0): UI sendiri (tanpa menu bawaan), menu tekan-lama, transisi halus
- **UI sendiri** (`FlowUi.kt`): dialog, kolom isian, menu popup, dan toast bergaya kaca dengan animasi, menggantikan semua `AlertDialog`/`Toast` bawaan sistem di peluncur, prompt "Jadikan launcher utama", dan pengaturan. Tombol kembali menutup lapisan teratas dulu. (Dialog sistem yang tak bisa dihindari: konfirmasi RoleManager launcher utama, izin bind widget, dan pemilih gambar galeri.)
- **Menu tekan lama ikon/folder/widget** (`HomeActivity.kt`): muncul di dekat ikon sambil tetap bisa diseret (menu hilang saat jari bergeser, bilah Hapus/Info/Copot muncul). Folder: **Sunting** (ganti nama), **Urutan** (urut A-Z), **Pilih Apl** (halaman khusus atur folder), **Hapus**. Aplikasi: **Sunting** (nama ikon kustom), Info aplikasi, Copot, Hapus. Widget: Hapus. Hapus memakai animasi mengecil.
- **Halaman folder dari widget** (`AndroidManifest.xml`): `FolderActivity` dan `FolderConfigActivity` kini punya task sendiri (`taskAffinity=""`), jadi tidak lagi masuk ke task Flow Launcher dan setelah selesai kembali ke layar utama launcher kamu, bukan ke halaman Flow.
- **Animasi**: folder terbuka membesar dari ikonnya dan isinya muncul berurutan (di peluncur maupun dari widget, lengkap dengan animasi tutup); aplikasi dibuka dengan zoom dari ikon; halaman memudar dan mengecil halus saat digeser; menu, dialog, dan toast punya animasi masuk-keluar.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File baru: `FlowUi.kt`. File diubah: `HomeActivity.kt`, `HomeData.kt`, `FolderActivity.kt`, `AndroidManifest.xml`, `build.gradle.kts`, `README.md`.

## Stage 14 (2.15.0): wallpaper dari galeri, animasi pindah ikon, ubah ukuran folder dan widget
- **Wallpaper dari galeri**: "Ganti wallpaper" kini membuka galeri foto (pemilih foto sistem di Android 13+, aplikasi galeri di bawahnya), bukan pemilih berkas.
- **Animasi pindah ikon** (`HomeActivity.kt`): bayangan seret terangkat dan berpusat di jari; ikon yang akan ditumpuk membesar (isyarat membuat folder); ikon di sekitar sel tujuan bergeser menjauh seperti memberi ruang dan kembali dengan pantulan; sorotan sel mengikuti jari; ikon mendarat meluncur dari jari; mode edit menggoyang ikon (seperti iPhone).
- **Ubah ukuran**: tekan lama folder atau widget > "Ubah ukuran". Muncul bingkai dengan titik tarik di empat tepi; seret per sel (ikut batas ukuran widget: arah yang boleh diubah dan ukuran minimum dari widget itu sendiri), bingkai dan isi berubah halus. Ketuk di luar atau tombol kembali untuk selesai.
- **Folder besar**: folder bisa dibuat lebih dari 1x1 (mis. 2x2 atau 4x1) menjadi kartu kaca berisi ikon aplikasi langsung (ketuk ikon = buka aplikasi, sel terakhir = sisa aplikasi, ketuk area kosong = buka folder). Diperkecil ke 1x1 kembali jadi ikon folder.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File diubah: `HomeActivity.kt`, `HomeData.kt`, `FlowUi.kt`, `build.gradle.kts`, `README.md`.

## 2.15.1: tarik laci ke bawah, gulir widget tidak membuka laci
- **Laci aplikasi**: bila daftar sudah di paling atas, tarik ke bawah untuk menutup dan kembali ke layar utama (lapisan ikut bergeser; dilepas cukup jauh atau dilempar = tutup, kalau tidak kembali dengan pantulan). Penutupan lewat tombol kembali/Home kini meluncur turun penuh.
- **Gestur geser layar utama diperbaiki**: geser ke atas (buka laci) dan geser ke bawah (panel notifikasi / Control Center) tidak lagi aktif saat menggulir pemilih widget, di mode edit, saat mengubah ukuran, saat ada menu/dialog, dan saat jari mulai di atas widget (jadi daftar di dalam widget bisa digulir tanpa membuka laci).
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File diubah: `HomeActivity.kt`, `HomeWall.kt`, `FlowUi.kt`, `build.gradle.kts`, `README.md`.

## 2.15.2: animasi pindah lebih halus, bilah hapus dan kotak putih dihapus
- **Dihapus**: bilah Hapus/Info/Copot di atas layar saat menyeret, dan kotak putih sorotan di belakang ikon yang diseret. Hapus, Info, dan Copot tetap ada di menu tekan lama.
- **Animasi lebih halus**: ikon asal terangkat dan mengecil halus saat diseret, bayangan seret lebih besar, ikon mendarat dengan pantulan lembut (lebih lama), ikon yang pindah meluncur dengan pelambatan, ikon baru muncul dengan pantulan, ikon di sekitar bergeser lebih lembut, dan pergantian halaman memakai animator sendiri (durasi menyesuaikan jarak, bisa dihentikan dengan sentuhan).
- **Widget**: dipindah dengan menekan lama (bukan sekadar ketuk); saat ditekan muncul menu Ubah ukuran, Pengaturan widget (bila widget punya pengaturan), dan Hapus.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File diubah: `HomeActivity.kt`, `HomeWall.kt`, `build.gradle.kts`, `README.md`.

## 2.16.0: Control Center mendatar (landscape) dua kolom
- **Tata letak baru saat layar mendatar** (`ControlCenter.kt`, `buildLand()`): kolom kiri berisi jam dan tanggal, baris Wi-Fi dan Data seluler (lebar, dengan ikon dan label), panel kaca berisi deretan kontrol kecil (Bluetooth, Pesawat, Rotasi, Jangan Ganggu, Cast, lalu semua kontrol cepat dari katalog) 4 per halaman dengan titik halaman dan bisa digeser, serta dua slider mendatar (kecerahan dan volume). Kolom kanan berisi kartu musik (sampul, judul, artis, bilah progres, tombol kontrol). Ukuran menyesuaikan tinggi layar; bila tidak muat, panel bisa digulir.
- **Tanpa lirik di Control Center mendatar** (`CcMedia`, parameter `showLyric`): tidak ada permintaan lirik dan ruang lirik dikosongkan supaya tombol tetap di bawah. Lirik di widget musik layar utama tidak berubah.
- **Slider mendatar** (`CcSlider.horizontal`): isi dari kiri ke kanan, ikon di kiri berganti warna di atas isian; potret tetap vertikal.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File diubah: `ControlCenter.kt`, `ControlCenterViews.kt`, `build.gradle.kts`, `README.md`.

## 2.17.0: widget bebas diatur, seret widget dari pemilih, perbaikan lag
- **Grid halus**: layar utama kini memakai grid 8x12 (tiap sel penuh = 2x2 sub-sel). Ikon dan folder kecil tetap menempel ke sel penuh, sedangkan **widget dan folder besar bebas per setengah sel** (posisi dan ukuran). Tata letak lama dikonversi otomatis sekali saat pertama dibuka.
- **Ubah ukuran bebas**: tekan lama widget/folder > "Ubah ukuran", seret titik di tepi dengan langkah setengah sel; tidak lagi terikat ukuran minimum/arah resize bawaan widget (batas bawah satu sel penuh).
- **Memilih widget**: di pemilih widget, **tekan lama** sebuah widget lalu **seret ke tempat di layar** yang diinginkan (bayangan = pratinjau widget, halaman bisa berganti lewat tepi layar); lepaskan untuk menaruhnya persis di situ. Ketuk biasa tetap menambahkan otomatis di ruang kosong.
- **Lag dikurangi** (dari analisis rekaman layar: jeda frame panjang saat ubah ukuran dan setelah memindah):
  - host view widget dipakai ulang antar pembangunan ulang layar (sebelumnya widget dibuat dan di-inflate ulang tiap kali menjatuhkan ikon);
  - ukuran widget hanya dikirim ke penyedia widget bila berubah (sebelumnya tiap rebuild memicu pembaruan semua widget);
  - saat mengubah ukuran, isi widget hanya ditransformasi (skala/geser) dan baru ditata ulang sekali saat selesai, bukan tiap frame;
  - efek pudar (alpha) pada halaman saat digeser dibuang karena memaksa layer offscreen.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File diubah: `HomeActivity.kt`, `HomeData.kt`, `build.gradle.kts`, `README.md`.

## 2.18.0: tile langsung menyala dengan animasi, lirik di landscape, cari aplikasi di folder
- **Tile Control Center langsung menyala/mati** (`CcAccessibilityService.kt`, `ControlCenter.kt`): mengetuk Wi-Fi, Data seluler, Bluetooth, Hotspot, Mode pesawat dan kontrol sistem lain tidak lagi membuka pengaturan (kecuali tile tidak ditemukan atau ditekan lama). Android tidak mengizinkan aplikasi biasa menyalakan Wi-Fi/Bluetooth/data/hotspot langsung, jadi yang ditekan adalah tile Pengaturan Cepat sistem lewat layanan aksesibilitas:
  - yang diklik kini **bagian ikon** tile (hidup/mati), bukan label (yang membuka detail), termasuk untuk tile dua bagian ala ColorOS/OxygenOS;
  - pencarian tile juga memakai id elemen (mis. qs_wifi_icon) selain teks/deskripsi, dan lebih cepat (mulai 450 ms, cek tiap 200 ms);
  - panel Control Center **tetap terbuka** selama itu (panel sistem terbuka diam-diam di belakangnya lalu ditutup), tanpa berkedip ke layar lain.
- **Animasi tile**: tile memantul saat diketuk dan memantul lagi saat statusnya berganti; warna berpindah halus antara mati dan menyala.
- **Lirik di Control Center mendatar** kembali ditampilkan di kartu musik.
- **Pengaturan folder**: ada kolom "Cari aplikasi" untuk menyaring daftar; nama folder boleh kosong (folder tanpa nama tidak menampilkan label), di folder layar utama, folder widget, dan folder terbuka.
- Bila tile masih membuka pengaturan di HP-mu, catatan "tekan tile" dan "tile tidak ditemukan" ada di log Control Center (CcLog) untuk disesuaikan dengan nama tile bawaan HP.
- Belum dikompilasi dan belum diuji di perangkat; `.github/workflows` tidak diubah.

File diubah: `CcAccessibilityService.kt`, `ControlCenter.kt`, `ControlCenterViews.kt`, `ControlCenterData.kt`, `FolderConfigActivity.kt`, `FolderActivity.kt`, `FolderWidget.kt`, `HomeActivity.kt`, `build.gradle.kts`, `README.md`.
