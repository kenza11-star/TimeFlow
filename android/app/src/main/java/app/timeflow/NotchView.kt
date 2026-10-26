package app.timeflow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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

/** Tombol aksi tambahan dari notifikasi (mis. "Tandai dibaca"). */
data class NotifAct(val label: String, val run: () -> Unit)

/** reply != null berarti notifikasi punya kolom balasan (RemoteInput); reply(teks) mengembalikan true bila terkirim. */
data class NotifInfo(
    val key: String, val app: String, val title: String, val text: String, val icon: Drawable?, val color: Int,
    val open: (() -> Unit)?, val reply: ((String) -> Boolean)? = null, val replyHint: String = "",
    val actions: List<NotifAct> = emptyList(),
)

/** Alarm yang sedang berbunyi (alarm utama + jumlah alarm lain yang ikut berbunyi). */
data class AlarmInfo(val id: String, val label: String, val time: String, val snoozeMin: Int, val extra: Int)

/** Focus Timer dari halaman web. running = hitung mundur berjalan sampai endAt (epoch ms); jika dijeda, sisa waktu = leftMs. */
data class TimerInfo(val running: Boolean, val endAt: Long, val leftMs: Long)

interface NotchHost {
    fun resize(w: Int, h: Int)          // ubah ukuran jendela overlay (px)
    fun cfg(): NotchPrefs.Cfg
    fun visible(show: Boolean)          // pasang/lepas jendela (saat diam & disembunyikan, lepas supaya sentuhan tidak terhalang)
    fun focusable(on: Boolean)          // true = jendela boleh menerima fokus (perlu untuk mengetik balasan)
    fun transport(cmd: Int)             // 0 = sebelumnya, 1 = putar/jeda, 2 = berikutnya
    fun seek(frac: Float)
    fun openMedia()
    fun openApp()                       // buka aplikasi TimeFlow
    fun alarmSnooze(id: String)
    fun alarmStop(id: String)
}

/**
 * Pil hitam bergaya Dynamic Island: diam -> musik (ringkas) -> notifikasi (banner) -> kartu notifikasi (balas) / kontrol musik (penuh).
 * Animasi hanya menggambar ulang pil di dalam jendela; jendela di-resize sekali per transisi (bukan tiap frame), jadi ketukan terasa instan.
 */
class NotchView(ctx: Context, private val host: NotchHost) : FrameLayout(ctx) {
    private enum class Mode { IDLE, MEDIA, BANNER, FULL, NFULL, ALARM, TIMER }

    private val d = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var mode = Mode.IDLE
    private var media: MediaInfo? = null
    private var notif: NotifInfo? = null
    private val queue = ArrayDeque<NotifInfo>()
    private var alarm: AlarmInfo? = null
    private var timer: TimerInfo? = null
    private var notifOpen = false
    private var imeOn = false
    private var sentKey = ""
    private var expanded = false
    private var curW = 0f; private var curH = 0f; private var curR = 0f
    private var anim: ValueAnimator? = null
    private val accent = Color.rgb(255, 55, 95)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val pill = RectF()

    // ---- isi: musik ringkas ----
    private val artC = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
    private val eqC = Eq(ctx, accent)
    private val mediaBox = FrameLayout(ctx)

    // ---- isi: banner notifikasi ----
    private val bIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; clipToOutline = true }
    private val bApp = tv(12f, Color.argb(170, 255, 255, 255), false)
    private val bTitle = tv(15f, Color.WHITE, true)
    private val bText = tv(13f, Color.argb(200, 255, 255, 255), false).apply { maxLines = 2 }
    private val bannerBox = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    // ---- isi: kartu notifikasi (balas + aksi) ----
    private val nIcon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; clipToOutline = true }
    private val nApp = tv(12f, Color.argb(170, 255, 255, 255), false)
    private val nTitle = tv(15f, Color.WHITE, true)
    private val nText = tv(14f, Color.argb(225, 255, 255, 255), false).apply { maxLines = 5 }
    private val nClose = Glyph(ctx, 4)
    private val nSend = Glyph(ctx, 5)
    private val nStatus = tv(12f, Color.WHITE, false)
    private val nChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val nReplyRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val nBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val nEdit = EditText(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.argb(120, 255, 255, 255))
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEND
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Color.rgb(44, 44, 46)); cornerRadius = 20f * resources.displayMetrics.density }
    }

    // ---- isi: alarm berbunyi ----
    private val aIcon = Glyph(ctx, 6)
    private val aSub = tv(12f, Color.argb(170, 255, 255, 255), false)
    private val aTitle = tv(17f, Color.WHITE, true)
    private val aTime = tv(26f, Color.WHITE, true)
    private val aChips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val aBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    // ---- isi: hitung mundur timer ----
    private val tIcon = Glyph(ctx, 7)
    private val tText = tv(14f, Color.WHITE, true)
    private val tBox = FrameLayout(ctx)
    private val mTime = tv(12f, Color.WHITE, true)

    // ---- isi: kontrol musik penuh ----
    private val fArt = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
    private val fTitle = tv(16f, Color.WHITE, true)
    private val fArtist = tv(14f, Color.argb(170, 255, 255, 255), false)
    private val fEq = Eq(ctx, accent)
    private val fBar = Bar(ctx)
    private val fCur = tv(11f, Color.argb(150, 255, 255, 255), false)
    private val fLeft = tv(11f, Color.argb(150, 255, 255, 255), false)
    private val gPrev = Glyph(ctx, 0); private val gPlay = Glyph(ctx, 1); private val gNext = Glyph(ctx, 3)
    private val fullBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val tmTick = object : Runnable {
        override fun run() {
            if (timer == null) return
            paintTimer()
            ui.postDelayed(this, 1000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (mode == Mode.FULL) { refreshProgress(); ui.postDelayed(this, 500) }
        }
    }
    private val autoClose = Runnable { if (alarm != null || (mode == Mode.NFULL && nEdit.text.isNotEmpty())) poke() else dismiss() }

    // sentuhan manual (lebih toleran daripada GestureDetector: geser jari kecil tetap dihitung ketukan)
    private var down = false
    private var dx0 = 0f
    private var dy0 = 0f

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
    private fun chip(label: String, fill: Int = Color.rgb(44, 44, 46), onClick: () -> Unit): TextView {
        val t = TextView(context)
        t.text = label
        t.setTextColor(Color.WHITE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        t.background = GradientDrawable().apply { setColor(fill); cornerRadius = 18f * d }
        t.setOnClickListener { onClick() }
        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3f); rightMargin = dp(3f) }
        return t
    }

    init {
        setWillNotDraw(false)
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
        // kartu notifikasi
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val hc = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        hc.addView(nApp); hc.addView(nTitle)
        head.addView(nIcon, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        head.addView(hc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10f); rightMargin = dp(8f) })
        head.addView(nClose, LinearLayout.LayoutParams(dp(34f), dp(34f)))
        rounded(nIcon, 10f * d)
        head.setOnClickListener { openApp() }
        nClose.setOnClickListener { dismiss() }
        nReplyRow.addView(nEdit, LinearLayout.LayoutParams(0, dp(40f), 1f))
        nReplyRow.addView(nSend, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { leftMargin = dp(8f) })
        nEdit.setPadding(dp(14f), 0, dp(14f), 0)
        nSend.setOnClickListener { doSend() }
        nEdit.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false }
        nEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (mode == Mode.NFULL) poke() }
        })
        nStatus.visibility = View.GONE
        nBox.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        nBox.addView(head)
        nBox.addView(nText, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(10f) })
        nBox.addView(nReplyRow, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12f) })
        nBox.addView(nStatus, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6f) })
        nBox.addView(nChips, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12f) })
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
        // alarm berbunyi
        val ahead = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ac = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ac.addView(aSub); ac.addView(aTitle)
        aIcon.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        ahead.addView(aIcon, LinearLayout.LayoutParams(dp(42f), dp(42f)))
        ahead.addView(ac, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12f); rightMargin = dp(8f) })
        ahead.addView(aTime)
        ahead.setOnClickListener { host.openApp() }
        aBox.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        aBox.addView(ahead)
        aBox.addView(aChips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14f) })
        // timer ringkas: ikon kiri, hitung mundur kanan
        tBox.addView(tIcon, LayoutParams(dp(20f), dp(20f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(12f) })
        tBox.addView(tText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(14f) })
        // hitung mundur kecil di pil musik ringkas saat timer aktif
        mTime.visibility = View.GONE
        mediaBox.addView(mTime, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(44f) })
        for (b in listOf(mediaBox, bannerBox, fullBox, nBox, aBox, tBox)) { b.visibility = View.GONE; addView(b) }
    }

    // ---- gambar pil (hitam, sudut bulat) ----
    override fun dispatchDraw(c: Canvas) {
        val l = (width - curW) / 2f
        pill.set(l, 0f, l + curW, curH)
        c.drawRoundRect(pill, curR, curR, pillPaint)
        super.dispatchDraw(c)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); pivotX = w / 2f; pivotY = 0f }

    private fun inPill(e: MotionEvent): Boolean {
        val l = (width - curW) / 2f
        return e.x >= l && e.x <= l + curW && e.y >= 0f && e.y <= curH
    }

    private fun press(on: Boolean) {
        if (on && (mode == Mode.IDLE || mode == Mode.NFULL || mode == Mode.ALARM)) return
        animate().scaleX(if (on) 0.96f else 1f).scaleY(if (on) 0.96f else 1f).setDuration(90).start()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inPill(e)) return false
                down = true; dx0 = e.rawX; dy0 = e.rawY
                press(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!down) return false
                down = false; press(false)
                val dx = e.rawX - dx0; val dy = e.rawY - dy0
                if (dy < -dp(28f).toFloat() && abs(dy) > abs(dx) * 1.2f) { if (mode == Mode.BANNER || mode == Mode.MEDIA || mode == Mode.FULL) dismiss() }
                else if (abs(dx) < dp(24f) && abs(dy) < dp(24f)) tap()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { down = false; press(false); return true }
            MotionEvent.ACTION_OUTSIDE -> { if (mode == Mode.NFULL) dismiss(); return true }
        }
        return super.onTouchEvent(e)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && mode == Mode.NFULL) {
            if (event.action == KeyEvent.ACTION_UP) dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

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

    /** Notifikasi baru: tampil sebagai banner; yang datang saat banner/kartu masih aktif masuk antrean (maks. 8). */
    fun showNotif(n: NotifInfo) {
        val cur = notif
        if (cur != null && cur.key == n.key) {
            notif = n; bindNotif(n, true)
            if (notifOpen) { ui.removeCallbacks(autoClose); ui.postDelayed(autoClose, 20_000L); evaluate(true, force = true) }
            else scheduleBannerClose()
            return
        }
        if (cur != null) {
            val i = queue.indexOfFirst { it.key == n.key }
            if (i >= 0) queue[i] = n else if (queue.size < 8) queue.addLast(n)
            bindBadge()
            if (!notifOpen) scheduleBannerClose()
            return
        }
        notif = n; notifOpen = false
        bindNotif(n, false)
        scheduleBannerClose()
        evaluate(true)
    }

    /** Notifikasi dihapus dari bar (dibuka/ditutup di tempat lain): hilangkan juga dari notch. */
    fun removeNotif(key: String) {
        queue.removeAll { it.key == key }
        val cur = notif ?: return
        if (cur.key != key) { bindBadge(); return }
        if (key == sentKey) return
        if (notifOpen && nEdit.text.isNotEmpty()) return
        dismiss()
    }

    /** Alarm berbunyi: kartu dengan jam, label, Tunda dan Matikan; null = alarm selesai. */
    fun setAlarm(a: AlarmInfo?) {
        alarm = a
        if (a != null) {
            if (notifOpen) { leaveCard(); notifOpen = false }
            expanded = false
            bindAlarm(a)
        }
        evaluate(true, force = a != null)
    }

    /** Focus Timer: hitung mundur di pil (atau di pil musik); null = timer tidak aktif. */
    fun setTimer(t: TimerInfo?) {
        timer = t
        ui.removeCallbacks(tmTick)
        if (timerActive()) { paintTimer(); ui.postDelayed(tmTick, 1000) }
        mTime.visibility = if (timerActive()) View.VISIBLE else View.GONE
        evaluate(true, force = mode == Mode.MEDIA)
    }

    private fun timerActive(): Boolean { val t = timer; return t != null && (t.running || t.leftMs > 0L) }

    private fun clock(ms: Long): String {
        val s = (max(0L, ms) + 999L) / 1000L
        val h = s / 3600L; val m = (s % 3600L) / 60L; val sec = s % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    private fun paintTimer() {
        val t = timer ?: return
        val left = if (t.running) t.endAt - System.currentTimeMillis() else t.leftMs
        if (t.running && left < -2000L) { timer = null; mTime.visibility = View.GONE; evaluate(true, force = mode == Mode.MEDIA); return }
        val txt = clock(left)
        tText.text = txt; mTime.text = txt
        val col = if (t.running) Color.WHITE else Color.argb(150, 255, 255, 255)
        tText.setTextColor(col); mTime.setTextColor(col)
    }

    private fun bindAlarm(a: AlarmInfo) {
        aTitle.text = a.label.ifEmpty { "Alarm" }
        aSub.text = if (a.extra > 0) "Alarm berbunyi · +" + a.extra + " lainnya" else "Alarm berbunyi"
        aTime.text = a.time
        aChips.removeAllViews()
        aChips.addView(chip("Tunda " + a.snoozeMin + " mnt") { host.alarmSnooze(a.id) })
        aChips.addView(chip("Matikan", accent) { host.alarmStop(a.id) })
    }

    fun applyConfig() { evaluate(false, force = true) }

    fun release() {
        ui.removeCallbacksAndMessages(null); anim?.cancel()
        runCatching { leaveCard() }
        eqC.setPlaying(false); fEq.setPlaying(false)
    }

    // ---- logika ----
    private fun bindBadge() {
        val n = notif ?: return
        val badge = if (queue.size > 0) " · +" + queue.size else ""
        bApp.text = n.app + badge; nApp.text = n.app + badge
    }

    private fun bindNotif(n: NotifInfo, keepDraft: Boolean) {
        bIcon.setImageDrawable(n.icon); nIcon.setImageDrawable(n.icon)
        val bg = if (n.color != 0) n.color or (0xFF shl 24) else Color.rgb(60, 60, 67)
        bIcon.setBackgroundColor(bg); nIcon.setBackgroundColor(bg)
        bTitle.text = n.title; nTitle.text = n.title
        bText.text = n.text; nText.text = n.text
        val tvis = if (n.text.isEmpty()) View.GONE else View.VISIBLE
        bText.visibility = tvis; nText.visibility = tvis
        if (!keepDraft) { nEdit.setText(""); nStatus.visibility = View.GONE }
        nEdit.hint = if (n.replyHint.isNotEmpty()) n.replyHint else "Balas…"
        nReplyRow.visibility = if (n.reply != null) View.VISIBLE else View.GONE
        nSend.isEnabled = true; nSend.invalidate()
        nChips.removeAllViews()
        nChips.addView(chip("Buka") { openApp() })
        for (a in n.actions) nChips.addView(chip(a.label) { a.run(); dismiss() })
        bindBadge()
    }

    private fun scheduleBannerClose() {
        ui.removeCallbacks(autoClose)
        if (notifOpen) return
        var ms = max(2000L, host.cfg().autoHide * 1000L)
        if (queue.size > 0) ms = min(ms, 3000L)
        ui.postDelayed(autoClose, ms)
    }

    private fun tap() {
        when (mode) {
            Mode.BANNER -> openCard()
            Mode.MEDIA -> { expanded = true; poke(); evaluate(true) }
            Mode.FULL -> collapse()
            Mode.TIMER -> host.openApp()
            else -> {}
        }
    }

    private fun openCard() {
        val n = notif ?: return
        notifOpen = true
        ui.removeCallbacks(autoClose); ui.postDelayed(autoClose, 20_000L)
        if (n.reply != null) { imeOn = true; host.focusable(true) }
        evaluate(true)
        if (n.reply != null) {
            nEdit.requestFocus()
            ui.postDelayed({ if (imeOn) imm.showSoftInput(nEdit, 0) }, 160)
            ui.postDelayed({ if (imeOn) imm.showSoftInput(nEdit, 0) }, 450)
        }
    }

    private fun leaveCard() {
        if (!imeOn) return
        imeOn = false
        runCatching { imm.hideSoftInputFromWindow(nEdit.windowToken, 0) }
        nEdit.clearFocus()
        host.focusable(false)
    }

    private fun openApp() {
        val o = notif?.open
        dismiss()
        runCatching { o?.invoke() }
    }

    private fun status(msg: String, color: Int) {
        nStatus.text = msg; nStatus.setTextColor(color); nStatus.visibility = View.VISIBLE
        evaluate(true, force = true)
    }

    private fun doSend() {
        val n = notif ?: return
        val r = n.reply ?: return
        val t = nEdit.text.toString().trim()
        if (t.isEmpty()) return
        nSend.isEnabled = false; nSend.invalidate()
        val ok = runCatching { r(t) }.getOrDefault(false)
        if (ok) {
            sentKey = n.key
            nEdit.setText("")
            status("Terkirim", Color.rgb(48, 209, 88))
            ui.postDelayed({ if (notif?.key == n.key) dismiss() }, 900)
        } else {
            nSend.isEnabled = true; nSend.invalidate()
            status("Gagal terkirim. Coba buka aplikasinya.", Color.rgb(255, 69, 58))
        }
    }

    private fun poke() { ui.removeCallbacks(autoClose); ui.postDelayed(autoClose, if (mode == Mode.NFULL) 20_000L else 9000L) }
    private fun collapse() { expanded = false; ui.removeCallbacks(autoClose); evaluate(true) }

    private fun dismiss() {
        ui.removeCallbacks(autoClose)
        leaveCard()
        expanded = false; notifOpen = false
        val next = queue.removeFirstOrNull()
        if (next != null) { notif = next; bindNotif(next, false); scheduleBannerClose() } else notif = null
        evaluate(true)
    }

    private fun decide(): Mode {
        val c = host.cfg(); val m = media
        return when {
            alarm != null -> Mode.ALARM
            notif != null -> if (notifOpen) Mode.NFULL else Mode.BANNER
            expanded && m != null -> Mode.FULL
            timerActive() && !(c.showMedia && m != null && m.playing) -> Mode.TIMER
            c.showMedia && m != null && m.playing -> Mode.MEDIA
            else -> Mode.IDLE
        }
    }

    private fun evaluate(animate: Boolean, force: Boolean = false) {
        val next = decide(); val c = host.cfg()
        if (next == Mode.IDLE && !c.idleVisible) { mode = next; showContent(); host.visible(false); return }
        host.visible(true)
        if (next == mode && !force) { if (next == Mode.FULL || next == Mode.MEDIA) showContent(); return }
        mode = next
        showContent()
        animateTo(animate && curW > 0f)
        if (next == Mode.FULL) { ui.removeCallbacks(tick); ui.post(tick) }
    }

    private fun showContent() {
        mediaBox.visibility = if (mode == Mode.MEDIA) View.VISIBLE else View.GONE
        bannerBox.visibility = if (mode == Mode.BANNER) View.VISIBLE else View.GONE
        fullBox.visibility = if (mode == Mode.FULL) View.VISIBLE else View.GONE
        nBox.visibility = if (mode == Mode.NFULL) View.VISIBLE else View.GONE
        aBox.visibility = if (mode == Mode.ALARM) View.VISIBLE else View.GONE
        tBox.visibility = if (mode == Mode.TIMER) View.VISIBLE else View.GONE
    }

    private fun target(): Triple<Float, Float, Float> {
        val c = host.cfg(); val s = c.scale / 100f
        val sw = resources.displayMetrics.widthPixels.toFloat(); val maxW = sw - dp(16f)
        return when (mode) {
            Mode.IDLE -> Triple(c.width * d, c.height * d, c.height * d / 2f)
            Mode.MEDIA -> Triple(min(maxW, (c.width + 104f * s + (if (timerActive()) 54f * s else 0f)) * d), c.height * d, c.height * d / 2f)
            Mode.TIMER -> Triple(min(maxW, (c.width + 70f * s) * d), c.height * d, c.height * d / 2f)
            Mode.ALARM -> {
                val tw = min(maxW, 370f * s * d)
                aBox.measure(MeasureSpec.makeMeasureSpec(tw.toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                Triple(tw, min(aBox.measuredHeight.toFloat(), 220f * d), 28f * d)
            }
            Mode.BANNER -> Triple(min(maxW, 350f * s * d), 76f * s * d, 38f * s * d)
            Mode.FULL -> Triple(min(maxW, 370f * s * d), 178f * s * d, 40f * s * d)
            Mode.NFULL -> {
                val tw = min(maxW, 370f * s * d)
                nBox.measure(MeasureSpec.makeMeasureSpec(tw.toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                Triple(tw, min(nBox.measuredHeight.toFloat(), 360f * d), 28f * d)
            }
        }
    }

    private fun setPill(w: Float, h: Float, r: Float) { curW = w; curH = h; curR = r; invalidate() }

    private fun animateTo(animate: Boolean) {
        val (tw, th, tr) = target()
        anim?.cancel()
        val sw = if (curW > 0f) curW else tw; val sh = if (curH > 0f) curH else th; val sr = if (curR > 0f) curR else tr
        val boxes = listOf(mediaBox, bannerBox, fullBox, nBox, aBox, tBox)
        // isi diberi ukuran tetap sesuai target agar teks tidak "bergetar" saat animasi
        for (b in boxes) b.layoutParams = LayoutParams(tw.toInt(), th.toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        if (!animate) {
            setPill(tw, th, tr)
            host.resize(max(1, tw.toInt()), max(1, th.toInt()))
            for (b in boxes) b.alpha = 1f
            return
        }
        val grow = tw * th >= sw * sh
        // jendela dibesarkan sekali di awal (dengan sedikit ruang untuk overshoot), pil dianimasikan di dalamnya, lalu jendela dipas di akhir
        val screenW = resources.displayMetrics.widthPixels
        host.resize(min(screenW, (max(tw, sw) * 1.1f).toInt()), (max(th, sh) * 1.2f).toInt())
        for (b in boxes) b.alpha = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (grow) 300 else 200
            interpolator = if (grow) OvershootInterpolator(0.8f) else DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                setPill(sw + (tw - sw) * t, sh + (th - sh) * t, sr + (tr - sr) * t)
                val a = ((it.animatedFraction - 0.15f) / 0.6f).coerceIn(0f, 1f)
                for (b in boxes) b.alpha = a
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    setPill(tw, th, tr)
                    for (b in boxes) b.alpha = 1f
                    host.resize(max(1, tw.toInt()), max(1, th.toInt()))
                }
            })
            start()
        }
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

    // 0 sebelumnya, 1 putar, 2 jeda, 3 berikutnya, 4 tutup (X), 5 kirim, 6 lonceng, 7 timer
    class Glyph(ctx: Context, var kind: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val fillAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 55, 95) }
        private fun tri(c: Canvas, x: Float, cy: Float, u: Float, right: Boolean) {
            val s = if (right) 1f else -1f
            val path = Path().apply { moveTo(x - s * u * 0.8f, cy - u); lineTo(x + s * u * 0.8f, cy); lineTo(x - s * u * 0.8f, cy + u); close() }
            c.drawPath(path, p)
        }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val u = min(width, height) * 0.30f
            val dn = resources.displayMetrics.density
            when (kind) {
                1 -> tri(c, cx, cy, u * 1.15f, true)
                2 -> { c.drawRoundRect(cx - u * 0.9f, cy - u, cx - u * 0.25f, cy + u, 4f, 4f, p); c.drawRoundRect(cx + u * 0.25f, cy - u, cx + u * 0.9f, cy + u, 4f, 4f, p) }
                0 -> { tri(c, cx - u * 0.7f, cy, u, false); tri(c, cx + u * 0.5f, cy, u, false) }
                3 -> { tri(c, cx - u * 0.5f, cy, u, true); tri(c, cx + u * 0.7f, cy, u, true) }
                4 -> {
                    st.strokeWidth = 2.2f * dn; st.alpha = 190
                    val k = min(width, height) * 0.18f
                    c.drawLine(cx - k, cy - k, cx + k, cy + k, st); c.drawLine(cx + k, cy - k, cx - k, cy + k, st)
                }
                6 -> {
                    val b = u * 1.05f
                    val bell = Path().apply {
                        moveTo(cx - b, cy + b * 0.55f)
                        cubicTo(cx - b * 0.45f, cy + b * 0.3f, cx - b * 0.7f, cy - b * 0.95f, cx, cy - b * 0.95f)
                        cubicTo(cx + b * 0.7f, cy - b * 0.95f, cx + b * 0.45f, cy + b * 0.3f, cx + b, cy + b * 0.55f)
                        close()
                    }
                    c.drawPath(bell, p)
                    c.drawCircle(cx, cy + b * 0.95f, b * 0.28f, p)
                }
                7 -> {
                    st.strokeWidth = 2f * dn; st.alpha = 255
                    c.drawCircle(cx, cy + u * 0.15f, u * 0.95f, st)
                    c.drawLine(cx, cy + u * 0.15f, cx, cy - u * 0.4f, st)
                    c.drawLine(cx - u * 0.3f, cy - u * 1.1f, cx + u * 0.3f, cy - u * 1.1f, st)
                }
                else -> {
                    val r = min(width, height) / 2f
                    fillAccent.alpha = if (isEnabled) 255 else 110
                    c.drawCircle(cx, cy, r, fillAccent)
                    st.strokeWidth = 2.4f * dn; st.alpha = 255
                    val k = r * 0.42f
                    c.drawLine(cx, cy + k, cx, cy - k, st)
                    c.drawLine(cx - k * 0.8f, cy - k * 0.2f, cx, cy - k, st); c.drawLine(cx + k * 0.8f, cy - k * 0.2f, cx, cy - k, st)
                }
            }
        }
    }
}
