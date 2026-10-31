package app.timeflow

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Wallpaper milik peluncur: dipilih sendiri (galeri) atau diambil dari sistem bila boleh. Dari wallpaper dibuat dua bitmap:
 * [full] pas layar (dasar tampilan) dan [blur] versi kabur kecil (bahan semua panel kaca). Panel kaca ([GlassPanelDrawable]) hanya
 * menggambar potongan [blur] persis di belakang posisinya, jadi kaca selalu sinkron saat digeser dan ringan (tanpa tangkapan layar).
 */
object HomeWall {
    @Volatile var full: Bitmap? = null
    @Volatile var blur: Bitmap? = null
    @Volatile var winW = 1
    @Volatile var winH = 1
    @Volatile var lum = 0.4f
    @Volatile var fallback = false      // true bila wallpaper sistem tidak bisa dibaca dan belum ada pilihan pengguna
    @Volatile private var builtKey = ""
    private const val DIV = 6

    fun file(ctx: Context) = File(ctx.filesDir, "home_wall.jpg")

    private fun key(ctx: Context, w: Int, h: Int) = "${w}x$h|${GlassPrefs.get(ctx).blur}|${file(ctx).lastModified()}"

    fun needs(ctx: Context, w: Int, h: Int) = builtKey != key(ctx, w, h)

    private fun gradient(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(max(2, w / 4), max(2, h / 4), Bitmap.Config.ARGB_8888)
        val p = Paint().apply {
            shader = LinearGradient(0f, 0f, b.width.toFloat(), b.height.toFloat(),
                intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF5B3A8E.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        Canvas(b).drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), p)
        return b
    }

    private fun decodeFile(f: File, w: Int, h: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        return BitmapFactory.decodeFile(f.path, o2)
    }

    /** Sumber wallpaper: pilihan pengguna, lalu wallpaper sistem (bila diizinkan), lalu gradasi bawaan. Selalu mengembalikan bitmap milik sendiri. */
    private fun loadSource(ctx: Context, w: Int, h: Int): Bitmap {
        fallback = false
        val f = file(ctx)
        if (f.exists()) decodeFile(f, w, h)?.let { return it }
        runCatching {
            val d = WallpaperManager.getInstance(ctx).drawable
            if (d != null) {
                val iw = max(2, min(d.intrinsicWidth, 2000)); val ih = max(2, d.intrinsicHeight * iw / max(1, d.intrinsicWidth))
                val b = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, iw, ih); d.draw(Canvas(b))
                return b
            }
        }
        fallback = true
        return gradient(w, h)
    }

    private fun shrink(src: Bitmap, tw: Int, th: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            val n = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = n
        }
        val fin = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src && fin !== cur) cur.recycle()
        return fin
    }

    private fun hPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + min(w - 1, max(0, i))]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val o = src[row + min(w - 1, max(0, x - r))]
                val n = src[row + min(w - 1, max(0, x + r + 1))]
                sr += ((n shr 16) and 255) - ((o shr 16) and 255)
                sg += ((n shr 8) and 255) - ((o shr 8) and 255)
                sb += (n and 255) - (o and 255)
            }
        }
    }

    private fun vPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[min(h - 1, max(0, i)) * w + x]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val o = src[min(h - 1, max(0, y - r)) * w + x]
                val n = src[min(h - 1, max(0, y + r + 1)) * w + x]
                sr += ((n shr 16) and 255) - ((o shr 16) and 255)
                sg += ((n shr 8) and 255) - ((o shr 8) and 255)
                sb += (n and 255) - (o and 255)
            }
        }
    }

    /** Bangun [full] dan [blur] untuk jendela berukuran w x h. Panggil dari thread latar. */
    @Synchronized
    fun build(ctx: Context, w: Int, h: Int) {
        if (w < 2 || h < 2) return
        val src = loadSource(ctx, w, h)
        val f = min(1f, 900f / w)
        val fw = max(2, (w * f).toInt()); val fh = max(2, (h * f).toInt())
        val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val sc = max(fw / src.width.toFloat(), fh / src.height.toFloat())
        val dw = src.width * sc; val dh = src.height * sc
        Canvas(out).drawBitmap(src, null, RectF((fw - dw) / 2f, (fh - dh) / 2f, (fw + dw) / 2f, (fh + dh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()

        // kabur: kecilkan, box blur beberapa putaran, naikkan saturasi 140%
        val lvl = GlassPrefs.get(ctx).blur
        val r = when (lvl) { 0 -> 2; 2 -> 6; else -> 3 }
        val passes = if (lvl == 0) 2 else 3
        val bw = max(2, w / DIV); val bh = max(2, h / DIV)
        val small = shrink(out, bw, bh)
        val px = IntArray(bw * bh)
        small.getPixels(px, 0, bw, 0, 0, bw, bh)
        var sum = 0L
        for (v in px) sum += ((v shr 16) and 255) * 299 + ((v shr 8) and 255) * 587 + (v and 255) * 114
        val l = (sum / (px.size.toFloat() * 1000f * 255f)).coerceIn(0f, 1f)
        val tmp = IntArray(px.size)
        repeat(passes) { hPass(px, tmp, bw, bh, r); vPass(tmp, px, bw, bh, r) }
        small.setPixels(px, 0, bw, 0, 0, bw, bh)
        val sat = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(1.4f) }
        Canvas(sat).drawBitmap(small, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        if (small !== out) small.recycle()

        winW = w; winH = h; lum = l
        full = out; blur = sat
        builtKey = key(ctx, w, h)
    }

    /** Salin gambar pilihan pengguna (uri dari galeri) ke berkas peluncur, diperkecil. */
    fun saveFromUri(ctx: Context, uri: Uri): Boolean = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= 2400) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        val b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return@runCatching false
        file(ctx).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        b.recycle()
        true
    }.getOrDefault(false)
}

/**
 * Latar kaca (glassmorphism) untuk view [host]: menggambar potongan wallpaper kabur tepat di belakang posisi view di jendela
 * (ikut berubah saat view bergeser), lalu tint tipis, kilau diagonal, dan rim light halus. Panggil host.invalidate() saat view bergerak.
 */
class GlassPanelDrawable(private val host: View, private val radius: Float, private val k: Float = 1f) : Drawable() {
    private val cfg = GlassPrefs.get(host.context)
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintBlack = Paint()
    private val tintWhite = Paint().apply { color = Color.argb((38 * k).toInt().coerceIn(0, 255), 255, 255, 255) }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, host.resources.displayMetrics.density) }
    private val box = RectF()
    private val inner = RectF()
    private val clip = Path()
    private val loc = IntArray(2)
    private val src = Rect()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val l = bounds.left.toFloat(); val t = bounds.top.toFloat(); val r = bounds.right.toFloat(); val b = bounds.bottom.toFloat()
        inner.set(l + rim.strokeWidth / 2f, t + rim.strokeWidth / 2f, r - rim.strokeWidth / 2f, b - rim.strokeWidth / 2f)
        val sk = cfg.sheenK
        sheen.shader = LinearGradient(l, t, r, b,
            intArrayOf(Color.argb((50 * sk * k).toInt().coerceIn(0, 255), 255, 255, 255), Color.argb((12 * sk * k).toInt().coerceIn(0, 255), 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(l, t, r, b,
            intArrayOf(Color.argb(60, 255, 255, 255), Color.argb(20, 255, 255, 255), Color.argb(8, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        box.set(b)
        val rad = min(radius, min(box.width(), box.height()) / 2f)
        clip.reset(); clip.addRoundRect(box, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        val bl = HomeWall.blur
        if (bl != null) {
            host.getLocationInWindow(loc)
            val fx = bl.width / HomeWall.winW.toFloat(); val fy = bl.height / HomeWall.winH.toFloat()
            val l = ((loc[0] + b.left) * fx).toInt().coerceIn(0, bl.width - 1)
            val t = ((loc[1] + b.top) * fy).toInt().coerceIn(0, bl.height - 1)
            val r = ((loc[0] + b.right) * fx).toInt().coerceIn(l + 1, bl.width)
            val bt = ((loc[1] + b.bottom) * fy).toInt().coerceIn(t + 1, bl.height)
            src.set(l, t, r, bt)
            c.drawBitmap(bl, src, box, bmp)
        } else c.drawColor(Color.argb(70, 24, 24, 32))
        // tint hitam tipis (lebih kuat di wallpaper terang agar teks putih terbaca) + tint putih tipis
        val extra = (((HomeWall.lum - 0.35f) / 0.5f).coerceIn(0f, 1f) * 56f)
        tintBlack.color = Color.argb((cfg.darkAlpha * k + extra).toInt().coerceIn(0, 255), 0, 0, 0)
        c.drawRect(box, tintBlack)
        c.drawRect(box, tintWhite)
        c.drawRect(box, sheen)
        c.restore()
        c.drawRoundRect(inner, max(0f, rad - rim.strokeWidth / 2f), max(0f, rad - rim.strokeWidth / 2f), rim)
    }

    override fun setAlpha(alpha: Int) { bmp.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { bmp.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Pager horizontal yang selalu berhenti pas di satu halaman (tanpa momentum liar). */
class HPager(ctx: Context) : HorizontalScrollView(ctx) {
    var pageW = 0
    var onPage: ((Int) -> Unit)? = null
    var onScroll: (() -> Unit)? = null
    private var flung = false
    private var lastPage = 0

    init { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; isFillViewport = false }

    private fun count(): Int = (getChildAt(0) as? android.view.ViewGroup)?.childCount ?: 1

    override fun fling(velocityX: Int) {
        flung = true
        if (pageW <= 0) return
        val cur = scrollX.toFloat() / pageW
        val base = Math.floor(cur.toDouble()).toInt()
        val target = if (velocityX > 300) base + 1 else if (velocityX < -300) base else Math.round(cur)
        goTo(target, true)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        val r = super.onTouchEvent(ev)
        if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && !flung && pageW > 0) {
            goTo(Math.round(scrollX.toFloat() / pageW), true)
        }
        return r
    }

    fun goTo(p: Int, smooth: Boolean) {
        val t = p.coerceIn(0, max(0, count() - 1))
        if (smooth) smoothScrollTo(t * pageW, 0) else scrollTo(t * pageW, 0)
        if (t != lastPage) { lastPage = t; onPage?.invoke(t) }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScroll?.invoke()
        if (pageW > 0) {
            val p = Math.round(l.toFloat() / pageW)
            if (p != lastPage) { lastPage = p; onPage?.invoke(p) }
        }
    }
}

/** FrameLayout yang menangkap tekan-lama sendiri (widget hasil host punya anak yang bisa diklik dan menelan tekan-lama). */
class LongPressFrame(ctx: Context) : FrameLayout(ctx) {
    var onLong: (() -> Unit)? = null
    private val h = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var sx = 0f
    private var sy = 0f
    private var fired = false
    private val run = Runnable { fired = true; onLong?.invoke() }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y; fired = false
                h.postDelayed(run, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - sx) > slop || abs(e.y - sy) > slop) h.removeCallbacks(run)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> h.removeCallbacks(run)
        }
        return fired
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) h.removeCallbacks(run)
        return fired || super.onTouchEvent(e)
    }
}
