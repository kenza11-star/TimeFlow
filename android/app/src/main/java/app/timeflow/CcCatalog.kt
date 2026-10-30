package app.timeflow

/**
 * Daftar kontrol cepat Control Center (disusun dari Quick Settings Poco C71).
 * acts  = aksi Intent (halaman pengaturan / fitur sistem) yang dicoba berurutan sampai ada yang terbuka.
 * pkgs / names = aplikasi pihak ketiga (paket atau potongan nama di peluncur). Bila ketemu, ikon aslinya dipakai.
 * appFirst = buka aplikasinya dulu, baru aksi cadangan.
 * Catatan: Android melarang aplikasi biasa menyalakan sebagian besar fitur ini langsung, jadi ketukan membuka halamannya;
 * status aktif / mati tetap dibaca untuk fitur yang bisa dibaca.
 */
data class CcDef(
    val id: String, val label: String, val icon: String,
    val acts: List<String> = emptyList(), val pkgs: List<String> = emptyList(), val names: List<String> = emptyList(),
    val appFirst: Boolean = false,
)

object CcCatalog {
    private const val ACCESS = "android.settings.ACCESSIBILITY_SETTINGS"
    private const val PRIVACY = "android.settings.PRIVACY_SETTINGS"
    private const val DISPLAY = "android.settings.DISPLAY_SETTINGS"

    val ALL: List<CcDef> = listOf(
        CcDef("torch", "Lampu Senter", "torch"),
        CcDef("timer", "TimeFlow", "timer"),
        CcDef("calc", "Kalkulator", "calc"),
        CcDef("camera", "Kamera", "camera"),
        CcDef("record", "Perekam Layar", "record"),
        CcDef("hotspot", "Hotspot", "hotspot", acts = listOf("android.settings.TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS")),
        CcDef("location", "Lokasi", "pin", acts = listOf("android.settings.LOCATION_SOURCE_SETTINGS")),
        CcDef("battery", "Penghemat Baterai", "battery", acts = listOf("android.settings.BATTERY_SAVER_SETTINGS")),
        CcDef("display", "Tema Gelap", "display", acts = listOf(DISPLAY)),
        CcDef("nightlight", "Cahaya Malam", "nightlight", acts = listOf("android.settings.NIGHT_DISPLAY_SETTINGS", DISPLAY)),
        CcDef("dim", "Ekstra Redup", "dim", acts = listOf("android.settings.REDUCE_BRIGHT_COLORS_SETTINGS", ACCESS)),
        CcDef("invert", "Inversi Warna", "invert", acts = listOf("android.settings.COLOR_INVERSION_SETTINGS", ACCESS)),
        CcDef("daltonizer", "Koreksi Warna", "daltonizer", acts = listOf("android.settings.COLOR_CORRECTION_SETTINGS", ACCESS)),
        CcDef("fontsize", "Ukuran Font", "fontsize", acts = listOf("android.settings.TEXT_READING_SETTINGS", DISPLAY)),
        CcDef("datasaver", "Penghemat Data", "datasaver", acts = listOf("android.settings.DATA_SAVER_SETTINGS", "android.settings.DATA_USAGE_SETTINGS")),
        CcDef("alarm", "Alarm", "alarm", acts = listOf("android.intent.action.SHOW_ALARMS")),
        CcDef("share", "Quick Share", "share", acts = listOf("com.google.android.gms.settings.NEARBY_SHARE", "android.settings.BLUETOOTH_SETTINGS")),
        CcDef("qr", "Pemindai QR", "qr", acts = listOf("android.media.action.STILL_IMAGE_CAMERA"), pkgs = listOf("com.xiaomi.scanner"), names = listOf("pemindai", "scanner"), appFirst = true),
        CcDef("mic", "Akses Mikrofon", "mic", acts = listOf(PRIVACY)),
        CcDef("cam", "Akses Kamera", "cam", acts = listOf(PRIVACY)),
        CcDef("security", "Keamanan & Privasi", "security", acts = listOf(PRIVACY, "android.settings.SECURITY_SETTINGS")),
        CcDef("hearing", "Alat Bantu Dengar", "hearing", acts = listOf("android.settings.HEARING_AID_SETTINGS", ACCESS)),
        CcDef("tts", "Teks ke Ucapan", "tts", acts = listOf("com.android.settings.TTS_SETTINGS", ACCESS)),
        CcDef("focus", "Fokus", "focus", acts = listOf("android.settings.ZEN_MODE_PRIORITY_SETTINGS"), pkgs = listOf("com.google.android.apps.wellbeing"), names = listOf("kesejahteraan digital", "digital wellbeing"), appFirst = true),
        CcDef("remote", "Remote TV", "remote", pkgs = listOf("com.duokan.phone.remotecontroller"), names = listOf("mi remote", "remote tv")),
        CcDef("devices", "Kontrol Perangkat", "devices", pkgs = listOf("com.google.android.apps.chromecast.app"), names = listOf("google home")),
        CcDef("song", "Penelusuran Lagu", "note", acts = listOf("com.google.android.googlequicksearchbox.MUSIC_SEARCH"), pkgs = listOf("com.shazam.android"), names = listOf("shazam")),
        CcDef("photo", "Foto untuk Bertanya", "photo", pkgs = listOf("com.google.ar.lens"), names = listOf("lens"), appFirst = true),
        CcDef("pay", "Bayar", "pay", acts = listOf("android.settings.NFC_PAYMENT_SETTINGS"), pkgs = listOf("com.google.android.apps.walletnfcrel"), names = listOf("wallet", "dompet"), appFirst = true),
        CcDef("chatgpt", "ChatGPT", "app", pkgs = listOf("com.openai.chatgpt"), names = listOf("chatgpt")),
        CcDef("deepseek", "Tanya DeepSeek", "app", pkgs = listOf("com.deepseek.chat"), names = listOf("deepseek")),
        CcDef("dns", "1.1.1.1", "app", pkgs = listOf("com.cloudflare.onedotonedotonedotone"), names = listOf("1.1.1.1")),
        CcDef("floatee", "Floatee", "app", names = listOf("floatee")),
    )

    val byId: Map<String, CcDef> = ALL.associateBy { it.id }
}
