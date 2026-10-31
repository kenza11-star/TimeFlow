package app.timeflow

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Latar kaca untuk widget layar utama (musik, jam, kalender, cuaca, agenda). Widget digambar oleh launcher, jadi widget tidak bisa
 * mengaburkan apa yang ada di belakangnya. Solusinya: ambil SATU tangkapan layar (tanpa widget-widget itu), potong persis di area
 * tiap widget, kaburkan, beri lapisan kaca (sama seperti Control Center), lalu pasang hasilnya sebagai gambar latar widget.
 * Dipanggil dari CcAccessibilityService. Satu bitmap per jenis widget (disimpan di berkas + cache memori kecil).
 */
object GlassBg {
    @Volatile var hidden = false          // true selama widget disembunyikan untuk mengambil tangkapan layar bersih
    @Volatile var lastTry = 0L            // percobaan terakhir (supaya tidak mengulang terus bila gagal)
    private val keys = java.util.concurrent.ConcurrentHashMap<String, String>()   // posisi + ukuran widget saat latar dibuat
    private val ats = java.util.concurrent.ConcurrentHashMap<String, Long>()      // kapan latar dibuat

    // cache memori kecil (maks ~3 MB): HP RAM kecil tidak menahan semua bitmap sekaligus; sisanya dibaca dari berkas
    private val cache = object : android.util.LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun file(ctx: Context, name: String) = File(ctx.filesDir, if (name == "music") "mw_glass.png" else "glass_$name.png")

    // gaya terang/gelap tiap bitmap (disimpan bersama bitmapnya): teks widget harus cocok dengan latar yang benar-benar terpasang
    private val lights = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile var lastLight = false      // hasil render() terakhir: apakah latar dibuat bergaya terang
    @Volatile var lastLum = 0f           // kecerahan rata-rata wallpaper di bawah widget (0..1)

    private fun lp(ctx: Context) = ctx.getSharedPreferences("timeflow_glass_state", Context.MODE_PRIVATE)

    /** Latar widget [name] sekarang bergaya terang? (hanya bila bitmap kacanya ada; kaca bawaan selalu gelap, jadi teks tetap putih) */
    fun isLight(ctx: Context, name: String): Boolean {
        if (!file(ctx, name).exists()) return GlassPrefs.get(ctx).style == GlassPrefs.STYLE_LIGHT   // kaca cadangan mengikuti pilihan Terang
        return lights.getOrPut(name) { lp(ctx).getBoolean("l_$name", false) }
    }

    fun hasBitmap(ctx: Context, name: String) = file(ctx, name).exists()

    /** Buang semua latar tersimpan (gaya berubah): widget memakai kaca cadangan sesuai gaya sampai latar blur baru jadi. */
    fun clearAll(ctx: Context) {
        for (d in GlassSlots.ALL) runCatching { file(ctx, d.name).delete() }
        cache.evictAll(); keys.clear(); ats.clear(); lights.clear()
        lp(ctx).edit().clear().apply()
    }

    fun key(name: String): String = keys[name] ?: ""
    fun at(name: String): Long = ats[name] ?: 0L

    fun get(ctx: Context, name: String): Bitmap? {
        cache.get(name)?.let { return it }
        val b = runCatching { file(ctx, name).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull() ?: return null
        cache.put(name, b)
        return b
    }

    /** Simpan latar baru. Mengembalikan true bila gaya terang/gelapnya berubah (widget perlu digambar ulang dengan warna teks yang cocok). */
    fun set(ctx: Context, name: String, b: Bitmap, k: String, light: Boolean = false): Boolean {
        val was = isLight(ctx, name)
        cache.put(name, b); keys[name] = k; ats[name] = System.currentTimeMillis()
        runCatching { file(ctx, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        lights[name] = light
        lp(ctx).edit().putBoolean("l_$name", light).apply()
        return was != light
    }

    /** Buat latar kaca dari tangkapan layar [shot] untuk area widget [r] (koordinat layar). */
    fun render(ctx: Context, shot: Bitmap, r: Rect, radiusDp: Float = 28f): Bitmap {
        val cfg = GlassPrefs.get(ctx)
        val d = ctx.resources.displayMetrics.density
        val pad = (40 * d).toInt()
        val x0 = max(0, r.left - pad); val y0 = max(0, r.top - pad)
        val x1 = min(shot.width, r.right + pad); val y1 = min(shot.height, r.bottom + pad)
        val region = Bitmap.createBitmap(shot, x0, y0, x1 - x0, y1 - y0)

        // kabur: kecilkan, kaburkan beberapa kali, lalu perbesar lagi (halus, tanpa RenderScript)
        val sw = max(2, region.width / cfg.blurDiv); val sh = max(2, region.height / cfg.blurDiv)
        val small = Bitmap.createScaledBitmap(region, sw, sh, true)
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        // kecerahan rata-rata sebelum diblur (untuk gaya Otomatis)
        var sum = 0L
        for (v in px) sum += ((v shr 16) and 255) * 299 + ((v shr 8) and 255) * 587 + (v and 255) * 114
        val lum = (sum / (px.size.toFloat() * 1000f * 255f)).coerceIn(0f, 1f)
        val light = when (cfg.style) { GlassPrefs.STYLE_LIGHT -> true; GlassPrefs.STYLE_AUTO -> lum > 0.55f; else -> false }
        lastLum = lum; lastLight = light
        repeat(cfg.blurPasses) { boxBlur(px, sw, sh, cfg.blurR) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        val blurred = Bitmap.createScaledBitmap(small, region.width, region.height, true)

        // hasil akhir dibatasi lebar 640 px supaya ringan dikirim ke launcher (batas memori RemoteViews, HP RAM kecil)
        val f = min(1f, 640f / r.width())
        val ow = max(2, (r.width() * f).toInt()); val oh = max(2, (r.height() * f).toInt())
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rad = cfg.radiusDp * d * f
        val box = RectF(0f, 0f, ow.toFloat(), oh.toFloat())
        val clip = Path().apply { addRoundRect(box, rad, rad, Path.Direction.CW) }
        c.save(); c.clipPath(clip)

        // 1) isi kabur, sedikit lebih hidup warnanya (seperti kaca)
        val sat = ColorMatrix().apply { setSaturation(1.35f) }
        val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(sat) }
        c.drawBitmap(blurred, Rect(r.left - x0, r.top - y0, r.right - x0, r.bottom - y0), box, bp)
        // lepas bitmap perantara segera (hemat memori di HP RAM kecil); cek identitas karena createBitmap/createScaledBitmap bisa mengembalikan objek yang sama
        if (blurred !== small && blurred !== region && blurred !== shot) blurred.recycle()
        if (small !== region && small !== shot) small.recycle()
        if (region !== shot) region.recycle()
        // 2) gaya gelap: peredup hitam supaya teks putih jelas; gaya terang: kabut putih supaya teks gelap jelas
        if (light) c.drawColor(Color.argb(cfg.lightAlpha, 255, 255, 255)) else c.drawColor(Color.argb(cfg.darkAlpha, 0, 0, 0))
        // 3) lapisan kaca: putih tipis + kilau diagonal
        c.drawColor(Color.argb(if (light) 10 else 20, 255, 255, 255))
        val k = cfg.sheenK
        val sheen = Paint().apply {
            shader = LinearGradient(0f, 0f, ow * 0.9f, oh.toFloat(),
                intArrayOf(Color.argb((70 * k).toInt().coerceIn(0, 255), 255, 255, 255), Color.argb((18 * k).toInt().coerceIn(0, 255), 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(box, sheen)
        // 3b) kedalaman: bayangan sangat tipis di bagian bawah supaya kaca terasa tebal
        val depth = Paint().apply {
            shader = LinearGradient(0f, oh * 0.6f, 0f, oh.toFloat(),
                Color.argb(0, 0, 0, 0), Color.argb(if (light) 22 else 46, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        c.drawRect(box, depth)
        c.restore()

        // 4) tepi kaca: terang di kiri atas, memudar di kanan bawah
        val sw1 = max(1f, 1.2f * d * f)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw1
            shader = LinearGradient(0f, 0f, ow.toFloat(), oh.toFloat(),
                Color.argb(150, 255, 255, 255), Color.argb(40, 255, 255, 255), Shader.TileMode.CLAMP)
        }
        val h = sw1 / 2f
        c.drawRoundRect(RectF(h, h, ow - h, oh - h), rad - h, rad - h, edge)
        return out
    }

    /** Box blur terpisah (horizontal lalu vertikal) dengan jumlah berjalan: cepat, cukup untuk bitmap kecil. */
    private fun boxBlur(p: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(p.size)
        pass(p, tmp, w, h, r, true)
        pass(tmp, p, w, h, r, false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val len = if (horiz) w else h
        val lines = if (horiz) h else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            fun px(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horiz) src[l * w + k] else src[k * w + l] }
            for (i in -r..r) { val v = px(i); sa += v ushr 24; sr += (v shr 16) and 255; sg += (v shr 8) and 255; sb += v and 255 }
            for (i in 0 until len) {
                val o = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                if (horiz) dst[l * w + i] = o else dst[i * w + l] = o
                val add = px(i + r + 1); val rem = px(i - r)
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }
}


/**
 * Daftar widget yang memakai latar kaca. Tiap layout punya: akar (rootId; dicari lewat aksesibilitas untuk mengetahui posisinya),
 * gambar kaca (glassId) dan wadah isi (contentId). Menyembunyikan/menampilkan memakai pembaruan sebagian pada layout yang SAMA
 * dengan yang sedang tampil (tiap widget hanya punya satu layout), jadi aman bagi RemoteViews.
 */
object GlassSlots {
    class Def(val name: String, val layout: Int, val rootId: Int, val rootName: String, val glassId: Int, val contentId: Int)

    val MUSIC = Def("music", R.layout.widget_music, R.id.mw_root, "mw_root", R.id.mw_glass, R.id.mw_content)
    val CLOCK = Def("clock", R.layout.widget_clock, R.id.gr_clock, "gr_clock", R.id.gw_clock, R.id.gc_clock)
    val CALENDAR = Def("calendar", R.layout.widget_calendar, R.id.gr_calendar, "gr_calendar", R.id.gw_calendar, R.id.gc_calendar)
    val WEATHER = Def("weather", R.layout.widget_weather, R.id.gr_weather, "gr_weather", R.id.gw_weather, R.id.gc_weather)
    val AGENDA = Def("agenda", R.layout.widget_agenda, R.id.gr_agenda, "gr_agenda", R.id.gw_agenda, R.id.gc_agenda)
    val SCREENTIME = Def("screentime", R.layout.widget_screentime, R.id.gr_screentime, "gr_screentime", R.id.gw_screentime, R.id.gc_screentime)
    val ALL = listOf(MUSIC, CLOCK, CALENDAR, WEATHER, AGENDA, SCREENTIME)

    private fun has(ctx: Context, d: Def): Boolean = when {
        d === MUSIC -> MusicWidget.has(ctx)
        d === SCREENTIME -> ScreenTimeWidget.has(ctx)
        else -> WidgetUtil.has(ctx, d.name)
    }

    /** Jenis widget yang saat ini terpasang di layar utama. */
    fun present(ctx: Context): List<Def> = ALL.filter { has(ctx, it) }

    private var anyAt = 0L
    private var anyVal = false
    /** Versi murah (cache 5 detik) untuk dipanggil dari event aksesibilitas yang sering. */
    @Synchronized fun anyCached(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - anyAt > 5_000L) { anyVal = present(ctx).isNotEmpty(); anyAt = now }
        return anyVal
    }
    @Synchronized fun invalidate() { anyAt = 0L }

    private fun ids(ctx: Context, d: Def): IntArray = runCatching {
        val cls: Class<*> = when (d.name) {
            "music" -> MusicWidget::class.java
            "clock" -> ClockWidget::class.java
            "calendar" -> CalendarWidget::class.java
            "weather" -> WeatherWidget::class.java
            "screentime" -> ScreenTimeWidget::class.java
            else -> AgendaWidget::class.java
        }
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))
    }.getOrDefault(IntArray(0))

    private fun applyVis(ctx: Context, defs: List<Def>, visible: Boolean) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        for (d in defs) {
            val all = ids(ctx, d); if (all.isEmpty()) continue
            val rv = RemoteViews(ctx.packageName, d.layout)
            if (visible) GlassBg.get(ctx, d.name)?.let { rv.setImageViewBitmap(d.glassId, it) }
            rv.setViewVisibility(d.glassId, v); rv.setViewVisibility(d.contentId, v)
            runCatching { mgr.partiallyUpdateAppWidget(all, rv) }
        }
    }

    /** Gaya terang/gelap latar berubah: gambar ulang semua widget supaya warna teksnya cocok dengan latar yang baru. */
    fun restyle(ctx: Context) {
        runCatching { WidgetUtil.updateAll(ctx) }
        runCatching { if (MusicWidget.has(ctx)) MusicWidget.push(ctx, NotchListener.hubMedia()) }
        runCatching { ScreenTimeWidget.render(ctx) }
    }

    /** Sembunyikan widget sesaat agar tangkapan layar tidak memuat widget itu sendiri. */
    fun hide(ctx: Context, defs: List<Def>) { GlassBg.hidden = true; applyVis(ctx, defs, false) }

    /** Tampilkan lagi widget, sekaligus memasang latar kaca terbaru. */
    fun show(ctx: Context, defs: List<Def>) { GlassBg.hidden = false; applyVis(ctx, defs, true) }
}
