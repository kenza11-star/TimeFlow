package app.timeflow

import android.content.Context

/**
 * Pengaturan gaya kaca widget (satu pengaturan untuk semua widget): gaya (gelap / terang / otomatis), kekuatan blur,
 * kepekatan, kilau, dan sudut. Disimpan di SharedPreferences; dibaca GlassBg saat membuat latar. Diubah dari WidgetStyleActivity.
 * Nilai bawaan = tampilan yang sebelumnya tetap (gelap, blur sedang, sudut 28dp).
 */
object GlassPrefs {
    private const val SP = "timeflow_glass"
    const val STYLE_DARK = 0
    const val STYLE_LIGHT = 1
    const val STYLE_AUTO = 2

    class Cfg(val style: Int, val blur: Int, val dim: Int, val sheen: Int, val radius: Int) {
        /** Kekuatan blur: pengecilan bitmap, jumlah putaran, dan radius kotak. Makin besar pembagi, makin kabur. */
        val blurDiv get() = when (blur) { 0 -> 6; 2 -> 12; else -> 8 }
        val blurPasses get() = when (blur) { 0 -> 2; 2 -> 4; else -> 3 }
        val blurR get() = when (blur) { 0 -> 3; 2 -> 6; else -> 5 }
        val radiusDp get() = when (radius) { 0 -> 12f; 2 -> 40f; else -> 28f }
        /** Lapisan peredup hitam (gaya gelap): 0..100% -> alpha 0..138 (bawaan 50% = 69, sama dengan dulu). */
        val darkAlpha get() = (dim * 1.38f).toInt().coerceIn(0, 255)
        /** Lapisan kabut putih (gaya terang): 0..100% -> alpha 0..179 (bawaan 50% = 89). */
        val lightAlpha get() = (dim * 1.79f).toInt().coerceIn(0, 255)
        val sheenK get() = sheen / 50f
    }

    private fun sp(c: Context) = c.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun get(c: Context): Cfg {
        val s = sp(c)
        return Cfg(s.getInt("style", STYLE_DARK).coerceIn(0, 2), s.getInt("blur", 1).coerceIn(0, 2), s.getInt("dim", 50).coerceIn(0, 100),
            s.getInt("sheen", 50).coerceIn(0, 100), s.getInt("radius", 1).coerceIn(0, 2))
    }

    /** Tanda tangan pengaturan: ikut dalam kunci latar, jadi mengubah pengaturan otomatis membuat latar baru. */
    fun sig(c: Context): String { val g = get(c); return "s${g.style}b${g.blur}d${g.dim}w${g.sheen}r${g.radius}" }

    fun set(c: Context, style: Int? = null, blur: Int? = null, dim: Int? = null, sheen: Int? = null, radius: Int? = null) {
        val e = sp(c).edit()
        style?.let { e.putInt("style", it) }; blur?.let { e.putInt("blur", it) }; dim?.let { e.putInt("dim", it) }
        sheen?.let { e.putInt("sheen", it) }; radius?.let { e.putInt("radius", it) }
        e.apply()
    }

    fun reset(c: Context) { sp(c).edit().clear().apply() }

    /** Minta latar kaca dibuat ulang sekarang (setelah pengaturan berubah). */
    fun applyNow(c: Context) {
        GlassBg.lastTry = 0L
        GlassSlots.invalidate()
        CcAccessibilityService.glassNudge()
    }
}
