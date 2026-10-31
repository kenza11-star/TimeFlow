package app.timeflow

import android.content.Context
import org.json.JSONObject

/** Pengaturan notch (disimpan di SharedPreferences, dibaca native tanpa membuka UI). Satuan ukuran = dp. */
object NotchPrefs {
    data class Cfg(
        val enabled: Boolean, val idleVisible: Boolean, val showMedia: Boolean, val showNotif: Boolean,
        val hideLandscape: Boolean, val width: Int, val height: Int, val top: Int, val x: Int,
        val scale: Int, val autoHide: Int,
    )

    private fun sp(c: Context) = c.getSharedPreferences("notch", Context.MODE_PRIVATE)

    fun get(c: Context): Cfg = sp(c).let {
        Cfg(
            enabled = it.getBoolean("enabled", false),
            idleVisible = it.getBoolean("idleVisible", true),
            showMedia = it.getBoolean("showMedia", true),
            showNotif = it.getBoolean("showNotif", true),
            hideLandscape = it.getBoolean("hideLandscape", true),
            width = it.getInt("width", 126), height = it.getInt("height", 34),
            top = it.getInt("top", 10), x = it.getInt("x", 0),
            scale = it.getInt("scale", 100), autoHide = it.getInt("autoHide", 5),
        )
    }

    fun toJson(c: Context): JSONObject = get(c).let {
        JSONObject().put("enabled", it.enabled).put("idleVisible", it.idleVisible).put("showMedia", it.showMedia)
            .put("showNotif", it.showNotif).put("hideLandscape", it.hideLandscape).put("width", it.width)
            .put("height", it.height).put("top", it.top).put("x", it.x).put("scale", it.scale).put("autoHide", it.autoHide)
    }

    /** Terima sebagian/seluruh kunci; nilai dibatasi ke rentang aman. */
    fun set(c: Context, o: JSONObject) {
        val e = sp(c).edit()
        fun b(k: String) { if (o.has(k)) e.putBoolean(k, o.optBoolean(k)) }
        fun i(k: String, lo: Int, hi: Int) { if (o.has(k)) e.putInt(k, o.optInt(k).coerceIn(lo, hi)) }
        b("enabled"); b("idleVisible"); b("showMedia"); b("showNotif"); b("hideLandscape")
        i("width", 60, 260); i("height", 20, 60); i("top", 0, 90); i("x", -160, 160); i("scale", 80, 130); i("autoHide", 2, 15)
        e.apply()
    }

    fun reset(c: Context) {
        val on = get(c).enabled
        sp(c).edit().clear().putBoolean("enabled", on).apply()
    }
}
