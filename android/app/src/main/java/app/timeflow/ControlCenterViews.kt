package app.timeflow

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Warna dasar gaya iOS dipakai bersama oleh semua kontrol. */
object CcColor {
    val glass = Color.argb(120, 66, 66, 74)
    val glassEdge = Color.argb(34, 255, 255, 255)
    val off = Color.argb(150, 98, 98, 106)
    val offCircle = Color.argb(205, 52, 52, 58)
    val dark = Color.rgb(28, 28, 30)
    val orange = Color.rgb(255, 159, 10)
    val green = Color.rgb(48, 209, 88)
    val blue = Color.rgb(10, 132, 255)
    val purple = Color.rgb(94, 92, 230)
    val yellow = Color.rgb(255, 204, 0)
}

/** Ikon garis/isian digambar dengan Canvas dalam koordinat satuan (-1..1), jadi tidak butuh file gambar. */
class CcArt {
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val r = RectF()
    private val moon: Path by lazy {
        val a = Path().apply { addCircle(0f, 0f, 0.95f, Path.Direction.CW) }
        val b = Path().apply { addCircle(0.52f, -0.42f, 0.78f, Path.Direction.CW) }
        a.op(b, Path.Op.DIFFERENCE)
        a
    }
    private val pin: Path by lazy {
        Path().apply {
            fillType = Path.FillType.EVEN_ODD
            moveTo(0f, 1f)
            cubicTo(-0.25f, 0.55f, -0.8f, 0.15f, -0.8f, -0.3f)
            cubicTo(-0.8f, -0.75f, -0.45f, -1f, 0f, -1f)
            cubicTo(0.45f, -1f, 0.8f, -0.75f, 0.8f, -0.3f)
            cubicTo(0.8f, 0.15f, 0.25f, 0.55f, 0f, 1f)
            close()
            addCircle(0f, -0.3f, 0.3f, Path.Direction.CW)
        }
    }

    private fun poly(c: Canvas, vararg p: Float) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close()
        c.drawPath(path, f)
    }

    private fun line(c: Canvas, vararg p: Float) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        c.drawPath(path, s)
    }

    /** u = setengah ukuran ikon (px). flag = varian: speaker senyap. */
    fun draw(c: Canvas, kind: String, cx: Float, cy: Float, u: Float, color: Int, flag: Boolean = false) {
        f.color = color; s.color = color; s.strokeWidth = 0.17f
        c.save()
        c.translate(cx, cy)
        c.scale(u, u)
        when (kind) {
            "plane" -> {
                c.rotate(45f); c.scale(0.82f, 0.82f)
                poly(c, 0f, -1f, 0.13f, -0.78f, 0.15f, -0.22f, 0.95f, 0.28f, 0.95f, 0.5f, 0.15f, 0.3f, 0.11f, 0.72f,
                    0.36f, 0.9f, 0.36f, 1f, 0f, 0.9f, -0.36f, 1f, -0.36f, 0.9f, -0.11f, 0.72f, -0.15f, 0.3f,
                    -0.95f, 0.5f, -0.95f, 0.28f, -0.15f, -0.22f, -0.13f, -0.78f)
            }
            "cell" -> {
                c.drawCircle(0f, -0.15f, 0.15f, f)
                line(c, 0f, 0f, 0f, 0.95f)
                r.set(-0.5f, -0.65f, 0.5f, 0.35f)
                c.drawArc(r, -40f, 80f, false, s); c.drawArc(r, 140f, 80f, false, s)
                r.set(-0.95f, -1.1f, 0.95f, 0.8f)
                c.drawArc(r, -40f, 80f, false, s); c.drawArc(r, 140f, 80f, false, s)
            }
            "wifi" -> {
                c.drawCircle(0f, 0.62f, 0.13f, f)
                for (rad in floatArrayOf(0.38f, 0.75f, 1.12f)) {
                    r.set(-rad, 0.62f - rad, rad, 0.62f + rad)
                    c.drawArc(r, 225f, 90f, false, s)
                }
            }
            "bt" -> {
                c.scale(0.82f, 0.82f)
                line(c, -0.5f, -0.5f, 0.5f, 0.5f, 0f, 1f, 0f, -1f, 0.5f, -0.5f, -0.5f, 0.5f)
            }
            "rotate" -> {
                r.set(-0.8f, -0.8f, 0.8f, 0.8f)
                c.drawArc(r, -70f, 280f, false, s)
                // kepala panah di ujung busur (sudut 210 derajat, arah putar searah jarum jam)
                val a = Math.toRadians(210.0)
                val px = (0.8 * cos(a)).toFloat(); val py = (0.8 * sin(a)).toFloat()
                val dx = (-sin(a)).toFloat(); val dy = cos(a).toFloat()
                val nx = -dy; val ny = dx
                path.reset()
                path.moveTo(px + dx * 0.38f, py + dy * 0.38f)
                path.lineTo(px + nx * 0.3f, py + ny * 0.3f)
                path.lineTo(px - nx * 0.3f, py - ny * 0.3f)
                path.close()
                c.drawPath(path, f)
                r.set(-0.3f, 0.02f, 0.3f, 0.52f)
                c.drawRoundRect(r, 0.08f, 0.08f, f)
                s.strokeWidth = 0.1f
                r.set(-0.2f, -0.36f, 0.2f, 0.18f)
                c.drawArc(r, 180f, 180f, false, s)
            }
            "cast" -> {
                c.scale(0.9f, 0.9f)
                r.set(-1f, -0.75f, 0.45f, 0.25f); c.drawRoundRect(r, 0.14f, 0.14f, s)
                r.set(-0.3f, -0.15f, 1f, 0.8f); c.drawRoundRect(r, 0.14f, 0.14f, s)
            }
            "moon" -> c.drawPath(moon, f)
            "sun" -> {
                c.drawCircle(0f, 0f, 0.38f, f)
                s.strokeWidth = 0.15f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                    c.drawLine(ca * 0.66f, sa * 0.66f, ca * 0.98f, sa * 0.98f, s)
                }
            }
            "speaker" -> {
                c.scale(0.85f, 0.85f)
                poly(c, -0.95f, -0.3f, -0.5f, -0.3f, 0f, -0.8f, 0f, 0.8f, -0.5f, 0.3f, -0.95f, 0.3f)
                if (flag) {
                    line(c, 0.35f, -0.3f, 0.95f, 0.3f); line(c, 0.35f, 0.3f, 0.95f, -0.3f)
                } else {
                    r.set(-0.45f, -0.5f, 0.55f, 0.5f); c.drawArc(r, -45f, 90f, false, s)
                    r.set(-0.85f, -0.9f, 0.95f, 0.9f); c.drawArc(r, -45f, 90f, false, s)
                }
            }
            "torch" -> {
                c.scale(0.92f, 0.92f)
                poly(c, -0.5f, -1f, 0.5f, -1f, 0.3f, -0.4f, -0.3f, -0.4f)
                r.set(-0.3f, -0.3f, 0.3f, 1f); c.drawRoundRect(r, 0.12f, 0.12f, f)
            }
            "camera" -> {
                c.scale(0.88f, 0.88f)
                r.set(-1f, -0.5f, 1f, 0.8f); c.drawRoundRect(r, 0.22f, 0.22f, s)
                line(c, -0.38f, -0.5f, -0.22f, -0.86f, 0.22f, -0.86f, 0.38f, -0.5f)
                c.drawCircle(0f, 0.15f, 0.38f, s)
            }
            "calc" -> {
                r.set(-0.7f, -0.95f, 0.7f, 0.95f); c.drawRoundRect(r, 0.22f, 0.22f, s)
                r.set(-0.42f, -0.7f, 0.42f, -0.3f); c.drawRoundRect(r, 0.08f, 0.08f, f)
                for (row in 0..2) for (col in 0..1) c.drawCircle(-0.25f + col * 0.5f, 0.06f + row * 0.36f, 0.1f, f)
            }
            "timer" -> {
                c.drawCircle(0f, 0.14f, 0.8f, s)
                line(c, 0f, -0.66f, 0f, -0.95f)
                line(c, -0.26f, -0.95f, 0.26f, -0.95f)
                line(c, 0f, 0.14f, 0.36f, -0.24f)
            }
            "hotspot" -> {
                c.drawCircle(0f, 0f, 0.17f, f)
                for (rad in floatArrayOf(0.56f, 0.98f)) {
                    r.set(-rad, -rad, rad, rad)
                    c.drawArc(r, 135f, 270f, false, s)
                }
            }
            "pin" -> c.drawPath(pin, f)
            "battery" -> {
                r.set(-0.95f, -0.5f, 0.7f, 0.5f); c.drawRoundRect(r, 0.16f, 0.16f, s)
                r.set(0.8f, -0.18f, 0.98f, 0.18f); c.drawRoundRect(r, 0.05f, 0.05f, f)
                s.strokeWidth = 0.12f
                line(c, -0.42f, 0f, 0.16f, 0f); line(c, -0.13f, -0.29f, -0.13f, 0.29f)
            }
            "display" -> {
                c.drawCircle(0f, 0f, 0.85f, s)
                r.set(-0.85f, -0.85f, 0.85f, 0.85f)
                c.drawArc(r, -90f, 180f, true, f)
            }
            "note" -> {
                c.drawCircle(-0.38f, 0.66f, 0.32f, f)
                s.strokeWidth = 0.15f
                line(c, -0.08f, 0.66f, -0.08f, -0.82f, 0.72f, -0.5f)
            }
        }
        c.restore()
    }
}

/** Ikon tunggal yang digambar di tengah (dipakai untuk keadaan kosong kartu musik). */
class CcIconView(ctx: Context, private val art: CcArt, private val kind: String, private val color: Int) : View(ctx) {
    override fun onDraw(c: Canvas) { art.draw(c, kind, width / 2f, height / 2f, min(width, height) * 0.42f, color) }
}

/**
 * Satu kontrol. style 0 = tombol bulat (ikon saja), 1 = kartu persegi (ikon + label), 2 = kartu lebar (ikon di kiri, label di kanan).
 * Aktif = berwarna (onBg), tidak aktif = kaca gelap.
 */
class CcTile(ctx: Context, private val art: CcArt, val kind: String, private val label: String, private val style: Int) : View(ctx) {
    var onBg = Color.WHITE
    var onFg = CcColor.dark
    private var on = false
    private var flag = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = CcColor.glassEdge }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rect = RectF()

    init { isClickable = true }

    fun state(on: Boolean, flag: Boolean = false) {
        if (on != this.on || flag != this.flag) { this.on = on; this.flag = flag; invalidate() }
    }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun fit(maxW: Float, startSp: Float) {
        var size = startSp
        txt.textSize = sp(size)
        while (txt.measureText(label) > maxW && size > 8f) { size -= 0.5f; txt.textSize = sp(size) }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.save()
        if (isPressed) c.scale(0.95f, 0.95f, w / 2f, h / 2f)
        rect.set(0f, 0f, w, h)
        val rad = if (style == 0) min(w, h) / 2f else min(w, h) * 0.3f
        bg.color = if (on) onBg else if (style == 0) CcColor.offCircle else CcColor.off
        c.drawRoundRect(rect, rad, rad, bg)
        if (!on) c.drawRoundRect(rect, rad, rad, edge)
        val fg = if (on) onFg else Color.WHITE
        txt.color = fg
        when (style) {
            0 -> art.draw(c, kind, w / 2f, h / 2f, min(w, h) * 0.26f, fg, flag)
            1 -> {
                art.draw(c, kind, w / 2f, h * 0.4f, min(w, h) * 0.2f, fg, flag)
                fit(w * 0.9f, 11f); txt.textAlign = Paint.Align.CENTER
                c.drawText(label, w / 2f, h * 0.84f, txt)
            }
            else -> {
                art.draw(c, kind, h / 2f, h / 2f, h * 0.2f, fg, flag)
                val x = h * 0.92f
                fit(w - x - h * 0.2f, 13f); txt.textAlign = Paint.Align.LEFT
                c.drawText(label, x, h / 2f - (txt.ascent() + txt.descent()) / 2f, txt)
            }
        }
        c.restore()
    }
}

/** Slider vertikal gaya iOS (kecerahan, volume): isi putih naik dari bawah, ikon di bawah berubah warna di atas isian. */
class CcSlider(ctx: Context, private val art: CcArt, private val kind: String) : View(ctx) {
    var onChange: ((Float) -> Unit)? = null
    var canAdjust: () -> Boolean = { true }
    var onDenied: () -> Unit = {}
    var frac = 0f
        private set
    var dragging = false
        private set
    private var flag = false
    private var denied = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CcColor.off }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 255, 255, 255) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = CcColor.glassEdge }
    private val clip = Path()
    private val rect = RectF()

    fun setValue(v: Float, flag: Boolean = false) {
        if (dragging) return
        val n = v.coerceIn(0f, 1f)
        if (abs(n - frac) > 0.002f || flag != this.flag) { frac = n; this.flag = flag; invalidate() }
    }

    private fun move(y: Float) {
        if (height <= 0) return
        frac = (1f - y / height).coerceIn(0f, 1f)
        invalidate()
        onChange?.invoke(frac)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (!canAdjust()) { denied = true } else { denied = false; dragging = true; move(e.y) }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) move(e.y)
            MotionEvent.ACTION_UP -> { if (denied) onDenied(); dragging = false; denied = false }
            MotionEvent.ACTION_CANCEL -> { dragging = false; denied = false }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = w * 0.3f
        rect.set(0f, 0f, w, h)
        clip.reset(); clip.addRoundRect(rect, rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        c.drawRect(rect, bg)
        val top = h * (1f - frac)
        c.drawRect(0f, top, w, h, fill)
        c.restore()
        c.drawRoundRect(rect, rad, rad, edge)
        val iy = h - w * 0.52f
        val ur = w * 0.21f
        c.save(); c.clipRect(0f, top, w, h)
        art.draw(c, kind, w / 2f, iy, ur, CcColor.dark, flag)
        c.restore()
        c.save(); c.clipRect(0f, 0f, w, top)
        art.draw(c, kind, w / 2f, iy, ur, Color.WHITE, flag)
        c.restore()
    }
}

/** Kartu musik: sampul + judul + tombol sebelumnya / putar / berikutnya. Kosong bila tidak ada sesi media. */
class CcMedia(ctx: Context, art: CcArt, private val host: CcHost, private val buzz: (View) -> Unit, private val onOpen: () -> Unit) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val cover = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.argb(90, 255, 255, 255))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, 10f * d) }
        }
        clipToOutline = true
    }
    private val title = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(Color.argb(190, 255, 255, 255))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val prev = NotchView.Glyph(ctx, 0)
    private val play = NotchView.Glyph(ctx, 1)
    private val next = NotchView.Glyph(ctx, 3)
    private val full = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val empty = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }

    init {
        background = GradientDrawable().apply {
            setColor(CcColor.glass); cornerRadius = 26f * d; setStroke(1, CcColor.glassEdge)
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(title); tx.addView(artist)
        head.addView(cover, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        head.addView(tx, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
        head.setOnClickListener { onOpen() }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (g in listOf(prev, play, next)) row.addView(g, LinearLayout.LayoutParams(0, dp(42f), 1f))
        prev.setOnClickListener { buzz(it); host.transport(0) }
        play.setOnClickListener { buzz(it); host.transport(1) }
        next.setOnClickListener { buzz(it); host.transport(2) }
        full.setPadding(dp(12f), dp(12f), dp(12f), dp(8f))
        full.addView(head)
        full.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        full.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val note = CcIconView(ctx, art, "note", Color.argb(150, 255, 255, 255))
        val msg = TextView(ctx).apply {
            text = "Tidak ada musik"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.argb(170, 255, 255, 255)); gravity = Gravity.CENTER
        }
        empty.addView(note, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        empty.addView(msg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
        addView(full, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setMedia(null)
    }

    fun setMedia(m: MediaInfo?) {
        full.visibility = if (m != null) View.VISIBLE else View.GONE
        empty.visibility = if (m != null) View.GONE else View.VISIBLE
        if (m == null) return
        title.text = m.title.ifEmpty { "Musik" }
        artist.text = m.artist
        if (m.art != null) cover.setImageBitmap(m.art) else cover.setImageDrawable(null)
        play.kind = if (m.playing) 2 else 1
        play.invalidate()
    }
}

/** Jendela transparan tipis di kanan atas layar: geser ke bawah dari sini membuka Control Center. */
class CcTrigger(ctx: Context, private val onSwipe: () -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var x0 = 0f
    private var y0 = 0f
    private var fired = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; fired = false; CcLog.add("pemicu: sentuh y=${e.rawY.toInt()}") }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dy = e.rawY - y0; val dx = abs(e.rawX - x0)
                if (dy > 18f * d && dy > dx * 0.8f) { fired = true; CcLog.add("pemicu: geser terdeteksi"); onSwipe() }
            }
            MotionEvent.ACTION_CANCEL -> CcLog.add("pemicu: sentuhan dibatalkan sistem")
        }
        return true
    }
}

/** Pegangan kecil di tepi kanan layar: ketuk atau geser ke bawah untuk membuka Control Center (cadangan bila geser dari bar status diblokir sistem). */
class CcHandle(ctx: Context, private val onOpen: () -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(185, 255, 255, 255) }
    private val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val r = RectF()
    private var x0 = 0f
    private var y0 = 0f
    private var fired = false

    override fun onDraw(c: Canvas) {
        val bw = 5f * d
        val bh = min(height.toFloat(), 52f * d)
        val x = width - bw - 3f * d
        val y = (height - bh) / 2f
        r.set(x - 1.5f * d, y - 1.5f * d, x + bw + 1.5f * d, y + bh + 1.5f * d)
        c.drawRoundRect(r, bw, bw, sh)
        r.set(x, y, x + bw, y + bh)
        c.drawRoundRect(r, bw / 2f, bw / 2f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; fired = false }
            MotionEvent.ACTION_MOVE -> if (!fired && e.rawY - y0 > 14f * d) { fired = true; onOpen() }
            MotionEvent.ACTION_UP -> if (!fired && abs(e.rawX - x0) < 12f * d && abs(e.rawY - y0) < 12f * d) { fired = true; onOpen() }
        }
        return true
    }
}
