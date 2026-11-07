package app.timeflow

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Halaman musik layar penuh: dibuka dengan menekan Dynamic Island saat kontrol musik melebar.
 * Latar = sampul lagu, atau foto / GIF / video pilihan pengguna (disimpan sebagai URI dengan izin permanen, tanpa disalin, jadi tanpa batas ukuran).
 */
class MusicPageActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Float) = (v * d).toInt()
    private val sp: SharedPreferences by lazy { getSharedPreferences("musicpage", Context.MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var bg: ImageView
    private var tex: TextureView? = null
    private var player: MediaPlayer? = null
    private var vidReady = false
    private var vw = 0
    private var vh = 0
    private var anim: AnimatedImageDrawable? = null
    private var custom = false
    private var bgGen = 0
    private var resumed = false

    private lateinit var tTitle: TextView
    private lateinit var tArtist: TextView
    private lateinit var lPrev: TextView
    private lateinit var lCur: TextView
    private lateinit var lNext: TextView
    private lateinit var lBox: LinearLayout
    private lateinit var cur: TextView
    private lateinit var left: TextView
    private lateinit var bar: NotchView.Bar
    private lateinit var gPlay: NotchView.Glyph
    private lateinit var heart: MpIc
    private lateinit var lyricIc: MpIc
    private lateinit var clockPage: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var dot0: View
    private lateinit var dot1: View
    private lateinit var clkTitle: TextView
    private lateinit var gPlay2: NotchView.Glyph
    private lateinit var clkH: android.widget.TextClock
    private lateinit var clkM: android.widget.TextClock
    private var page = 0

    private var key = ""
    private var lastArt: Bitmap? = null
    private var lrc: Lyrics? = null
    private var lrcLine = -2
    private var landscape = false
    private var lyricsOn = true

    private val gd by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = b.rawY - (a?.rawY ?: b.rawY); val dx = b.rawX - (a?.rawX ?: b.rawX)
                if (abs(dx) > dp(90f) && abs(dx) > abs(dy) * 1.5f && abs(vx) > 1000f && !onBar(a?.rawY)) {
                    if (dx < 0 && page == 0) { setPage(1, true); return true }
                    if (dx > 0 && page == 1) { setPage(0, true); return true }
                    return false
                }
                if (vy > 1500f && dy > dp(110f) && abs(dy) > abs(dx) * 1.5f) { finish(); return true }
                return false
            }
        })
    }

    private fun onBar(y: Float?): Boolean {
        if (y == null || page != 0 || !::bar.isInitialized) return false
        val loc = IntArray(2); bar.getLocationOnScreen(loc)
        return y >= loc[1] - dp(16f) && y <= loc[1] + bar.height + dp(16f)
    }

    private fun setPage(p: Int, animate: Boolean) {
        page = p
        val w = (if (root.width > 0) root.width else resources.displayMetrics.widthPixels).toFloat()
        val c0 = if (p == 0) 0f else -w
        val c1 = if (p == 0) w else 0f
        if (animate) {
            content.animate().translationX(c0).setDuration(260).start()
            clockPage.animate().translationX(c1).setDuration(260).start()
        } else {
            content.animate().cancel(); clockPage.animate().cancel()
            content.translationX = c0; clockPage.translationX = c1
        }
        dot0.alpha = if (p == 0) 1f else 0.35f; dot1.alpha = if (p == 1) 1f else 0.35f
    }

    private fun applyClockSize() {
        val land = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val sz = if (land) 84f else 150f
        clkH.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz); clkM.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz)
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        applyClockSize()
        root.post { setPage(page, false) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (page == 1) setPage(0, true) else super.onBackPressed() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { gd.onTouchEvent(ev); return super.dispatchTouchEvent(ev) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        lyricsOn = sp.getBoolean("lyrics", true)
        build()
        applyBackground()
    }

    private fun tv(size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))
        val scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(10, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.argb(235, 0, 0, 0)))
        }
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, ins ->
            for (l in listOf(content, clockPage, overlay)) l.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }

        // tutup (kanan atas)
        val close = MpIc(this, 2).apply { setOnClickListener { finish() } }
        content.addView(close, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.END).apply { topMargin = dp(8f); rightMargin = dp(16f) })

        // lirik besar (kiri bawah)
        lPrev = tv(18f, Color.argb(110, 255, 255, 255), true).apply { maxLines = 2 }
        lCur = tv(36f, Color.WHITE, true).apply { maxLines = 4; setLineSpacing(0f, 0.95f) }
        lNext = tv(22f, Color.argb(150, 255, 255, 255), true).apply { maxLines = 2 }
        lBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
        lBox.addView(lPrev); lBox.addView(lCur, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f); bottomMargin = dp(6f) }); lBox.addView(lNext)
        content.addView(lBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(22f); rightMargin = dp(84f); bottomMargin = dp(236f)
        })

        // kolom kanan: suka, sebelumnya, putar/jeda, berikutnya
        heart = MpIc(this, 0).apply { setOnClickListener { toggleFav() } }
        val gPrev = NotchView.Glyph(this, 0).apply { setOnClickListener { tr(0) } }
        gPlay = NotchView.Glyph(this, 1).apply { setOnClickListener { tr(1) } }
        val gNext = NotchView.Glyph(this, 3).apply { setOnClickListener { tr(2) } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        for (v in listOf<View>(heart, gPrev, gPlay, gNext)) col.addView(v, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        content.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(12f); bottomMargin = dp(150f) })

        // bawah: artis, judul, bar progres, menu
        tArtist = tv(13f, Color.argb(170, 255, 255, 255), false).apply { maxLines = 1 }
        tTitle = tv(16f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        bar = NotchView.Bar(this).apply { onSeek = { f -> NotchListener.hubSeek(f); ui.postDelayed({ refresh() }, 200) } }
        cur = tv(11f, Color.argb(160, 255, 255, 255), false)
        left = tv(11f, Color.argb(160, 255, 255, 255), false)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(cur, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(left)
        lyricIc = MpIc(this, 6)
        val menu = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun item(ic: MpIc, label: String, onClick: (View) -> Unit) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { onClick(it) } }
            c.addView(ic, LinearLayout.LayoutParams(dp(40f), dp(36f)))
            c.addView(tv(10f, Color.argb(190, 255, 255, 255), false).apply { text = label; maxLines = 1; gravity = Gravity.CENTER })
            menu.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        }
        item(MpIc(this, 3), "Edit player") { editPlayer() }
        item(MpIc(this, 4), "Putar layar") { rotate() }
        item(lyricIc, "Lirik") { lyricsOn = !lyricsOn; sp.edit().putBoolean("lyrics", lyricsOn).apply(); lrcLine = -2; refresh() }
        item(MpIc(this, 5), "Menu lain") { otherMenu(it) }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(tArtist); bottom.addView(tTitle)
        bottom.addView(bar, LinearLayout.LayoutParams(-1, dp(22f)).apply { topMargin = dp(8f) })
        bottom.addView(times)
        bottom.addView(menu, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        content.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(22f); rightMargin = dp(22f); bottomMargin = dp(6f) })
        // halaman jam (geser kiri dari halaman player)
        fun clk(fmt: String, alpha: Int) = android.widget.TextClock(this).apply {
            format12Hour = fmt; format24Hour = fmt
            setTextColor(Color.argb(alpha, 255, 255, 255)); typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false; setLineSpacing(0f, 0.9f)
        }
        clkH = clk(if (android.text.format.DateFormat.is24HourFormat(this)) "HH" else "hh", 255)
        clkM = clk("mm", 215)
        val clkD = clk("EEEE, d MMMM", 200).apply { format12Hour = "EEEE, d MMMM"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
        applyClockSize()
        clockPage = FrameLayout(this)
        val cl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cl.addView(clkH); cl.addView(clkM); cl.addView(clkD, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f) })
        clockPage.addView(cl, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(26f) })
        clkTitle = tv(14f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        gPlay2 = NotchView.Glyph(this, 1).apply { setOnClickListener { tr(1) } }
        val nowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nowRow.addView(clkTitle, LinearLayout.LayoutParams(0, -2, 1f)); nowRow.addView(gPlay2, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        clockPage.addView(nowRow, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(22f); rightMargin = dp(16f); bottomMargin = dp(6f) })
        clockPage.translationX = resources.displayMetrics.widthPixels.toFloat()
        root.addView(clockPage, FrameLayout.LayoutParams(-1, -1))
        // penanda halaman (atas, tengah)
        overlay = FrameLayout(this)
        fun dot() = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
        dot0 = dot(); dot1 = dot(); dot1.alpha = 0.35f
        val dl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dl.addView(dot0, LinearLayout.LayoutParams(dp(6f), dp(6f)).apply { rightMargin = dp(6f) }); dl.addView(dot1, LinearLayout.LayoutParams(dp(6f), dp(6f)))
        overlay.addView(dl, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14f) })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        for (l in listOf(content, clockPage, overlay)) l.setPadding(0, dp(24f), 0, dp(16f))
        setContentView(root)
        root.requestApplyInsets()
    }

    // ---------- kontrol ----------
    private fun tr(cmd: Int) { NotchListener.hubTransport(cmd); ui.postDelayed({ refresh() }, 180) }

    private fun favs(): Set<String> = sp.getStringSet("favs", emptySet()) ?: emptySet()

    private fun toggleFav() {
        if (key.isEmpty()) return
        val s = HashSet(favs())
        if (!s.remove(key)) s.add(key)
        sp.edit().putStringSet("favs", s).apply()
        heart.kind = if (s.contains(key)) 1 else 0; heart.invalidate()
    }

    private fun rotate() {
        landscape = !landscape
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun otherMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Buka aplikasi musik")
        pm.menu.add(0, 2, 1, "Kembalikan latar ke sampul lagu")
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> NotchListener.hubOpenMedia(); 2 -> resetBg() }
            true
        }
        pm.show()
    }

    private fun editPlayer() {
        val items = arrayOf("Pilih foto / GIF / video", "Kembalikan ke sampul lagu")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Edit player")
            .setItems(items) { _, i -> if (i == 0) pick() else resetBg() }.show()
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, 77) } catch (_: Exception) { Toast.makeText(this, "Pemilih file tidak tersedia", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc != 77 || res != RESULT_OK) return
        val u = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        sp.edit().putString("bg_uri", u.toString()).apply()
        applyBackground()
    }

    private fun resetBg() { sp.edit().remove("bg_uri").apply(); applyBackground() }

    // ---------- latar ----------
    private fun clearMedia() {
        bgGen++
        anim?.stop(); anim = null
        player?.let { runCatching { it.release() } }; player = null; vidReady = false
        tex?.let { root.removeView(it) }; tex = null
    }

    private fun applyBackground() {
        clearMedia()
        val s = sp.getString("bg_uri", null)
        if (s == null) { custom = false; bg.visibility = View.VISIBLE; showCover(); return }
        custom = true
        val uri = Uri.parse(s)
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
        if (mime.startsWith("video/")) startVideo(uri) else loadImage(uri)
    }

    private fun failBg() {
        Toast.makeText(this, "Latar tidak bisa dibuka, kembali ke sampul lagu", Toast.LENGTH_LONG).show()
        resetBg()
    }

    private fun showCover() {
        if (Build.VERSION.SDK_INT >= 31) {
            val a = lastArt
            bg.setRenderEffect(if (a != null && a.width < 700) RenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.CLAMP) else null)
        }
        bg.setImageBitmap(lastArt)
    }

    private fun decode(uri: Uri): Drawable {
        val big = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeDrawable(src) { dec, info, _ ->
                val m = max(info.size.width, info.size.height)
                if (m > big) {
                    val k = big.toFloat() / m
                    dec.setTargetSize(max(1, (info.size.width * k).toInt()), max(1, (info.size.height * k).toInt()))
                }
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var ss = 1
        while (max(o.outWidth, o.outHeight) / ss > big * 2) ss *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = ss }
        val b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: throw IllegalStateException("gagal")
        return BitmapDrawable(resources, b)
    }

    private fun loadImage(uri: Uri) {
        val gen = bgGen
        Thread {
            val dr = runCatching { decode(uri) }.getOrNull()
            ui.post {
                if (gen != bgGen || isDestroyed) return@post
                if (dr == null) { failBg(); return@post }
                if (Build.VERSION.SDK_INT >= 31) bg.setRenderEffect(null)
                bg.visibility = View.VISIBLE
                bg.setImageDrawable(dr)
                if (Build.VERSION.SDK_INT >= 28 && dr is AnimatedImageDrawable) {
                    dr.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    anim = dr
                    if (resumed) dr.start()
                }
            }
        }.start()
    }

    private fun startVideo(uri: Uri) {
        bg.visibility = View.GONE
        val t = TextureView(this)
        tex = t
        root.addView(t, 1, FrameLayout.LayoutParams(-1, -1))
        t.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        val gen = bgGen
        t.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (gen == bgGen) prepareVideo(uri, Surface(st)) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fitVideo() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { player?.let { runCatching { it.release() } }; player = null; vidReady = false; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun prepareVideo(uri: Uri, surface: Surface) {
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(this, uri)
            p.setSurface(surface)
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; fitVideo() }
            p.setOnPreparedListener { vidReady = true; if (resumed) runCatching { it.start() } }
            p.setOnErrorListener { _, _, _ -> ui.post { failBg() }; true }
            p.prepareAsync()
        } catch (_: Exception) { failBg() }
    }

    /** Isi layar tanpa gepeng (center-crop) untuk TextureView. */
    private fun fitVideo() {
        val t = tex ?: return
        if (vw <= 0 || vh <= 0 || t.width <= 0 || t.height <= 0) return
        val w = t.width.toFloat(); val h = t.height.toFloat()
        val vr = vw.toFloat() / vh; val r = w / h
        val m = Matrix()
        if (vr > r) m.setScale(vr / r, 1f, w / 2f, h / 2f) else m.setScale(1f, r / vr, w / 2f, h / 2f)
        t.setTransform(m)
    }

    // ---------- pembaruan berkala ----------
    private val poll = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 400) } }

    private fun fmt(ms: Long): String { val s = max(0L, ms) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun refresh() {
        lyricIc.active = lyricsOn; lyricIc.invalidate()
        lBox.visibility = if (lyricsOn) View.VISIBLE else View.GONE
        val m = NotchListener.hubMedia()
        if (m == null) {
            tTitle.text = "Tidak ada musik yang diputar"; tArtist.text = ""; clkTitle.text = tTitle.text
            gPlay.kind = 1; gPlay.invalidate(); gPlay2.kind = 1; gPlay2.invalidate(); return
        }
        val k = m.title + "|" + m.artist
        if (k != key) {
            key = k
            tTitle.text = m.title.ifEmpty { "Tanpa judul" }; tArtist.text = m.artist
            clkTitle.text = tTitle.text.toString() + (if (m.artist.isNotEmpty()) " · " + m.artist else "")
            heart.kind = if (favs().contains(k)) 1 else 0; heart.invalidate()
            lrc = null; lrcLine = -2
            lPrev.text = ""; lNext.text = ""; lCur.text = ""
            CcLyrics.request(this, m.title, m.artist, m.dur) { l -> ui.post { if (key == k) { lrc = l; lrcLine = -2 } } }
        }
        if (m.art !== lastArt) { lastArt = m.art; if (!custom) showCover() }
        val pk = if (m.playing) 2 else 1
        if (gPlay.kind != pk) { gPlay.kind = pk; gPlay.invalidate() }
        if (gPlay2.kind != pk) { gPlay2.kind = pk; gPlay2.invalidate() }
        val now = if (m.playing) m.pos + ((SystemClock.elapsedRealtime() - m.stamp) * m.speed).toLong() else m.pos
        bar.frac = if (m.dur > 0) now.coerceIn(0L, m.dur).toFloat() / m.dur else 0f
        bar.invalidate()
        cur.text = fmt(now); left.text = if (m.dur > 0) "-" + fmt(m.dur - now) else ""
        if (!lyricsOn) return
        val l = lrc
        if (l == null) {
            val st = CcLyrics.status(m.title, m.artist)
            if (lCur.text.toString() != st) { lPrev.text = ""; lNext.text = ""; lCur.text = st; lCur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) }
            return
        }
        val i = l.indexAt(now + 250)
        if (i != lrcLine) {
            lrcLine = i
            lCur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            lPrev.text = if (i >= 1) l.lines[i - 1] else ""
            lCur.text = if (i >= 0) l.lines[i].ifEmpty { "♪" } else ""
            lNext.text = if (i + 1 < l.lines.size) l.lines[i + 1] else ""
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ui.removeCallbacks(poll); ui.post(poll)
        if (vidReady) runCatching { player?.start() }
        anim?.start()
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(poll)
        runCatching { if (vidReady) player?.pause() }
        anim?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        clearMedia()
        super.onDestroy()
    }
}

/** Ikon garis untuk halaman musik. 0 hati, 1 hati terisi, 2 tutup (panah bawah), 3 pengaturan player, 4 putar layar, 5 titik tiga, 6 lirik. */
class MpIc(ctx: Context, var kind: Int) : View(ctx) {
    var active = true
    private val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val dn = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f; val u = min(width, height) * 0.28f
        st.strokeWidth = 2f * dn
        val a = if (active) 255 else 90
        st.alpha = a; fl.alpha = a
        when (kind) {
            0, 1 -> {
                val p = Path().apply {
                    moveTo(cx, cy + u * 0.95f)
                    cubicTo(cx - u * 1.9f, cy - u * 0.1f, cx - u * 0.8f, cy - u * 1.4f, cx, cy - u * 0.4f)
                    cubicTo(cx + u * 0.8f, cy - u * 1.4f, cx + u * 1.9f, cy - u * 0.1f, cx, cy + u * 0.95f)
                    close()
                }
                if (kind == 1) { fl.color = Color.rgb(255, 55, 95); c.drawPath(p, fl); fl.color = Color.WHITE } else c.drawPath(p, st)
            }
            2 -> { st.strokeWidth = 2.6f * dn; c.drawLine(cx - u, cy - u * 0.35f, cx, cy + u * 0.5f, st); c.drawLine(cx, cy + u * 0.5f, cx + u, cy - u * 0.35f, st) }
            3 -> {
                for (i in 0..2) {
                    val y = cy + (i - 1) * u * 0.85f
                    c.drawLine(cx - u * 1.1f, y, cx + u * 1.1f, y, st)
                    val kx = cx + (if (i == 1) 0.45f else if (i == 0) -0.4f else 0.1f) * u
                    c.drawCircle(kx, y, u * 0.22f, fl)
                }
            }
            4 -> {
                rect.set(cx - u, cy - u, cx + u, cy + u)
                c.drawArc(rect, -50f, 280f, false, st)
                val ang = Math.toRadians(-50.0)
                val px = cx + u * cos(ang).toFloat(); val py = cy + u * sin(ang).toFloat()
                val ar = Path().apply { moveTo(px - u * 0.1f, py - u * 0.55f); lineTo(px + u * 0.5f, py - u * 0.05f); lineTo(px - u * 0.35f, py + u * 0.1f); close() }
                c.drawPath(ar, fl)
            }
            5 -> for (i in -1..1) c.drawCircle(cx, cy + i * u * 0.75f, u * 0.17f, fl)
            else -> {
                rect.set(cx - u * 1.1f, cy - u * 0.85f, cx + u * 1.1f, cy + u * 0.85f)
                c.drawRoundRect(rect, u * 0.35f, u * 0.35f, st)
                c.drawLine(cx - u * 0.6f, cy - u * 0.2f, cx + u * 0.6f, cy - u * 0.2f, st)
                c.drawLine(cx - u * 0.6f, cy + u * 0.3f, cx + u * 0.2f, cy + u * 0.3f, st)
            }
        }
    }
}
