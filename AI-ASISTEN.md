# Asisten AI dan Jam Kaca (v2.21.0)

## Menyalakan
Pengaturan peluncur (ikon pengaturan di layar utama) > **Asisten AI**.

1. Isi **API key AI**, pilih penyedia dan model (OpenAI, Anthropic, Gemini, OpenRouter, Groq, DeepSeek, Mistral, xAI, atau kustom). Ketuk **Tes AI**.
2. Isi **API key ElevenLabs** dan Voice ID (opsional; tanpa itu dipakai suara HP). Ketuk **Tes suara**.
3. Nyalakan **Nyalakan asisten AI**, izinkan mikrofon.
4. Nyalakan layanan aksesibilitas **Flow AI (kendali layar)** di Pengaturan Android agar AI bisa melihat dan menyentuh layar. Layanan ini terpisah dari Control Center.
5. Untuk bangun lewat suara, nyalakan **Dengarkan kata aktivasi**. Atau pakai tombol **Aktifkan AI sekarang**.

## Perintah suara
- Bangun: "ai aktif", "kyn aktif", "wake up" (nama panggilan bisa diubah, tambahkan ejaan yang sering salah didengar).
- Perintah: "ai tolong buka YouTube", "kyn bacakan notifikasi", dst.
- Batalkan tugas: "stop" / "batal". Tidur: "ai nonaktif" / "sleep".

## Cara kerja
Perintah suara -> pengenal suara Android -> LLM (format OpenAI atau Anthropic) -> aksi satu per satu (buka aplikasi/tautan, ketuk, geser, ketik, baca layar, baca notifikasi) -> hasil layar -> LLM, sampai selesai. Jawaban diucapkan lewat ElevenLabs. Kolom kata sandi tidak dibaca; AI diminta konfirmasi sebelum pembayaran, penghapusan, atau pengiriman pesan yang belum jelas.

## Dynamic Island
Saat AI aktif, pil notch melebar dengan cincin cahaya berputar, orb, gelombang suara, dan teks status (mendengarkan, berpikir, berbicara, bekerja). Kapsul info singkat juga muncul untuk mengisi daya, baterai lemah, senyap/getar/dering, dan jangan ganggu (bila notch dinyalakan).

## Jam Kaca
Tambah widget **Jam Kaca** dari pemilih widget peluncur. Ukurannya bisa diubah; angka mengisi panel bergaya kaca.

## Batasan
- Pengenal suara Android bisa mengeluarkan bunyi "ting" tiap mulai mendengarkan (ada opsi meredam) dan tidak seirit pendeteksi kata aktivasi khusus.
- Mikrofon latar depan tidak bisa dinyalakan dari boot: siaga aktif lagi saat peluncur dibuka.
- Alamat kustom harus https. Kunci API tersimpan hanya di HP dan tidak ikut cadangan.

## Mode tidur (mikrofon mati)
- Saklar **Mikrofon mati saat AI tidur** (aktif secara bawaan): setelah AI tidur (perintah suara, tombol "Tidurkan AI", ubin, atau 4 menit tanpa perintah) layanan dihentikan total sehingga titik hijau mikrofon hilang.
- Bangunkan lewat tombol **Aktifkan AI sekarang** atau ubin Quick Settings **Flow AI** (tambahkan lewat Edit di panel Quick Settings).
- Matikan saklar ini bila ingin AI terus menunggu kata aktivasi (mikrofon menyala terus).

## Mode game otomatis (hemat CPU, GPU, RAM, baterai)
- Saklar **Mode game otomatis** (aktif secara bawaan) di Asisten AI > MODE.
- Terdeteksi saat aplikasi di depan berkategori game, atau aplikasi layar penuh dalam posisi landscape.
- Selama itu: notch, panel Control Center, pil AI, mikrofon AI (supaya voice chat game bebas memakai mikrofon), pembaruan widget musik per detik, dan penangkapan kaca widget dihentikan sementara. Semuanya menyala lagi otomatis begitu kembali ke layar utama atau aplikasi lain.
- Butuh salah satu layanan aksesibilitas (TimeFlow Control Center atau Flow AI) menyala agar aplikasi di depan bisa diketahui.

## Penghematan lain
- Daftar aplikasi dimuat ulang hanya bila ada aplikasi dipasang/dihapus (bukan tiap layar utama dibuka).
- Tata letak layar utama hanya disimpan bila berubah.
- Animasi batang musik di notch ~15 gambar per detik (sebelumnya mengikuti refresh layar) dan berhenti saat jendela tidak terlihat.
- Widget musik tidak memperbarui tiap detik saat layar mati.
- Build lewat skrip Termux memakai mode release.

## Offline / sinyal jelek
- AI: bila tidak ada internet, langsung dijawab "lagi nggak ada internet" (tanpa menunggu timeout); suara beralih ke suara bawaan HP; pengenal suara memakai model offline bila ada (unduh bahasa Indonesia di Pengaturan Google > Suara) dan mencoba ulang dengan jeda makin panjang (hemat baterai).
- Timeout dipersingkat: LLM 8 dtk sambung / 40 dtk baca, ElevenLabs 8 / 20 dtk.
- Cuaca dan lirik tidak mencoba jaringan saat offline (memakai data tersimpan).

## Blur Control Center
- Latar blur memakai tangkapan layar yang dikecilkan (hemat RAM). Bila tangkapan baru ditolak (layar aman, batas jarak antar tangkapan, game), dipakai tangkapan terakhir yang berhasil (maks 3 menit) sehingga blur tidak hilang.

## Pengenalan (onboarding)
- Tampil sekali saat pertama kali Flow Launcher dibuka pada pemasangan baru (pengguna lama dengan tata letak yang sudah ada tidak diganggu). Ulangi lewat Pengaturan layar utama > "Ulangi pengenalan".
- Halaman: intro, gaya ikon (warna asli / satu warna), kaca & blur (pratinjau langsung), mode game, lalu penyiapan ikon dengan progres. Geser atau ketuk Lanjut. Semua pilihan tersimpan ke pengaturan yang sama dengan menu Pengaturan.
