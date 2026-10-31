package app.timeflow

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject

/**
 * Widget "Folder App" bergaya App Library iPhone: kartu kaca berisi ubin ikon kaca (2x2). Ketuk ikon = buka aplikasi asli,
 * ketuk kartu/judul = buka folder penuh (FolderActivity). Isi folder dipilih di FolderConfigActivity, disimpan per widget.
 */
object FolderStore {
    const val MAX = 12
    private const val SP = "timeflow_folder"

    /** [mono] true = ikon kaca putih (bila ikon mendukung), false = warna asli aplikasi. */
    class Data(val name: String, val pkgs: List<String>, val mono: Boolean)

    private fun sp(c: Context) = c.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun get(c: Context, id: Int): Data {
        val raw = sp(c).getString("w$id", null) ?: return Data("Folder", emptyList(), true)
        return runCatching {
            val o = JSONObject(raw)
            val a = o.optJSONArray("p") ?: JSONArray()
            val l = ArrayList<String>()
            for (i in 0 until a.length()) l.add(a.getString(i))
            Data(o.optString("n", "Folder"), l, o.optBoolean("m", true))
        }.getOrElse { Data("Folder", emptyList(), true) }
    }

    fun set(c: Context, id: Int, d: Data) {
        val a = JSONArray()
        for (p in d.pkgs) a.put(p)
        val o = JSONObject()
        o.put("n", d.name); o.put("p", a); o.put("m", d.mono)
        sp(c).edit().putString("w$id", o.toString()).apply()
    }

    fun remove(c: Context, id: Int) { sp(c).edit().remove("w$id").apply() }
}

/** Menggambar ubin kaca + ikon aplikasi menjadi bitmap kecil (cache kecil, hemat memori). */
object FolderIcons {
    private const val SZ = 144
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 12).toInt().coerceIn(2 * 1024 * 1024, 24 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Ubin kaca: isi tipis + kilau diagonal + rim light halus, sudut sangat bulat. */
    fun drawGlassTile(c: Canvas, r: RectF, light: Boolean) {
        val rad = r.width() * 0.30f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) Color.argb(18, 0, 0, 0) else Color.argb(40, 255, 255, 255) }
        c.drawRoundRect(r, rad, rad, fill)
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(Color.argb(if (light) 90 else 62, 255, 255, 255), Color.argb(14, 255, 255, 255), Color.argb(4, 255, 255, 255)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, rad, rad, sheen)
        val sw = maxOf(1.5f, r.width() * 0.012f)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw
            shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(Color.argb(96, 255, 255, 255), Color.argb(34, 255, 255, 255), Color.argb(14, 255, 255, 255)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
        val h = sw / 2f
        c.drawRoundRect(RectF(r.left + h, r.top + h, r.right - h, r.bottom - h), rad - h, rad - h, rim)
    }

    private fun appIcon(ctx: Context, pkg: String): Drawable? = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** Ikon putih (siluet dari lapisan monokrom/depan ikon adaptif). false bila lapisan tidak cocok (kosong atau memenuhi seluruh kotak). */
    private fun drawMono(layer: Drawable, light: Boolean, c: Canvas, r: RectF): Boolean {
        val s = 216
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        try {
            layer.setBounds(0, 0, s, s)
            layer.draw(Canvas(bmp))
            val px = IntArray(s * s)
            bmp.getPixels(px, 0, s, 0, 0, s, s)
            var n = 0
            for (v in px) if ((v ushr 24) > 24) n++
            val cov = n / (s * s).toFloat()
            if (cov < 0.02f || cov > 0.8f) return false
            val ink = if (light) 28f else 255f
            val cm = ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, ink,
                0f, 0f, 0f, 0f, ink,
                0f, 0f, 0f, 0f, ink,
                0f, 0f, 0f, 0.95f, 0f))
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) }
            val half = r.width() * 0.42f          // 108dp penuh = 0.84 lebar ubin (zona aman 72dp ~ 0.56)
            c.drawBitmap(bmp, null, RectF(r.centerX() - half, r.centerY() - half, r.centerX() + half, r.centerY() + half), p)
            return true
        } finally {
            bmp.recycle()
        }
    }

    private fun drawGlyph(d: Drawable, mono: Boolean, light: Boolean, c: Canvas, r: RectF) {
        if (mono && d is AdaptiveIconDrawable) {
            var layer: Drawable? = null
            if (Build.VERSION.SDK_INT >= 33) layer = d.monochrome
            if (layer == null) layer = d.foreground
            if (layer != null && drawMono(layer, light, c, r)) return
        }
        // warna asli: ikon dipotong bulat di tengah ubin
        val inset = r.width() * 0.18f
        val box = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        val clip = Path().apply { addRoundRect(box, box.width() * 0.24f, box.width() * 0.24f, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        d.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        d.draw(c)
        c.restore()
    }

    fun tile(ctx: Context, pkg: String, mono: Boolean, light: Boolean, size: Int = SZ): Bitmap? {
        val key = "$pkg|$mono|$light|$size"
        cache.get(key)?.let { return it }
        val d = appIcon(ctx, pkg) ?: return null
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = RectF(0f, 0f, size.toFloat(), size.toFloat())
        drawGlassTile(c, r, light)
        drawGlyph(d, mono, light, c, r)
        cache.put(key, b)
        return b
    }

    /** Ubin kosong (slot belum terisi). */
    fun empty(light: Boolean, size: Int = SZ): Bitmap {
        val key = "empty|$light|$size"
        cache.get(key)?.let { return it }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawGlassTile(Canvas(b), RectF(0f, 0f, size.toFloat(), size.toFloat()), light)
        cache.put(key, b)
        return b
    }

    /** Ubin berisi 2x2 ikon mini (sisa aplikasi folder), seperti App Library. */
    fun mini(ctx: Context, pkgs: List<String>, mono: Boolean, light: Boolean, size: Int = SZ): Bitmap {
        val key = "mini|" + pkgs.joinToString(",") + "|$mono|$light|$size"
        cache.get(key)?.let { return it }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        drawGlassTile(c, RectF(0f, 0f, size.toFloat(), size.toFloat()), light)
        val m = size * 0.36f
        val gap = size * 0.08f
        val p = (size - 2 * m - gap) / 2f
        pkgs.take(4).forEachIndexed { i, pkg ->
            val d = appIcon(ctx, pkg)
            if (d != null) {
                val x = p + (i % 2) * (m + gap)
                val y = p + (i / 2) * (m + gap)
                val rr = RectF(x, y, x + m, y + m)
                drawGlassTile(c, rr, light)
                drawGlyph(d, mono, light, c, rr)
            }
        }
        cache.put(key, b)
        return b
    }
}

object FolderUtil {
    private val TILES = intArrayOf(R.id.f_t0, R.id.f_t1, R.id.f_t2, R.id.f_t3)

    /** Ketuk kartu/judul: buka folder penuh, atau layar pilih aplikasi bila folder masih kosong. */
    private fun openPi(ctx: Context, id: Int, empty: Boolean): PendingIntent {
        val cls: Class<*> = if (empty) FolderConfigActivity::class.java else FolderActivity::class.java
        val intent = Intent(ctx, cls)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("timeflow://folder/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 6000 + id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun launchPi(ctx: Context, pkg: String): PendingIntent? {
        val li = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(ctx, pkg.hashCode(), li, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun build(ctx: Context, id: Int): RemoteViews {
        val rv = WidgetUtil.base(ctx, GlassSlots.FOLDER)
        val light = WidgetTheme.light(ctx, GlassSlots.FOLDER)
        val pm = ctx.packageManager
        val data = FolderStore.get(ctx, id)
        val pkgs = data.pkgs.filter { pm.getLaunchIntentForPackage(it) != null }      // aplikasi yang sudah dihapus dilewati
        val open = openPi(ctx, id, pkgs.isEmpty())
        rv.setOnClickPendingIntent(R.id.gr_folder, open)
        rv.setOnClickPendingIntent(R.id.f_title, open)
        rv.setTextViewText(R.id.f_title, if (pkgs.isEmpty()) "Ketuk untuk mengisi" else data.name)
        for (i in 0 until 4) {
            var bmp: Bitmap? = null
            var pi: PendingIntent = open
            if (i < 3 || pkgs.size <= 4) {
                val p = pkgs.getOrNull(i)
                if (p != null) {
                    bmp = FolderIcons.tile(ctx, p, data.mono, light)
                    val lp = launchPi(ctx, p)
                    if (lp != null) pi = lp
                }
            } else {
                bmp = FolderIcons.mini(ctx, pkgs.subList(3, minOf(pkgs.size, 7)), data.mono, light)
            }
            rv.setImageViewBitmap(TILES[i], bmp ?: FolderIcons.empty(light))
            rv.setOnClickPendingIntent(TILES[i], pi)
        }
        return rv
    }

    fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) runCatching { mgr.updateAppWidget(id, build(ctx, id)) }
    }

    fun updateOne(ctx: Context, id: Int) = update(ctx, AppWidgetManager.getInstance(ctx), intArrayOf(id))
}

class FolderWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        FolderUtil.update(ctx, mgr, ids)
        CcAccessibilityService.glassNudge()
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        FolderUtil.update(ctx, mgr, intArrayOf(id)); CcAccessibilityService.glassNudge()
    }
    override fun onDeleted(ctx: Context, appWidgetIds: IntArray) {
        for (i in appWidgetIds) FolderStore.remove(ctx, i)
    }
    override fun onDisabled(ctx: Context) { GlassSlots.invalidate() }
}
