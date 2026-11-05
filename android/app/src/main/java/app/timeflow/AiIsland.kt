package app.timeflow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/**
 * Dynamic Island untuk Asisten AI dan info singkat. Jendela mandiri di posisi notch yang sama (mengikuti Pengaturan notch:
 * posisi, ukuran, skala): pil hitam melebar dari ukuran notch, dengan cincin cahaya berputar, orb, gelombang suara, dan teks status.
 *  AI: 1 = mendengarkan, 2 = berpikir, 3 = berbicara, 4 = bekerja di layar. Info: mengisi daya, baterai lemah, senyap/getar/dering, jangan ganggu.
 */
object AiIsland {
    private const val NONE = 0
    private const val AI = 1
    private const val INFO = 2

    private val ui = Handler(Looper.getMainLooper())
    private var view: IslandView? = null
    private var wm: WindowManager? = null
    private var wlp: WindowManager.LayoutParams? = null
    private var appRef: Context? = null
    private var aiOn = false
    private var aiState = 1
    private var aiText = ""
    private var capIcon = ""
    private var capText = ""
    private var capUntil = 0L
    private var eventsOn = false
    private val capTick = Runnable { appRef?.let { refresh(it) } }

    /** state 0 = AI mati; 1..4 lihat atas. Aman dipanggil dari thread mana pun. */
    fun state(ctx: Context, state: Int, text: String) {
        val app = ctx.applicationContext
        ui.post {
            appRef = app
            aiOn = state != 0
            if (aiOn) { aiState = state; aiText = text }
            refresh(app)
        }
    }

    /** Volume mikrofon 0..1 untuk gelombang. */
    fun level(v: Float) { view?.level = v }

    /** Kapsul info singkat (hanya bila notch dinyalakan di Pengaturan notch). */
    fun info(ctx: Context, icon: String, text: String, ms: Long) {
        val app = ctx.applicationContext
        ui.post {
            if (!NotchPrefs.get(app).enabled) return@post
            appRef = app
            capIcon = icon
            capText = text
            capUntil = SystemClock.uptimeMillis() + ms
            ui.removeCallbacks(capTick)
            ui.postDelayed(capTick, ms)
            refresh(app)
        }
    }

    private fun refresh(app: Context) {
        val want = if (aiOn) AI else if (SystemClock.uptimeMillis() < capUntil) INFO else NONE
        if (want == NONE) {
            val v = view ?: return
            v.shrinkAndHide { runCatching { wm?.removeViewImmediate(v) }; if (view === v) view = null }
            return
        }
        val v = ensure(app) ?: return
        val cfg = NotchPrefs.get(app)
        val dm = app.resources.displayMetrics
        val d = dm.density
        val s = cfg.scale / 100f
        v.idleW = cfg.width * s * d
        v.idleH = cfg.height * s * d
        v.mode = want
        v.aiState = aiState
        v.icon = capIcon
        v.text = if (want == AI) (if (aiText.isEmpty()) (if (aiState == 1) "Mendengarkan…" else "AI") else aiText) else capText
        val tw: Float
        val th: Float
        if (want == AI) {
            tw = min(270f * s * d, dm.widthPixels - 16f * d)
            th = max(54f * s * d, v.idleH)
        } else {
            th = max(38f * s * d, v.idleH)
            tw = min(dm.widthPixels - 16f * d, max(v.idleW + 40f * d, v.contentWidth() + 56f * d))
        }
        wlp?.let { lp ->
            val nx = (cfg.x * d).toInt()
            val ny = (cfg.top * d).toInt()
            if (lp.x != nx || lp.y != ny) { lp.x = nx; lp.y = ny; runCatching { wm?.updateViewLayout(v, lp) } }
        }
        v.animateTo(tw, th)
    }

    private fun ensure(app: Context): IslandView? {
        view?.let { return it }
        val m: WindowManager
        val type: Int
        val ai = AiAccessibilityService.instance()
        val ccWm = CcAccessibilityService.winMgr()
        if (ai != null) {
            m = ai.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (ccWm != null) {
            m = ccWm
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(app)) {
            m = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else return null
        val cfg = NotchPrefs.get(app)
        val dm = app.resources.displayMetrics
        val d = dm.density
        val s = cfg.scale / 100f
        val lp = WindowManager.LayoutParams(
            dm.widthPixels, (60f * s * d).toInt() + (16f * d).toInt(), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.x = (cfg.x * d).toInt()
        lp.y = (cfg.top * d).toInt()
        val v = IslandView(app)
        try { m.addView(v, lp) } catch (e: Exception) { return null }
        wm = m
        wlp = lp
        view = v
        return v
    }

    // ---------- kapsul info dari kejadian sistem ----------
    private fun batteryPct(app: Context): Int {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (lv < 0 || sc <= 0) -1 else lv * 100 / sc
    }

    /** Dipanggil sekali dari TimeFlowApp: mendengarkan kejadian sistem selama proses hidup. */
    fun registerEvents(app: Application) {
        if (eventsOn) return
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pct = batteryPct(app)
                val p = if (pct >= 0) " $pct%" else ""
                when (i.action) {
                    Intent.ACTION_POWER_CONNECTED -> info(app, "⚡", "Mengisi daya$p", 2400L)
                    Intent.ACTION_BATTERY_LOW -> info(app, "🔋", "Baterai lemah$p", 3600L)
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                        when (am.ringerMode) {
                            AudioManager.RINGER_MODE_SILENT -> info(app, "🔕", "Senyap", 2000L)
                            AudioManager.RINGER_MODE_VIBRATE -> info(app, "📳", "Getar", 2000L)
                            else -> info(app, "🔔", "Dering", 2000L)
                        }
                    }
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                        val nm = app.getSystemService(NotificationManager::class.java) ?: return
                        val f = nm.currentInterruptionFilter
                        val on = f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                        info(app, "🌙", if (on) "Jangan ganggu: Nyala" else "Jangan ganggu: Mati", 2200L)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        eventsOn = runCatching { ContextCompat.registerReceiver(app, rx, f, ContextCompat.RECEIVER_NOT_EXPORTED) }.isSuccess
    }

    // ---------- tampilan ----------
    private class IslandView(ctx: Context) : View(ctx) {
        var mode = AI
        var aiState = 1
        var text = ""
        var icon = ""
        var idleW = 0f
        var idleH = 0f
        @Volatile var level = 0f

        private val d = ctx.resources.displayMetrics.density
        private var curW = 0f
        private var curH = 0f
        private var tgtW = 0f
        private var a = 0f
        private var angle = 0f
        private var anim: ValueAnimator? = null
        private var spin: ValueAnimator? = null
        private val pill = RectF()
        private val ring = RectF()
        private val arc = RectF()
        private val bar = RectF()
        private val mat = Matrix()
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * d; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16f * d }
        private val ringColors = intArrayOf(0xFFFF5FA2.toInt(), 0xFFA66BFF.toInt(), 0xFF4DA3FF.toInt(), 0xFF3DE0D0.toInt(), 0xFFFFB35C.toInt(), 0xFFFF5FA2.toInt())

        fun contentWidth(): Float = textPaint.measureText(text) + (if (icon.isNotEmpty()) 26f * d else 0f)

        fun animateTo(tw: Float, th: Float) {
            anim?.cancel()
            tgtW = tw
            if (curW < 1f) { curW = idleW; curH = idleH; a = 0f }
            val w0 = curW
            val h0 = curH
            val a0 = a
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                interpolator = OvershootInterpolator(0.8f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    curW = w0 + (tw - w0) * f
                    curH = h0 + (th - h0) * f
                    a = a0 + (1f - a0) * min(1f, f * 2f)
                    invalidate()
                }
                start()
            }
            if (mode == AI) startSpin() else stopSpin()
            invalidate()
        }

        fun shrinkAndHide(onGone: () -> Unit) {
            anim?.cancel()
            stopSpin()
            val w0 = curW
            val h0 = curH
            val a0 = a
            var cancelled = false
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240
                addUpdateListener {
                    val f = it.animatedValue as Float
                    curW = w0 + (idleW - w0) * f
                    curH = h0 + (idleH - h0) * f
                    a = a0 * (1f - f)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: Animator) { if (!cancelled) onGone() }
                })
                start()
            }
        }

        private fun startSpin() {
            if (spin != null) return
            spin = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 2600
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { angle = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        private fun stopSpin() { spin?.cancel(); spin = null }

        override fun onDetachedFromWindow() {
            anim?.cancel(); anim = null
            stopSpin()
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            if (curW < 1f || a <= 0.01f) return
            val l = (width - curW) / 2f
            pill.set(l, 0f, l + curW, curH)
            val r = curH / 2f
            pillPaint.alpha = (255 * a).toInt().coerceIn(0, 255)
            c.drawRoundRect(pill, r, r, pillPaint)
            val ca = ((curW - idleW) / max(8f * d, tgtW - idleW)).coerceIn(0f, 1f) * a
            if (mode == AI) drawAi(c, r, ca) else drawInfo(c, ca)
        }

        private fun drawAi(c: Canvas, r: Float, ca: Float) {
            val cx = pill.centerX()
            val cy = pill.centerY()
            // cincin cahaya berputar: merah muda, ungu, biru, tosca, oranye
            val sh = SweepGradient(cx, cy, ringColors, null)
            mat.setRotate(angle, cx, cy)
            sh.setLocalMatrix(mat)
            ringPaint.shader = sh
            val widths = floatArrayOf(5f, 3f, 1.6f)
            val alphas = intArrayOf(45, 110, 255)
            for (i in 0..2) {
                val sw = widths[i] * d
                ringPaint.strokeWidth = sw
                ringPaint.alpha = (alphas[i] * a).toInt().coerceIn(0, 255)
                val inset = sw / 2f
                ring.set(pill.left + inset, pill.top + inset, pill.right - inset, pill.bottom - inset)
                val rr = max(0f, r - inset)
                c.drawRoundRect(ring, rr, rr, ringPaint)
            }
            if (ca < 0.05f) return
            // orb: berdenyut (mendengarkan), berputar (berpikir/bekerja), hangat (berbicara)
            val orbR = min(15f * d, curH * 0.3f)
            val ox = pill.left + 14f * d + orbR
            val t = (SystemClock.uptimeMillis() % 1800L) / 1800f
            val pulse = 1f + 0.12f * Math.sin(t * 6.2832).toFloat()
            val col = when (aiState) { 2 -> 0xFFA66BFF.toInt(); 3 -> 0xFFFF5FA2.toInt(); 4 -> 0xFF3DDC84.toInt(); else -> 0xFF4DA3FF.toInt() }
            orbPaint.style = Paint.Style.FILL
            orbPaint.shader = RadialGradient(ox, cy, orbR * pulse, intArrayOf(Color.WHITE, col, Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            orbPaint.alpha = (255 * ca).toInt()
            c.drawCircle(ox, cy, orbR * pulse * 0.95f, orbPaint)
            orbPaint.shader = null
            if (aiState == 2 || aiState == 4) {
                orbPaint.style = Paint.Style.STROKE
                orbPaint.strokeWidth = orbR * 0.16f
                orbPaint.strokeCap = Paint.Cap.ROUND
                orbPaint.color = Color.WHITE
                orbPaint.alpha = (255 * ca).toInt()
                val ar = orbR * 0.78f
                arc.set(ox - ar, cy - ar, ox + ar, cy + ar)
                c.drawArc(arc, t * 360f, 100f, false, orbPaint)
            }
            // gelombang 5 batang: mengikuti volume mikrofon saat mendengarkan
            val ww = 40f * d
            val wh = min(24f * d, curH * 0.5f)
            val wx = pill.right - 16f * d - ww
            val n = 5
            val gap = ww * 0.08f
            val bw = (ww - gap * (n - 1)) / n
            val ts = SystemClock.uptimeMillis() / 1000f
            orbPaint.style = Paint.Style.FILL
            orbPaint.color = when (aiState) { 3 -> 0xFFFF8FBF.toInt(); 4 -> 0xFF7BE8A8.toInt(); 2 -> 0xFFC4A6FF.toInt(); else -> 0xFF8CC4FF.toInt() }
            orbPaint.alpha = (255 * ca).toInt()
            for (i in 0 until n) {
                val base = when (aiState) { 1 -> 0.15f + level * 0.85f; 3 -> 0.5f; 2 -> 0.3f; else -> 0.35f }
                val wob = 0.5f + 0.5f * Math.sin((ts * (3.0 + i * 1.3) + i).toDouble()).toFloat()
                val amp = (base * (0.35f + 0.65f * wob)).coerceIn(0.12f, 1f)
                val hh = max(bw, wh * amp)
                val x = wx + i * (bw + gap)
                bar.set(x, cy - hh / 2f, x + bw, cy + hh / 2f)
                c.drawRoundRect(bar, bw / 2f, bw / 2f, orbPaint)
            }
            // teks status / transkrip
            val tx = pill.left + 14f * d + orbR * 2f + 12f * d
            val avail = wx - 10f * d - tx
            if (avail > 24f * d) {
                val s = TextUtils.ellipsize(text, textPaint, avail, TextUtils.TruncateAt.END).toString()
                textPaint.alpha = (255 * ca).toInt()
                val fm = textPaint.fontMetrics
                c.drawText(s, tx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
            }
        }

        private fun drawInfo(c: Canvas, ca: Float) {
            if (ca < 0.05f) return
            val cy = pill.centerY()
            val iw = if (icon.isNotEmpty()) 26f * d else 0f
            val avail = pill.width() - 32f * d - iw
            val s = TextUtils.ellipsize(text, textPaint, max(0f, avail), TextUtils.TruncateAt.END).toString()
            val total = iw + textPaint.measureText(s)
            var x = pill.centerX() - total / 2f
            if (icon.isNotEmpty()) {
                iconPaint.alpha = (255 * ca).toInt()
                val fm = iconPaint.fontMetrics
                c.drawText(icon, x, cy - (fm.ascent + fm.descent) / 2f, iconPaint)
                x += iw
            }
            textPaint.alpha = (255 * ca).toInt()
            val fm2 = textPaint.fontMetrics
            c.drawText(s, x, cy - (fm2.ascent + fm2.descent) / 2f, textPaint)
        }
    }
}
