package app.timeflow

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class MediaInfo(val title: String, val artist: String, val art: Bitmap?, val playing: Boolean,
                     val pos: Long, val dur: Long, val speed: Float, val stamp: Long)

data class NotifInfo(val app: String, val title: String, val text: String, val icon: Drawable?, val color: Int, val open: (() -> Unit)?)

interface NotchHost {
    fun resize(w: Int, h: Int)          // ubah ukuran jendela overlay (px)
    fun cfg(): NotchPrefs.Cfg
    fun visible(show: Boolean)          // pasang/lepas jendela (saat diam & disembunyikan, lepas supaya sentuhan tidak terhalang)
    fun transport(cmd: Int)             // 0 = sebelumnya, 1 = putar/jeda, 2 = berikutnya
    fun seek(frac: Float)
    fun openMedia()
}

/** Pil hitam bergaya Dynamic Island: diam -> musik (ringkas) -> notifikasi (banner) -> kontrol musik (penuh). */
class NotchView(ctx: Context, private val host: NotchHost) : FrameLayout(ctx) {
    private enum class Mode { IDLE, MEDIA, BANNER, FULL }

    private val d = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private var mode = Mode.IDLE
    private var media: MediaInfo? = null
    private var notif: NotifInfo? = null
    private var expanded = false
    private var curW = 0f; private var curH = 0f; private var curR = 0f
    private var anim: ValueAnimator? = null
    private var radius = 0f
    private val accent = Color.rgb(255, 55, 95)

    // ---- isi ----
    private val artC = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
    private val eqC = Eq(ctx, accent)
    private val mediaBox = FrameLayout(ctx)

    private val bIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; clipToOutline = true }
    private val bApp = tv(12f, Color.argb(170, 255, 255, 255), false)
    private val bTitle = tv(15f, Color.WHITE, true)
    private val bText = tv(13f, Color.argb(200, 255, 255, 255), false).apply { maxLines = 2 }
    private val bannerBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private val fArt = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
    private val fTitle = tv(16f, Color.WHITE, true)
    private val fArtist = tv(14f, Color.argb(170, 255, 255, 255), false)
    private val fEq = Eq(ctx, accent)
    private val fBar = Bar(ctx)
    private val fCur = tv(11f, Color.argb(150, 255, 255, 255), false)
    private val fLeft = tv(11f, Color.argb(150, 255, 255, 255), false)
    private val gPrev = Glyph(ctx, 0); private val gPlay = Glyph(ctx, 1); private val gNext = Glyph(ctx, 3)
    private val fullBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val tick = object : Runnable {
        override fun run() {
            if (mode == Mode.FULL) { refreshProgress(); ui.postDelayed(this, 500) }
        }
    }
    private val autoClose = Runnable { dismiss() }

    private fun dp(v: Float) = (v * d).toInt()
    private fun tv(sp: Float, color: Int, bold: Boolean) = TextView(ctx()).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END; if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun ctx(): Context = context
    private fun rounded(v: View, r: Float) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, r) }
        }
        v.clipToOutline = true
    }

    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(); return true }
        override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
            if (vy < -600f && abs(vy) > abs(vx)) { dismiss(); return true }
            return false
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        // media ringkas: sampul kiri, gelombang kanan
        mediaBox.addView(artC, LayoutParams(dp(24f), dp(24f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(10f) })
        mediaBox.addView(eqC, LayoutParams(dp(22f), dp(16f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(14f) })
        rounded(artC, 6f * d)
        // banner notifikasi
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(bApp); col.addView(bTitle); col.addView(bText)
        bannerBox.addView(bIcon, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { leftMargin = dp(16f); rightMargin = dp(12f) })
        bannerBox.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(16f) })
        rounded(bIcon, 11f * d)
        // media penuh
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tx = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(fTitle); tx.addView(fArtist)
        top.addView(fArt, LinearLayout.LayoutParams(dp(64f), dp(64f)))
        top.addView(tx, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12f); rightMargin = dp(8f) })
        top.addView(fEq, LinearLayout.LayoutParams(dp(24f), dp(18f)))
        rounded(fArt, 12f * d)
        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(fCur, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(fLeft)
        val ctl = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (g in listOf(gPrev, gPlay, gNext)) ctl.addView(g, LinearLayout.LayoutParams(dp(64f), dp(44f)))
        fullBox.setPadding(dp(18f), dp(16f), dp(18f), dp(10f))
        fullBox.addView(top)
        fullBox.addView(fBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18f)).apply { topMargin = dp(10f) })
        fullBox.addView(times)
        fullBox.addView(ctl, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        gPrev.setOnClickListener { host.transport(0); poke() }
        gPlay.setOnClickListener { host.transport(1); poke() }
        gNext.setOnClickListener { host.transport(2); poke() }
        fArt.setOnClickListener { host.openMedia(); collapse() }
        fBar.onSeek = { f -> host.seek(f); poke() }
        for (b in listOf(mediaBox, bannerBox, fullBox)) { b.visibility = View.GONE; addView(b) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = gd.onTouchEvent(e) || super.onTouchEvent(e)

    // ---- API dari listener ----
    fun setMedia(m: MediaInfo?) {
        media = m
        if (m == null) expanded = false
        if (m != null) {
            artC.setImageBitmap(m.art); fArt.setImageBitmap(m.art)
            fTitle.text = m.title.ifEmpty { "Tanpa judul" }; fArtist.text = m.artist
            eqC.setPlaying(m.playing); fEq.setPlaying(m.playing)
            gPlay.kind = if (m.playing) 2 else 1; gPlay.invalidate()
            if (!m.playing) ui.postDelayed({ if (media?.playing == false && !expanded) evaluate(false) }, 10_000)
            refreshProgress()
        }
        evaluate(true)
    }

    fun showNotif(n: NotifInfo) {
        notif = n
        bIcon.setImageDrawable(n.icon)
        bIcon.setBackgroundColor(if (n.color != 0) n.color or (0xFF shl 24) else Color.rgb(60, 60, 67))
        bApp.text = n.app; bTitle.text = n.title; bText.text = n.text
        bText.visibility = if (n.text.isEmpty()) View.GONE else View.VISIBLE
        ui.removeCallbacks(autoClose); ui.postDelayed(autoClose, host.cfg().autoHide * 1000L)
        evaluate(true)
    }

    fun applyConfig() { evaluate(false, force = true) }

    fun release() { ui.removeCallbacksAndMessages(null); anim?.cancel(); eqC.setPlaying(false); fEq.setPlaying(false) }

    // ---- logika ----
    private fun tap() {
        when (mode) {
            Mode.BANNER -> { val o = notif?.open; dismiss(); runCatching { o?.invoke() } }
            Mode.MEDIA -> { expanded = true; ui.removeCallbacks(autoClose); ui.postDelayed(autoClose, 9000); evaluate(true) }
            Mode.FULL -> collapse()
            else -> {}
        }
    }
    private fun poke() { ui.removeCallbacks(autoClose); ui.postDelayed(autoClose, 9000) }
    private fun collapse() { expanded = false; ui.removeCallbacks(autoClose); evaluate(true) }
    private fun dismiss() { ui.removeCallbacks(autoClose); notif = null; expanded = false; evaluate(true) }

    private fun decide(): Mode {
        val c = host.cfg(); val m = media
        return when {
            notif != null -> Mode.BANNER
            expanded && m != null -> Mode.FULL
            c.showMedia && m != null && m.playing -> Mode.MEDIA
            else -> Mode.IDLE
        }
    }

    private fun evaluate(animate: Boolean, force: Boolean = false) {
        val next = decide(); val c = host.cfg()
        if (next == Mode.IDLE && !c.idleVisible) { mode = next; showContent(); host.visible(false); return }
        host.visible(true)
        if (next == mode && !force) { if (next == Mode.FULL || next == Mode.MEDIA) showContent(); return }
        val prev = mode; mode = next
        showContent()
        animateTo(prev, animate && curW > 0f)
        if (next == Mode.FULL) { ui.removeCallbacks(tick); ui.post(tick) }
    }

    private fun showContent() {
        mediaBox.visibility = if (mode == Mode.MEDIA) View.VISIBLE else View.GONE
        bannerBox.visibility = if (mode == Mode.BANNER) View.VISIBLE else View.GONE
        fullBox.visibility = if (mode == Mode.FULL) View.VISIBLE else View.GONE
    }

    private fun target(): Triple<Float, Float, Float> {
        val c = host.cfg(); val s = c.scale / 100f
        val sw = resources.displayMetrics.widthPixels.toFloat(); val maxW = sw - dp(16f)
        return when (mode) {
            Mode.IDLE -> Triple(c.width * d, c.height * d, c.height * d / 2f)
            Mode.MEDIA -> Triple(min(maxW, (c.width + 104f * s) * d), c.height * d, c.height * d / 2f)
            Mode.BANNER -> Triple(min(maxW, 350f * s * d), 76f * s * d, 38f * s * d)
            Mode.FULL -> Triple(min(maxW, 370f * s * d), 178f * s * d, 40f * s * d)
        }
    }

    private fun animateTo(prev: Mode, animate: Boolean) {
        val (tw, th, tr) = target()
        anim?.cancel()
        val sw = if (curW > 0f) curW else tw; val sh = if (curH > 0f) curH else th; val sr = if (curR > 0f) curR else tr
        // isi diberi ukuran tetap sesuai target agar teks tidak "bergetar" saat animasi
        for (b in listOf(mediaBox, bannerBox, fullBox)) b.layoutParams = LayoutParams(tw.toInt(), th.toInt(), Gravity.CENTER)
        if (!animate) { apply(tw, th, tr); return }
        val grow = tw * th >= sw * sh
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (grow) 420 else 300
            interpolator = if (grow) OvershootInterpolator(0.9f) else DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                apply(sw + (tw - sw) * t, sh + (th - sh) * t, sr + (tr - sr) * t)
                val a = ((it.animatedFraction - 0.3f) / 0.7f).coerceIn(0f, 1f)
                for (b in listOf(mediaBox, bannerBox, fullBox)) b.alpha = a
            }
            start()
        }
        for (b in listOf(mediaBox, bannerBox, fullBox)) b.alpha = 0f
    }

    private fun apply(w: Float, h: Float, r: Float) {
        curW = w; curH = h; curR = r; radius = r
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, radius) }
        }
        clipToOutline = true
        invalidateOutline()
        host.resize(max(1, w.toInt()), max(1, h.toInt()))
    }

    private fun refreshProgress() {
        val m = media ?: return
        val now = if (m.playing) m.pos + ((SystemClock.elapsedRealtime() - m.stamp) * m.speed).toLong() else m.pos
        val p = if (m.dur > 0) now.coerceIn(0, m.dur) else 0L
        fBar.frac = if (m.dur > 0) p.toFloat() / m.dur else 0f; fBar.invalidate()
        fCur.text = fmt(p); fLeft.text = if (m.dur > 0) "-" + fmt(m.dur - p) else ""
    }
    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); ui.removeCallbacks(tick); eqC.setPlaying(false); fEq.setPlaying(false) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); media?.let { eqC.setPlaying(it.playing); fEq.setPlaying(it.playing) } }

    // ---- gambar kecil ----
    class Eq(ctx: Context, tint: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        private var phase = 0f; private var a: ValueAnimator? = null
        fun setPlaying(on: Boolean) {
            if (on) {
                if (a == null) a = ValueAnimator.ofFloat(0f, 6.2832f).apply {
                    duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                    addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start()
                }
            } else { a?.cancel(); a = null; phase = 0f; invalidate() }
        }
        override fun onDraw(c: Canvas) {
            val n = 5; val gap = width / (n * 2f); val bw = gap
            for (i in 0 until n) {
                val f = if (a == null) 0.25f else 0.3f + 0.7f * abs(sin(phase + i * 1.3f))
                val h = height * f; val x = i * gap * 2f + gap / 2f
                c.drawRoundRect(x, (height - h) / 2f, x + bw, (height + h) / 2f, bw / 2f, bw / 2f, p)
            }
        }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); a?.cancel(); a = null }
    }

    class Bar(ctx: Context) : View(ctx) {
        var frac = 0f; var onSeek: ((Float) -> Unit)? = null
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
        private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun onDraw(c: Canvas) {
            val h = 4f * resources.displayMetrics.density; val y = (height - h) / 2f
            c.drawRoundRect(0f, y, width.toFloat(), y + h, h / 2, h / 2, t)
            c.drawRoundRect(0f, y, width * frac, y + h, h / 2, h / 2, f)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP && width > 0) { onSeek?.invoke((e.x / width).coerceIn(0f, 1f)); return true }
            return e.action == MotionEvent.ACTION_DOWN
        }
    }

    class Glyph(ctx: Context, var kind: Int) : View(ctx) {   // 0 sebelumnya, 1 putar, 2 jeda, 3 berikutnya
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private fun tri(c: Canvas, x: Float, cy: Float, u: Float, right: Boolean) {
            val s = if (right) 1f else -1f
            val path = Path().apply { moveTo(x - s * u * 0.8f, cy - u); lineTo(x + s * u * 0.8f, cy); lineTo(x - s * u * 0.8f, cy + u); close() }
            c.drawPath(path, p)
        }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val u = min(width, height) * 0.30f
            when (kind) {
                1 -> tri(c, cx, cy, u * 1.15f, true)
                2 -> { c.drawRoundRect(cx - u * 0.9f, cy - u, cx - u * 0.25f, cy + u, 4f, 4f, p); c.drawRoundRect(cx + u * 0.25f, cy - u, cx + u * 0.9f, cy + u, 4f, 4f, p) }
                0 -> { tri(c, cx - u * 0.7f, cy, u, false); tri(c, cx + u * 0.5f, cy, u, false) }
                else -> { tri(c, cx - u * 0.5f, cy, u, true); tri(c, cx + u * 0.7f, cy, u, true) }
            }
        }
    }
}
