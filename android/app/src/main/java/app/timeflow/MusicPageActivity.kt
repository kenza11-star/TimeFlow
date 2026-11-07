package app.timeflow

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Halaman musik layar penuh (landscape, dua panel), dibuka dengan menekan Dynamic Island saat kontrol musik melebar.
 *
 * KIRI  : sampul / foto / GIF / video pilihan pengguna (penuh), sampul kecil + judul + artis + hati, bar progres, tombol prev / putar / next.
 * KANAN : panel hitam yang digeser VERTIKAL (5 halaman):
 *         0 lirik (baris aktif putih, baris lain blur + redup)   1 jam digital besar bertumpuk (08 / 38 + "7 sat")
 *         2 jam analog besar                                     3 visualizer + jam analog mini
 *         4 visualizer + jam digital "8:38"
 */
class MusicPageActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Float) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val sp: SharedPreferences by lazy { getSharedPreferences("musicpage", Context.MODE_PRIVATE) }

    private lateinit var root: FrameLayout
    private lateinit var cover: FrameLayout
    private var gallery: MpGallerySheet? = null
    private var menu: MpMenu? = null
    private lateinit var leftPane: FrameLayout
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
    private lateinit var thumb: ImageView
    private lateinit var cur: TextView
    private lateinit var left: TextView
    private lateinit var bar: MpBar
    private lateinit var cPlay: MpCtl
    private lateinit var heart: MpIc
    private lateinit var pager: Pager
    private lateinit var dots: MpDots
    private lateinit var lyricsV: LyricsView
    private lateinit var dLabel: TextView
    private lateinit var dHour: TextView
    private lateinit var dMin: TextView
    private lateinit var dShort: TextView
    private lateinit var clockBig: MpAnalog
    private lateinit var clockMini: MpAnalog
    private lateinit var viz3: MpViz
    private lateinit var viz4: MpViz

    private var key = ""
    private var lastArt: Bitmap? = null
    private var thumbArt: Bitmap? = null
    private var lrc: Lyrics? = null
    private var lrcShown: Lyrics? = null
    private var lrcLine = -2
    private var lyricsOn = true
    private var playing = false

    // ---------- gestur: geser bawah di panel kiri = tutup ----------
    private val gd by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                if (a == null) return false
                val dy = b.rawY - a.rawY; val dx = b.rawX - a.rawX
                val inLeft = a.rawX < leftPane.width + dp(40f)
                if (inLeft && vy > 1400f && dy > dp(90f) && abs(dy) > abs(dx) * 1.5f) { finish(); return true }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { gd.onTouchEvent(ev); return super.dispatchTouchEvent(ev) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val g = gallery; val m = menu
        if (g != null) g.close() else if (m != null) m.dismiss() else if (pager.target != 0) pager.goTo(0) else super.onBackPressed()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        lyricsOn = sp.getBoolean("lyrics", true)
        build()
        applyBackground()
    }

    private fun tv(size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun press(v: View) {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> x.animate().scaleX(0.84f).scaleY(0.84f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> x.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(PathInterpolator(0.2f, 1.4f, 0.4f, 1f)).start()
            }
            false
        }
    }

    // ====================================================================================================
    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ---------------- LAPISAN SAMPUL (di bawah kedua panel, memudar halus ke hitam) ----------------
        cover = FrameLayout(this)
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        cover.addView(bg, FrameLayout.LayoutParams(-1, -1))
        // gelap atas/bawah supaya teks & tombol terbaca
        cover.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.argb(235, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, -1))
        // pudar horizontal panjang: sampul menyatu ke panel hitam tanpa tepi keras
        cover.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(22, 0, 0, 0),
                Color.argb(80, 0, 0, 0), Color.argb(165, 0, 0, 0), Color.argb(232, 0, 0, 0), Color.argb(255, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, -1))
        val coverRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        coverRow.addView(cover, LinearLayout.LayoutParams(0, -1, 68f))
        coverRow.addView(View(this), LinearLayout.LayoutParams(0, -1, 32f))
        root.addView(coverRow, FrameLayout.LayoutParams(-1, -1))

        // ---------------- PANEL KIRI (transparan) ----------------
        val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(panes, FrameLayout.LayoutParams(-1, -1))
        leftPane = FrameLayout(this)

        // pegangan atas (ketuk = tutup)
        val handleL = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(3f); setColor(Color.argb(210, 255, 255, 255)) }
            setOnClickListener { finish() }
        }
        leftPane.addView(handleL, FrameLayout.LayoutParams(dp(68f), dp(5f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(5f); leftMargin = dp(60f) })

        // baris info: sampul kecil + judul/artis + hati
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(8f)) } }
            clipToOutline = true
            setBackgroundColor(Color.argb(60, 255, 255, 255))
        }
        tTitle = tv(20f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; letterSpacing = -0.01f }
        tArtist = tv(13f, Color.argb(170, 255, 255, 255), false).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        names.addView(tTitle); names.addView(tArtist, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2f) })
        heart = MpIc(this, 0).apply { setOnClickListener { toggleFav() } }
        press(heart)
        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        info.addView(thumb, LinearLayout.LayoutParams(dp(41f), dp(41f)))
        info.addView(names, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(11f); rightMargin = dp(8f) })
        info.addView(heart, LinearLayout.LayoutParams(dp(40f), dp(40f)))

        // waktu + bar
        cur = tv(11f, Color.WHITE, true); left = tv(11f, Color.WHITE, true)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        times.addView(cur, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(left)
        bar = MpBar(this).apply { onSeek = { f -> NotchListener.hubSeek(f); ui.postDelayed({ refresh() }, 200) } }

        // tombol
        val gPrev = MpCtl(this, 0).apply { setOnClickListener { tr(0) } }
        cPlay = MpCtl(this, 1).apply { setOnClickListener { tr(1) } }
        val gNext = MpCtl(this, 3).apply { setOnClickListener { tr(2) } }
        press(gPrev); press(cPlay); press(gNext)
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (v in listOf<View>(gPrev, cPlay, gNext)) ctl.addView(v, LinearLayout.LayoutParams(dp(73f), dp(44f)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(info)
        col.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(16f)).apply { topMargin = dp(2f) })
        col.addView(ctl, LinearLayout.LayoutParams(-1, dp(44f)).apply { topMargin = dp(2f) })
        leftPane.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(61f); rightMargin = dp(12f); bottomMargin = dp(13f) })

        panes.addView(leftPane, LinearLayout.LayoutParams(0, -1, 53f))

        // ---------------- PANEL KANAN ----------------
        val right = FrameLayout(this)
        pager = Pager(this)
        dots = MpDots(this)

        // 0 lirik
        lyricsV = LyricsView(this)
        pager.add(lyricsV)
        // 1 jam digital besar bertumpuk
        val pg1 = FrameLayout(this)
        dLabel = tv(17f, Color.WHITE, true)
        dHour = tv(122f, Color.WHITE, true).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = -0.03f }
        dMin = tv(122f, Color.WHITE, true).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = -0.03f }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        stack.addView(dLabel)
        stack.addView(dHour, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2f) })
        stack.addView(dMin, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(-14f) })
        pg1.addView(stack, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        pager.add(pg1)
        // 2 jam analog besar
        val pg2 = FrameLayout(this)
        clockBig = MpAnalog(this)
        pg2.addView(clockBig, FrameLayout.LayoutParams(dp(268f), dp(268f), Gravity.CENTER))
        pager.add(pg2)
        // 3 visualizer + jam mini
        val pg3 = FrameLayout(this)
        viz3 = MpViz(this)
        pg3.addView(viz3, FrameLayout.LayoutParams(-1, -1))
        clockMini = MpAnalog(this)
        pg3.addView(clockMini, FrameLayout.LayoutParams(dp(66f), dp(66f), Gravity.TOP or Gravity.END).apply { topMargin = dp(13f); rightMargin = dp(48f) })
        pager.add(pg3)
        // 4 visualizer + 8:38
        val pg4 = FrameLayout(this)
        viz4 = MpViz(this)
        pg4.addView(viz4, FrameLayout.LayoutParams(-1, -1))
        dShort = tv(62f, Color.WHITE, true).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = -0.02f }
        pg4.addView(dShort, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(34f); rightMargin = dp(36f) })
        pager.add(pg4)

        right.addView(pager, FrameLayout.LayoutParams(-1, -1))
        right.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(2f); setColor(Color.argb(150, 142, 142, 147)) }
        }, FrameLayout.LayoutParams(dp(37f), dp(4f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(20f) })
        right.addView(dots, FrameLayout.LayoutParams(dp(14f), dp(80f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(3f); bottomMargin = dp(14f) })
        val more = MpIc(this, 5).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(150, 70, 70, 74)) }
            rotation = 90f
            setOnClickListener { otherMenu(it) }
        }
        press(more)
        right.addView(more, FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.TOP or Gravity.END).apply { topMargin = dp(14f); rightMargin = dp(14f) })
        panes.addView(right, LinearLayout.LayoutParams(0, -1, 47f))

        pager.dots = dots
        pager.onSettle = { p -> onPageSettled(p) }
        setContentView(root)
        tickClock()
    }

    // ====================================================================================================
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

    private fun toggleLyrics() {
        lyricsOn = !lyricsOn; sp.edit().putBoolean("lyrics", lyricsOn).apply(); lrcLine = -2; lrcShown = null; refresh()
    }

    private fun otherMenu(anchor: View) {
        menu?.dismiss()
        val m = MpMenu(this, listOf(
            "Edit player" to { openEdit() },
            (if (lyricsOn) "Matikan lirik" else "Nyalakan lirik") to { toggleLyrics() },
            "Buka aplikasi musik" to { NotchListener.hubOpenMedia() },
            "Kembalikan ke sampul lagu" to { resetBg() }
        )) { menu = null }
        menu = m
        root.addView(m.view, FrameLayout.LayoutParams(-1, -1)); m.show()
    }

    private fun openEdit() {
        try {
            gallery?.close()
            val g = MpGallerySheet(this, sp.getString("bg_uri", null),
                onPick = { u -> sp.edit().putString("bg_uri", u.toString()).apply(); applyBackground() },
                onReset = { resetBg() },
                onSystemPicker = { pick() },
                onClosed = { gallery = null })
            gallery = g
            root.addView(g.view, FrameLayout.LayoutParams(-1, -1)); g.show()
        } catch (e: Throwable) {
            gallery = null
            Toast.makeText(this, "Galeri gagal dibuka (" + (e.message ?: e.javaClass.simpleName) + "), memakai pemilih file", Toast.LENGTH_LONG).show()
            pick()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        gallery?.reload()
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

    // ====================================================================================================
    // ---------- latar (panel kiri) ----------
    private fun clearMedia() {
        bgGen++
        anim?.stop(); anim = null
        player?.let { runCatching { it.release() } }; player = null; vidReady = false
        tex?.let { cover.removeView(it) }; tex = null
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
        if (Build.VERSION.SDK_INT >= 31) bg.setRenderEffect(null)   // sampul TIDAK di-blur
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
        cover.addView(t, 0, FrameLayout.LayoutParams(-1, -1))
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

    /** Isi panel tanpa gepeng (center-crop) untuk TextureView. */
    private fun fitVideo() {
        val t = tex ?: return
        if (vw <= 0 || vh <= 0 || t.width <= 0 || t.height <= 0) return
        val w = t.width.toFloat(); val h = t.height.toFloat()
        val vr = vw.toFloat() / vh; val r = w / h
        val m = Matrix()
        if (vr > r) m.setScale(vr / r, 1f, w / 2f, h / 2f) else m.setScale(1f, r / vr, w / 2f, h / 2f)
        t.setTransform(m)
    }

    // ====================================================================================================
    // ---------- jam (tiap detik) ----------
    private val clockTick = object : Runnable { override fun run() { tickClock(); ui.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L) } }

    private fun tickClock() {
        val c = Calendar.getInstance()
        val is24 = DateFormat.is24HourFormat(this)
        val h = if (is24) c.get(Calendar.HOUR_OF_DAY) else (c.get(Calendar.HOUR).let { if (it == 0) 12 else it })
        val m = c.get(Calendar.MINUTE)
        dHour.text = "%02d".format(h); dMin.text = "%02d".format(m)
        dShort.text = "%d:%02d".format(h, m)
        val wd = java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(c.time).lowercase(Locale.getDefault())
        dLabel.text = "${c.get(Calendar.DAY_OF_MONTH)} $wd"
        clockBig.invalidate(); clockMini.invalidate()
    }

    // ---------- visualizer ----------
    private val bars = FloatArray(12)
    private val real = FloatArray(12)
    @Volatile private var realStamp = 0L
    private var viz: Visualizer? = null
    private var vizOn = false
    private val envelope = floatArrayOf(0.52f, 0.78f, 0.66f, 0.94f, 0.95f, 0.70f, 0.64f, 0.30f, 0.22f, 0.20f, 0.23f, 0.18f)

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!vizOn) return
            val now = SystemClock.uptimeMillis()
            val sec = now / 1000f
            val useReal = now - realStamp < 350L
            for (i in bars.indices) {
                val tgt = if (!playing) 0f else if (useReal) real[i] else {
                    val n = 0.5f + 0.5f * sin(sec * (2.3f + 0.41f * i) + i * 1.7f) * (0.6f + 0.4f * sin(sec * 1.31f + i * 0.9f))
                    envelope[i] * (0.45f + 0.55f * n)
                }
                val k = if (tgt > bars[i]) 0.32f else 0.12f
                bars[i] += (tgt - bars[i]) * k
            }
            viz3.bars = bars; viz4.bars = bars
            if (pager.pos > 2.2f) { viz3.invalidate(); viz4.invalidate() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startViz() {
        if (vizOn || !resumed) return
        vizOn = true
        Choreographer.getInstance().postFrameCallback(frame)
        if (viz == null && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                val v = Visualizer(0)
                v.enabled = false
                v.captureSize = min(512, Visualizer.getCaptureSizeRange()[1])
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(x: Visualizer?, w: ByteArray?, r: Int) {}
                    override fun onFftDataCapture(x: Visualizer?, fft: ByteArray?, r: Int) {
                        if (fft == null) return
                        val n = fft.size / 2
                        val top = max(8, (n * 0.42f).toInt())
                        for (b in 0 until 12) {
                            val lo = max(1, Math.pow(top.toDouble(), b / 12.0).toInt())
                            val hi = max(lo + 1, Math.pow(top.toDouble(), (b + 1) / 12.0).toInt())
                            var mx = 0f
                            for (k in lo until min(hi, n)) {
                                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                                mx = max(mx, sqrt(re * re + im * im))
                            }
                            val lift = 0.55f + 0.09f * b
                            real[b] = min(1f, sqrt(mx / 95f) * lift)
                        }
                        realStamp = SystemClock.uptimeMillis()
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                v.enabled = true
                viz = v
            }
        }
    }

    private fun stopViz() {
        vizOn = false
        Choreographer.getInstance().removeFrameCallback(frame)
        viz?.let { runCatching { it.enabled = false; it.release() } }; viz = null
    }

    private fun onPageSettled(p: Int) { if (p >= 3) startViz() else stopViz() }

    // ====================================================================================================
    // ---------- pembaruan berkala ----------
    private val poll = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 400) } }

    private fun fmt(ms: Long): String { val s = max(0L, ms) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    private fun refresh() {
        val m = NotchListener.hubMedia()
        if (m == null) {
            tTitle.text = "Tidak ada musik yang diputar"; tArtist.text = ""
            cPlay.kind = 1; cPlay.invalidate(); playing = false
            lyricsV.showStatus(""); return
        }
        playing = m.playing
        val k = m.title + "|" + m.artist
        if (k != key) {
            key = k
            tTitle.text = m.title.ifEmpty { "Tanpa judul" }; tArtist.text = m.artist
            heart.kind = if (favs().contains(k)) 1 else 0; heart.invalidate()
            lrc = null; lrcShown = null; lrcLine = -2
            lyricsV.setLines(emptyList())
            CcLyrics.request(this, m.title, m.artist, m.dur) { l -> ui.post { if (key == k) { lrc = l; lrcLine = -2 } } }
        }
        if (m.art !== lastArt) { lastArt = m.art; if (!custom) showCover() }
        if (m.art !== thumbArt) { thumbArt = m.art; thumb.setImageBitmap(m.art) }
        val pk = if (m.playing) 2 else 1
        if (cPlay.kind != pk) { cPlay.kind = pk; cPlay.invalidate() }
        val now = if (m.playing) m.pos + ((SystemClock.elapsedRealtime() - m.stamp) * m.speed).toLong() else m.pos
        bar.setFrac(if (m.dur > 0) now.coerceIn(0L, m.dur).toFloat() / m.dur else 0f)
        cur.text = fmt(now); left.text = if (m.dur > 0) fmt(m.dur) else ""

        if (!lyricsOn) { lyricsV.showStatus("Lirik dimatikan"); return }
        val l = lrc
        if (l == null) { lyricsV.showStatus(CcLyrics.status(m.title, m.artist)); return }
        if (lrcShown !== l) { lrcShown = l; lyricsV.setLines(l.lines.toList()); lrcLine = -2 }
        val i = l.indexAt(now + 250)
        if (i != lrcLine) { val first = lrcLine == -2; lrcLine = i; lyricsV.setActive(i, !first) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        ui.removeCallbacks(poll); ui.post(poll)
        ui.removeCallbacks(clockTick); ui.post(clockTick)
        if (vidReady) runCatching { player?.start() }
        anim?.start()
        if (pager.target >= 3) startViz()
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(poll); ui.removeCallbacks(clockTick)
        stopViz()
        runCatching { if (vidReady) player?.pause() }
        anim?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopViz()
        clearMedia()
        super.onDestroy()
    }

    // ====================================================================================================
    //  PAGER vertikal: mengikuti jari, lepas = meluncur halus ke halaman terdekat (fade + geser + skala)
    // ====================================================================================================
    inner class Pager(ctx: Context) : FrameLayout(ctx) {
        val pages = ArrayList<View>()
        var pos = 0f
        var target = 0
        var dots: MpDots? = null
        var onSettle: ((Int) -> Unit)? = null
        private var an: ValueAnimator? = null
        private var downX = 0f; private var downY = 0f; private var startPos = 0f; private var startPage = 0
        private var dragging = false
        private var vt: VelocityTracker? = null
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        fun add(v: View) { pages.add(v); addView(v, FrameLayout.LayoutParams(-1, -1)); if (pages.size > 1) v.visibility = View.INVISIBLE }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); applyPos(pos) }

        fun applyPos(p: Float) {
            pos = p
            val h = height.toFloat()
            for (i in pages.indices) {
                val dd = i - p; val ad = abs(dd)
                val v = pages[i]
                if (ad >= 1f) { if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE; continue }
                if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                val f = 1f - ad
                v.translationY = dd * h * 0.42f
                v.alpha = f * f * (3f - 2f * f)
                val s = 1f - 0.07f * ad
                v.scaleX = s; v.scaleY = s
            }
            dots?.setPos(p)
        }

        fun goTo(i: Int) {
            val t = i.coerceIn(0, pages.size - 1)
            target = t
            an?.cancel()
            val a = ValueAnimator.ofFloat(pos, t.toFloat())
            a.duration = (300 + 170 * abs(t - pos)).toLong()
            a.interpolator = PathInterpolator(0.2f, 0.9f, 0.22f, 1f)
            a.addUpdateListener { applyPos(it.animatedValue as Float) }
            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(an: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(an: android.animation.Animator) { if (!cancelled) onSettle?.invoke(t) }
            })
            an = a; a.start()
            if (t >= 3) onSettle?.invoke(t)   // visualizer mulai lebih awal agar langsung hidup saat halaman masuk
        }

        private fun rubber(t: Float): Float {
            val mx = (pages.size - 1).toFloat()
            return if (t < 0f) t * 0.28f else if (t > mx) mx + (t - mx) * 0.28f else t
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; dragging = false
                    an?.cancel(); startPos = pos; startPage = pos.roundToInt()
                    vt?.recycle(); vt = VelocityTracker.obtain(); vt!!.addMovement(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.y - downY; val dx = e.x - downX
                    if (!dragging && abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) { dragging = true; downY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
                }
            }
            return dragging
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            vt?.addMovement(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) { dragging = true; downY = e.y }
                    if (dragging) applyPos(rubber(startPos - (e.y - downY) / (height * 0.48f)))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        vt!!.computeCurrentVelocity(1000)
                        val vy = vt!!.yVelocity
                        var t = pos.roundToInt()
                        if (vy < -650f && t <= startPage) t = startPage + 1
                        else if (vy > 650f && t >= startPage) t = startPage - 1
                        goTo(t)
                    }
                    dragging = false
                    vt?.recycle(); vt = null
                }
            }
            return true
        }
    }
}

// ========================================================================================================
//  LIRIK: baris aktif putih tajam, baris lain redup + blur makin jauh makin kabur; daftar meluncur halus
// ========================================================================================================
class LyricsView(ctx: Context) : FrameLayout(ctx) {
    private val dn = resources.displayMetrics.density
    private val col = object : LinearLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)) }
    }.apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(ctx).apply {
        setTextColor(Color.argb(140, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD); gravity = Gravity.CENTER
    }
    private var tvs = ArrayList<TextView>()
    private var a = FloatArray(0); private var b = FloatArray(0)
    private var offset = 0f
    private var active = -1
    private var an: ValueAnimator? = null
    private val ease = PathInterpolator(0.22f, 0.85f, 0.25f, 1f)

    init {
        addView(col, LayoutParams((235 * dn).toInt(), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = (60 * dn).toInt() })
        addView(status, LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = (30 * dn).toInt(); rightMargin = (40 * dn).toInt() })
        status.visibility = View.GONE
    }

    fun showStatus(t: String) {
        if (tvs.isNotEmpty()) return
        status.text = t; status.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setLines(lines: List<String>) {
        an?.cancel()
        col.removeAllViews(); tvs = ArrayList(); active = -1
        a = FloatArray(lines.size); b = FloatArray(lines.size)
        status.visibility = if (lines.isEmpty()) status.visibility else View.GONE
        for (l in lines) {
            val t = TextView(context).apply {
                text = l.ifEmpty { "♪" }
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                typeface = Typeface.create("sans-serif", Typeface.BOLD); letterSpacing = -0.02f
                includeFontPadding = false; setLineSpacing(0f, 0.98f)
                alpha = 0f; visibility = View.INVISIBLE
            }
            col.addView(t, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (22 * dn).toInt() })
            tvs.add(t)
        }
        col.translationY = height * 0.45f
    }

    private fun tgtA(dist: Int) = when (dist) { 0 -> 1f; 1 -> 0.42f; 2 -> 0.28f; 3 -> 0.18f; 4 -> 0.10f; else -> 0f }
    private fun tgtB(dist: Int) = min(dist, 5) * 1.35f * dn

    private fun applyLine(i: Int) {
        val t = tvs[i]
        t.alpha = a[i]
        t.visibility = if (a[i] < 0.01f) View.INVISIBLE else View.VISIBLE
        if (Build.VERSION.SDK_INT >= 31) {
            t.setRenderEffect(if (b[i] > 0.4f) RenderEffect.createBlurEffect(b[i], b[i], Shader.TileMode.DECAL) else null)
        }
    }

    private fun offsetFor(i: Int): Float {
        val t = tvs[i]
        return height * 0.45f - (t.top + t.height / 2f)
    }

    fun setActive(i: Int, animate: Boolean) {
        if (tvs.isEmpty()) return
        if (col.height == 0 || tvs[0].height == 0) { post { setActive(i, animate) }; return }
        val old = active
        active = i
        val lo = max(0, min(old, i) - 6); val hi = min(tvs.size - 1, max(old, i) + 6)
        val fa = FloatArray(hi - lo + 1) { a[lo + it] }; val fb = FloatArray(hi - lo + 1) { b[lo + it] }
        val ta = FloatArray(hi - lo + 1) { if (i < 0) tgtA(lo + it + 1) else tgtA(abs(lo + it - i)) }
        val tb = FloatArray(hi - lo + 1) { if (i < 0) tgtB(lo + it + 1) else tgtB(abs(lo + it - i)) }
        val fo = offset
        val to = if (i >= 0) offsetFor(i) else height * 0.45f
        an?.cancel()
        if (!animate) {
            for (k in 0..hi - lo) { a[lo + k] = ta[k]; b[lo + k] = tb[k]; applyLine(lo + k) }
            offset = to; col.translationY = to; return
        }
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 520; va.interpolator = ease
        va.addUpdateListener {
            val f = it.animatedValue as Float
            for (k in 0..hi - lo) { a[lo + k] = fa[k] + (ta[k] - fa[k]) * f; b[lo + k] = fb[k] + (tb[k] - fb[k]) * f; applyLine(lo + k) }
            offset = fo + (to - fo) * f; col.translationY = offset
        }
        an = va; va.start()
    }
}

// ========================================================================================================
//  Komponen gambar kecil
// ========================================================================================================

/** Bar progres tipis; menebal saat disentuh dan bisa digeser (seek dikirim saat jari diangkat). */
class MpBar(ctx: Context) : View(ctx) {
    private val dn = resources.displayMetrics.density
    private var frac = 0f
    private var dragFrac = -1f
    private var thick = 2.5f * dn
    var onSeek: ((Float) -> Unit)? = null
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(95, 255, 255, 255) }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun setFrac(v: Float) { if (dragFrac < 0f) { frac = v; invalidate() } }

    private fun grow(to: Float) {
        ValueAnimator.ofFloat(thick, to * dn).apply { duration = 160; addUpdateListener { thick = it.animatedValue as Float; invalidate() } }.start()
    }

    override fun onDraw(c: Canvas) {
        val y = (height - thick) / 2f; val fr = if (dragFrac >= 0f) dragFrac else frac
        c.drawRoundRect(0f, y, width.toFloat(), y + thick, thick / 2, thick / 2, t)
        c.drawRoundRect(0f, y, max(thick, width * fr), y + thick, thick / 2, thick / 2, f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragFrac = (e.x / width).coerceIn(0f, 1f); grow(6f); invalidate() }
            MotionEvent.ACTION_MOVE -> { dragFrac = (e.x / width).coerceIn(0f, 1f); invalidate() }
            MotionEvent.ACTION_UP -> { val v = (e.x / width).coerceIn(0f, 1f); frac = v; dragFrac = -1f; grow(2.5f); onSeek?.invoke(v) }
            MotionEvent.ACTION_CANCEL -> { dragFrac = -1f; grow(2.5f) }
        }
        return true
    }
}

/** Tombol transport: 0 sebelumnya, 1 putar, 2 jeda, 3 berikutnya (segitiga membulat, putih). */
class MpCtl(ctx: Context, var kind: Int) : View(ctx) {
    private val dn = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; pathEffect = CornerPathEffect(2.6f * dn) }
    private val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private fun tri(c: Canvas, tipX: Float, cy: Float, w: Float, h: Float, right: Boolean) {
        val path = Path()
        if (right) { path.moveTo(tipX - w, cy - h / 2); path.lineTo(tipX, cy); path.lineTo(tipX - w, cy + h / 2) }
        else { path.moveTo(tipX + w, cy - h / 2); path.lineTo(tipX, cy); path.lineTo(tipX + w, cy + h / 2) }
        path.close(); c.drawPath(path, p)
    }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        when (kind) {
            0 -> { val w = 13f * dn; val h = 19f * dn; tri(c, cx - 12.5f * dn, cy, w, h, false); tri(c, cx - 1.5f * dn, cy, w, h, false) }
            3 -> { val w = 13f * dn; val h = 19f * dn; tri(c, cx + 1.5f * dn, cy, w, h, true); tri(c, cx + 12.5f * dn, cy, w, h, true) }
            2 -> { val bw = 6.2f * dn; val bh = 20f * dn; val g = 3.6f * dn
                c.drawRoundRect(cx - g - bw, cy - bh / 2, cx - g, cy + bh / 2, 2f * dn, 2f * dn, q)
                c.drawRoundRect(cx + g, cy - bh / 2, cx + g + bw, cy + bh / 2, 2f * dn, 2f * dn, q) }
            else -> tri(c, cx + 9f * dn, cy, 18f * dn, 21f * dn, true)
        }
    }
}

/** Penanda halaman vertikal di tepi kanan panel: halaman aktif memanjang jadi pil putih. */
class MpDots(ctx: Context) : View(ctx) {
    private val dn = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var pos = 0f
    private val n = 5
    fun setPos(v: Float) { pos = v; invalidate() }
    override fun onDraw(c: Canvas) {
        val gap = 6f * dn; val w = 4f * dn
        val hs = FloatArray(n) { val k = 1f - min(1f, abs(it - pos)); 4f * dn + 12f * dn * k }
        val total = hs.sum() + gap * (n - 1)
        var y = (height - total) / 2f
        for (i in 0 until n) {
            val k = 1f - min(1f, abs(i - pos))
            p.alpha = (90 + 165 * k).toInt()
            c.drawRoundRect((width - w) / 2f, y, (width + w) / 2f, y + hs[i], w / 2, w / 2, p)
            y += hs[i] + gap
        }
    }
}

/** Jam analog: 60 garis menit abu-abu + jarum jam & menit putih tebal (tanpa jarum detik, seperti di desain). */
class MpAnalog(ctx: Context) : View(ctx) {
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); strokeCap = Paint.Cap.ROUND }
    private val hand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f
        val cx = width / 2f; val cy = height / 2f
        tick.strokeWidth = r * 0.03f
        val len = r * 0.105f; val pad = r * 0.03f
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90.0)
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val ro = r - pad; val ri = ro - len
            c.drawLine(cx + ca * ri, cy + sa * ri, cx + ca * ro, cy + sa * ro, tick)
        }
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        val h = (cal.get(Calendar.HOUR) % 12) + m / 60f
        hand.strokeWidth = r * 0.056f
        fun hnd(deg: Double, l: Float) {
            val a = Math.toRadians(deg - 90.0)
            c.drawLine(cx, cy, cx + cos(a).toFloat() * r * l, cy + sin(a).toFloat() * r * l, hand)
        }
        hnd(h * 30.0, 0.52f); hnd(m * 6.0, 0.77f)
    }
}

/** Visualizer 12 batang putih, tumbuh dari bawah panel, ujung atas membulat. */
class MpViz(ctx: Context) : View(ctx) {
    private val dn = resources.displayMetrics.density
    var bars: FloatArray = FloatArray(12)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    override fun onDraw(c: Canvas) {
        val n = 12
        val bw = 13f * dn
        val total = width * 0.83f
        val gap = (total - n * bw) / (n - 1)
        val x0 = (width - total) / 2f
        val minH = 15f * dn
        val maxH = min(height * 0.5f, 150f * dn)
        for (i in 0 until n) {
            val h = minH + bars[i].coerceIn(0f, 1f) * (maxH - minH)
            val l = x0 + i * (bw + gap)
            c.drawRoundRect(l, height - h, l + bw, height + bw, bw / 2, bw / 2, p)
        }
    }
}


/** Ikon garis untuk halaman musik. 0 hati, 1 hati terisi (putih), 2 tutup (panah bawah), 3 pengaturan player, 4 putar layar, 5 titik tiga, 6 lirik. */
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
                if (kind == 1) { fl.color = Color.WHITE; c.drawPath(p, fl) } else c.drawPath(p, st)
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
