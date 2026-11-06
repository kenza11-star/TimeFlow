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
