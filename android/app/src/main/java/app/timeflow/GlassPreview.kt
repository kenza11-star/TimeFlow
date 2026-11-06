package app.timeflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView

/** Latar berwarna dengan lingkaran: dipakai pratinjau kaca (satu tajam, satu diburamkan). */
internal class Blobs(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF2A5CFF.toInt(), 0xFF8E2DE2.toInt(), 0xFFFF6A88.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        p.color = 0xFFFFD166.toInt(); c.drawCircle(w * 0.18f, h * 0.30f, h * 0.20f, p)
        p.color = 0xFF06D6A0.toInt(); c.drawCircle(w * 0.82f, h * 0.72f, h * 0.24f, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(w * 0.62f, h * 0.18f, h * 0.10f, p)
    }
}

/**
 * Pratinjau kaca (300x180dp): kartu yang memotong salinan latar yang diburamkan, dengan tint, kilau, dan sudut sesuai pengaturan.
 * Hanya perkiraan: blur asli di widget memakai wallpaper dan kamu bisa melihatnya langsung di layar utama.
 */
internal class GlassPreview(ctx: Context) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val blurred = Blobs(ctx)
    private val tint = View(ctx)
    private val sheen = View(ctx)
    private val time = TextView(ctx)
    private val card = FrameLayout(ctx)
    private var cardR = 28f

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 28 * d) }
        }
        clipToOutline = true
        addView(Blobs(ctx), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val cl = (50 * d).toInt(); val ct = (40 * d).toInt()
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, cardR * d) }
        }
        card.clipToOutline = true
        card.addView(blurred, LayoutParams((300 * d).toInt(), (180 * d).toInt()).apply { leftMargin = -cl; topMargin = -ct })
        card.addView(tint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.addView(sheen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        time.apply { text = "13:17"; textSize = 34f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        card.addView(time, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams((200 * d).toInt(), (100 * d).toInt()).apply { leftMargin = cl; topMargin = ct })
        set(1, 0)
    }

    /** blur 0..2, style 0 gelap / 1 terang / 2 otomatis, dim dan sheen 0..100, radius 0..2 (sama dengan GlassPrefs). */
    fun set(blur: Int, style: Int, dim: Int = 50, sheenPct: Int = 50, radius: Int = 1) {
        val r = when (blur) { 0 -> 6f; 1 -> 14f; else -> 26f } * d
        if (Build.VERSION.SDK_INT >= 31) blurred.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        val light = style == 1
        // pratinjau dibuat sedikit lebih pekat dari aslinya supaya perubahan kepekatan terlihat jelas di latar berwarna
        tint.setBackgroundColor(if (light) Color.argb((30 + dim * 1.2f).toInt().coerceIn(0, 255), 255, 255, 255) else Color.argb((20 + dim * 0.9f).toInt().coerceIn(0, 255), 0, 0, 0))
        time.setTextColor(if (light) 0xFF101426.toInt() else Color.WHITE)
        sheen.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.argb((sheenPct * 1.1f).toInt().coerceIn(0, 140), 255, 255, 255), 0x00FFFFFF)).apply { cornerRadius = 0f }
        cardR = when (radius) { 0 -> 20f; 2 -> 38f; else -> 28f }
        card.invalidateOutline()
        card.foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke((1 * d).toInt().coerceAtLeast(1), 0x66FFFFFF); cornerRadius = cardR * d }
    }
}
