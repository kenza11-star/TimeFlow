package app.timeflow

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
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Latar kaca untuk widget musik. Widget layar utama digambar oleh launcher, jadi widget tidak bisa mengaburkan apa yang ada
 * di belakangnya. Solusinya: ambil tangkapan layar (tanpa widget), potong persis di area widget, kaburkan, beri lapisan kaca
 * (sama seperti Control Center), lalu pasang hasilnya sebagai gambar latar widget. Dipanggil dari CcAccessibilityService.
 */
object GlassBg {
    @Volatile var hidden = false          // true selama widget disembunyikan untuk mengambil tangkapan layar bersih
    @Volatile var key = ""                // posisi + ukuran widget saat latar dibuat
    @Volatile var at = 0L                 // kapan latar dibuat
    @Volatile var lastTry = 0L            // percobaan terakhir (supaya tidak mengulang terus bila gagal)
    @Volatile private var bmp: Bitmap? = null
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "mw_glass.png")

    @Synchronized fun get(ctx: Context): Bitmap? {
        if (!loaded) {
            loaded = true
            runCatching { file(ctx).takeIf { it.exists() }?.let { bmp = BitmapFactory.decodeFile(it.path) } }
        }
        return bmp
    }

    @Synchronized fun set(ctx: Context, b: Bitmap, k: String) {
        bmp = b; loaded = true; key = k; at = System.currentTimeMillis()
        runCatching { file(ctx).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    /** Buat latar kaca dari tangkapan layar [shot] untuk area widget [r] (koordinat layar). */
    fun render(ctx: Context, shot: Bitmap, r: Rect): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val pad = (40 * d).toInt()
        val x0 = max(0, r.left - pad); val y0 = max(0, r.top - pad)
        val x1 = min(shot.width, r.right + pad); val y1 = min(shot.height, r.bottom + pad)
        val region = Bitmap.createBitmap(shot, x0, y0, x1 - x0, y1 - y0)

        // kabur: kecilkan, kaburkan beberapa kali, lalu perbesar lagi (halus, tanpa RenderScript)
        val sw = max(2, region.width / 8); val sh = max(2, region.height / 8)
        val small = Bitmap.createScaledBitmap(region, sw, sh, true)
        val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) { boxBlur(px, sw, sh, 5) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        val blurred = Bitmap.createScaledBitmap(small, region.width, region.height, true)

        // hasil akhir dibatasi lebar 720 px supaya ringan dikirim ke launcher
        val f = min(1f, 720f / r.width())
        val ow = max(2, (r.width() * f).toInt()); val oh = max(2, (r.height() * f).toInt())
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val rad = 28f * d * f
        val box = RectF(0f, 0f, ow.toFloat(), oh.toFloat())
        val clip = Path().apply { addRoundRect(box, rad, rad, Path.Direction.CW) }
        c.save(); c.clipPath(clip)

        // 1) isi kabur, sedikit lebih hidup warnanya (seperti kaca)
        val sat = ColorMatrix().apply { setSaturation(1.35f) }
        val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(sat) }
        c.drawBitmap(blurred, Rect(r.left - x0, r.top - y0, r.right - x0, r.bottom - y0), box, bp)
        // 2) peredup tipis (sama dengan latar Control Center) supaya teks putih tetap jelas
        c.drawColor(Color.argb(70, 0, 0, 0))
        // 3) lapisan kaca: putih tipis + kilau diagonal
        c.drawColor(Color.argb(20, 255, 255, 255))
        val sheen = Paint().apply {
            shader = LinearGradient(0f, 0f, ow * 0.9f, oh.toFloat(),
                intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(18, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(box, sheen)
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
