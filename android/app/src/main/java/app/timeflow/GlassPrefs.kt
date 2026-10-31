package app.timeflow

import android.content.Context

/**
 * Pengaturan gaya kaca widget (satu pengaturan untuk semua widget): gaya (gelap / terang / otomatis), kekuatan blur,
 * kepekatan, kilau, dan sudut. Disimpan di SharedPreferences; dibaca GlassBg saat membuat latar. Diubah dari WidgetStyleActivity.
 * Nilai bawaan = glassmorphism (gelap, blur sedang ~24dp, tint tipis, sudut 28dp).
 */
object GlassPrefs {
    private const val SP = "timeflow_glass"
    const val STYLE_DARK = 0
    const val STYLE_LIGHT = 1
    const val STYLE_AUTO = 2

    class Cfg(val style: Int, val blur: Int, val dim: Int, val sheen: Int, val radius: Int) {
        /** Kekuatan blur: pengecilan bitmap, jumlah putaran, dan radius kotak. Makin besar pembagi, makin kabur. */
        val blurDiv get() = when (blur) { 0 -> 8; 2 -> 14; else -> 10 }
        val blurPasses get() = when (blur) { 0 -> 3; 2 -> 4; else -> 3 }
        val blurR get() = when (blur) { 0 -> 5; 2 -> 6; else -> 6 }
        val radiusDp get() = when (radius) { 0 -> 20f; 2 -> 38f; else -> 28f }
        /** Tint hitam tipis (gaya gelap): 0..100% -> alpha 0..55 (bawaan 50% = 27, ~10%) supaya warna wallpaper tetap hidup. */
        val darkAlpha get() = (dim * 0.55f).toInt().coerceIn(0, 255)
        /** Kabut putih (gaya terang, teks gelap): 0..100% -> alpha 0..160 (bawaan 50% = 80). */
        val lightAlpha get() = (dim * 1.6f).toInt().coerceIn(0, 255)
        val sheenK get() = sheen / 50f
    }

    private fun sp(c: Context) = c.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private const val VER = 2

    fun get(c: Context): Cfg {
        val s = sp(c)
        // migrasi glassmorphism (v2): pengaturan lama sering membuat kaca abu-abu dengan teks gelap; setel ulang sekali ke bawaan baru
        if (s.getInt("ver", 0) < VER) s.edit().clear().putInt("ver", VER).apply()
        return Cfg(s.getInt("style", STYLE_DARK).coerceIn(0, 2), s.getInt("blur", 1).coerceIn(0, 2), s.getInt("dim", 50).coerceIn(0, 100),
            s.getInt("sheen", 50).coerceIn(0, 100), s.getInt("radius", 1).coerceIn(0, 2))
    }

    /** Tanda tangan pengaturan: ikut dalam kunci latar, jadi mengubah pengaturan otomatis membuat latar baru. */
    fun sig(c: Context): String { val g = get(c); return "g2s${g.style}b${g.blur}d${g.dim}w${g.sheen}r${g.radius}" }

    fun set(c: Context, style: Int? = null, blur: Int? = null, dim: Int? = null, sheen: Int? = null, radius: Int? = null) {
        val e = sp(c).edit()
        e.putInt("ver", VER)
        style?.let { e.putInt("style", it) }; blur?.let { e.putInt("blur", it) }; dim?.let { e.putInt("dim", it) }
        sheen?.let { e.putInt("sheen", it) }; radius?.let { e.putInt("radius", it) }
        e.apply()
    }

    fun reset(c: Context) { sp(c).edit().clear().putInt("ver", VER).apply() }

    /** Minta latar kaca dibuat ulang sekarang (setelah pengaturan berubah). */
    fun applyNow(c: Context) {
        GlassBg.lastTry = 0L
        GlassBg.clearAll(c)               // latar lama bergaya lain: buang, widget langsung memakai kaca cadangan bergaya baru
        GlassSlots.invalidate()
        GlassSlots.restyle(c)
        CcAccessibilityService.glassNudge()
    }
}
