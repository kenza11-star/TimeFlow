package app.timeflow

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Bahasa visual kaca (glassmorphism) bersama untuk Control Center: tint putih tipis, kilau diagonal
 * (terang di kiri atas, bening ke kanan bawah) dan rim light 1dp bergradasi. Dipakai kartu, tombol, slider, dan kartu musik.
 */
object CcGlass {
    /** Saturasi isi latar blur panel (140%) supaya warna wallpaper tetap hidup. */
    const val SAT = 1.4f

    /** Kilau isi: putih ~23% di kiri atas, memudar jadi nyaris bening di kanan bawah. */
    fun fillShader(l: Float, t: Float, r: Float, b: Float): Shader = LinearGradient(l, t, r, b,
        intArrayOf(Color.argb(58, 255, 255, 255), Color.argb(18, 255, 255, 255), Color.argb(6, 255, 255, 255)),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

    /** Rim light: putih ~38% di kiri atas, memudar di kanan bawah. */
    fun rimShader(l: Float, t: Float, r: Float, b: Float): Shader = LinearGradient(l, t, r, b,
        intArrayOf(Color.argb(98, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(24, 255, 255, 255)),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
}

/** Latar kaca berbentuk persegi membulat: tint + kilau + rim light. Pengganti GradientDrawable abu-abu polos. */
class CcGlassDrawable(private val radius: Float, private val density: Float) : Drawable() {
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 255, 255, 255) }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density.coerceAtLeast(1f) }
    private val box = RectF()
    private val inner = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val l = bounds.left.toFloat(); val t = bounds.top.toFloat(); val r = bounds.right.toFloat(); val b = bounds.bottom.toFloat()
        box.set(l, t, r, b)
        val h = rim.strokeWidth / 2f
        inner.set(l + h, t + h, r - h, b - h)
        sheen.shader = CcGlass.fillShader(l, t, r, b)
        rim.shader = CcGlass.rimShader(l, t, r, b)
    }

    override fun draw(c: Canvas) {
        if (box.width() <= 0f || box.height() <= 0f) return
        val rad = minOf(radius, minOf(box.width(), box.height()) / 2f)
        c.drawRoundRect(box, rad, rad, tint)
        c.drawRoundRect(box, rad, rad, sheen)
        c.drawRoundRect(inner, rad, rad, rim)
    }

    override fun setAlpha(alpha: Int) { tint.alpha = alpha; sheen.alpha = alpha; rim.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { tint.colorFilter = colorFilter; sheen.colorFilter = colorFilter; rim.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
