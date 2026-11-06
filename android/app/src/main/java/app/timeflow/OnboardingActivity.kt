package app.timeflow

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs

/**
 * Pengenalan saat pertama kali membuka Flow Launcher: intro, gaya ikon, kaca & blur, mode game, lalu menyiapkan ikon.
 * Semua pilihan langsung tersimpan ke pengaturan yang sama dengan menu Pengaturan (bisa diubah lagi kapan saja).
 * Ringan: tanpa pustaka tambahan, animasi hanya perpindahan halaman, dan penyiapan ikon berjalan di thread latar.
 */
class OnboardingActivity : Activity() {
    companion object {
        private const val SP = "timeflow_onboard"
        fun done(c: Context): Boolean = c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("done", false)
        fun markDone(c: Context) { c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean("done", true).apply() }
        private const val LAST = 4
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d + 0.5f).toInt()

    private lateinit var stage: FrameLayout
    private lateinit var dots: LinearLayout
    private lateinit var next: TextView
    private lateinit var skip: TextView
    private lateinit var later: TextView
    private val pages = ArrayList<View>()
    private var cur = 0
    private var busy = false
    private var mono = false
    private var glassChanged = false
    private var prepared = false
    private var preparing = false
    private var previewPkgs: List<String> = emptyList()
    private val tiles = ArrayList<ImageView>()
    private lateinit var track: FrameLayout
    private lateinit var fill: View
    private lateinit var prepTitle: TextView
    private lateinit var prepBody: TextView
    private lateinit var prepCount: TextView
    private lateinit var glassPrev: GlassPreview
    private lateinit var gd: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        mono = getSharedPreferences(HomeActivity.SP, Context.MODE_PRIVATE).getBoolean("mono", false)

        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B1226.toInt(), 0xFF1D2A6B.toInt(), 0xFF6A2C91.toInt()))
        root.addView(glow(0x553E7BFF, 320), FrameLayout.LayoutParams(dp(320), dp(320), Gravity.TOP or Gravity.START).apply { leftMargin = -dp(110); topMargin = -dp(60) })
        root.addView(glow(0x44FF7AB8, 360), FrameLayout.LayoutParams(dp(360), dp(360), Gravity.BOTTOM or Gravity.END).apply { rightMargin = -dp(130); bottomMargin = -dp(90) })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.setOnApplyWindowInsetsListener { _, ins -> col.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }

        val top = FrameLayout(this)
        skip = tv("Lewati", 14f, 0xB3FFFFFF.toInt(), false).apply {
            setPadding(dp(20), dp(14), dp(24), dp(14))
            setOnClickListener { finishOnboarding() }
        }
        top.addView(skip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top, LinearLayout.LayoutParams(MATCH, dp(52)))

        stage = FrameLayout(this)
        col.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        col.addView(dots, LinearLayout.LayoutParams(MATCH, dp(24)))

        next = tv("Mulai", 16f, 0xFF101426.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(28).toFloat() }
            setOnClickListener { goNext() }
        }
        col.addView(next, LinearLayout.LayoutParams(MATCH, dp(56)).apply { setMargins(dp(28), dp(8), dp(28), dp(6)) })

        later = tv("Jadikan launcher utama", 14f, 0xE6FFFFFF.toInt(), false).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { HomeUtil.requestHome(this@OnboardingActivity, 77) }
        }
        col.addView(later, LinearLayout.LayoutParams(MATCH, dp(44)).apply { bottomMargin = dp(10) })

        pages.add(pageIntro()); pages.add(pageIcons()); pages.add(pageGlass()); pages.add(pageGame()); pages.add(pagePrepare())
        for ((i, p) in pages.withIndex()) {
            p.visibility = if (i == 0) View.VISIBLE else View.GONE
            stage.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        for (i in pages.indices) dots.addView(View(this), LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(4), 0, dp(4), 0) })

        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) * 1.5f && abs(vx) > 700f && abs(dx) > dp(60)) {
                    if (dx < 0) goNext() else goTo(cur - 1)
                    return true
                }
                return false
            }
        })
        setContentView(root)
        updateChrome()
        onShown(0)

        // daftar aplikasi dimuat di latar: dipakai pratinjau ikon dan penyiapan
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            previewPkgs = runCatching { AppRepo.visible(this).take(4).map { it.pkg } }.getOrDefault(emptyList())
            refreshTiles()
        }.start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cur > 0 && !busy) goTo(cur - 1) else finish()
    }

    // ---------------- navigasi ----------------
    private fun goNext() {
        if (cur >= LAST) { if (prepared) finishOnboarding(); return }
        goTo(cur + 1)
    }

    private fun goTo(i: Int) {
        if (i == cur || i < 0 || i > LAST || busy) return
        val fwd = i > cur
        val old = pages[cur]
        val nw = pages[i]
        val w = stage.width.toFloat().coerceAtLeast(1f)
        busy = true
        nw.visibility = View.VISIBLE
        nw.alpha = 0f
        nw.translationX = if (fwd) w * 0.3f else -w * 0.3f
        nw.animate().translationX(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        old.animate().translationX(if (fwd) -w * 0.3f else w * 0.3f).alpha(0f).setDuration(240).withEndAction {
            old.visibility = View.GONE
            old.translationX = 0f
            busy = false
        }.start()
        cur = i
        updateChrome()
        onShown(i)
    }

    private fun onShown(i: Int) {
        if (i == 0) {
            val t = pages[0]
            t.scaleX = 0.92f; t.scaleY = 0.92f
            t.animate().scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(DecelerateInterpolator()).start()
        }
        if (i == LAST) prepare()
    }

    private fun updateChrome() {
        for (i in 0 until dots.childCount) {
            val v = dots.getChildAt(i)
            val sel = i == cur
            v.background = GradientDrawable().apply { setColor(if (sel) Color.WHITE else 0x55FFFFFF); cornerRadius = dp(3).toFloat() }
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.width = if (sel) dp(18) else dp(6)
            v.layoutParams = lp
        }
        skip.visibility = if (cur < LAST) View.VISIBLE else View.INVISIBLE
        next.text = when {
            cur == 0 -> "Mulai"
            cur < LAST -> "Lanjut"
            prepared -> "Buka Flow Launcher"
            else -> "Menyiapkan…"
        }
        next.alpha = if (cur == LAST && !prepared) 0.55f else 1f
        later.visibility = if (cur == LAST && prepared && !HomeUtil.isDefaultHome(this)) View.VISIBLE else View.GONE
    }

    private fun finishOnboarding() {
        markDone(this)
        if (glassChanged) Thread { runCatching { GlassPrefs.applyNow(applicationContext) } }.start()
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---------------- halaman ----------------
    private fun pageIntro(): View {
        val p = page(Gravity.CENTER)
        p.addView(tv("Flow", 72f, Color.WHITE, false).apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); letterSpacing = 0.04f; gravity = Gravity.CENTER })
        p.addView(tv("Launcher yang ringan, bening, dan nyaman.", 16f, 0xCCFFFFFF.toInt(), false).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(28)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (t in listOf("Ringan", "Kaca bening", "Bisa diatur")) row.addView(label(t).apply { setPadding(dp(14), dp(8), dp(14), dp(8)); background = GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = dp(18).toFloat() } }, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(4), 0, dp(4), 0) })
        p.addView(row)
        return p
    }

    private fun pageIcons(): View {
        val p = page(Gravity.CENTER_HORIZONTAL)
        p.addView(tv("Gaya ikon", 28f, Color.WHITE, true))
        p.addView(tv("Pilih tampilan ikon aplikasimu. Bisa diubah lagi kapan saja.", 15f, 0xB3FFFFFF.toInt(), false).apply { setPadding(0, dp(8), 0, dp(28)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (i in 0 until 4) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            tiles.add(iv)
            row.addView(iv, LinearLayout.LayoutParams(dp(68), dp(68)).apply { setMargins(dp(8), 0, dp(8), 0) })
        }
        p.addView(row)
        p.addView(View(this), LinearLayout.LayoutParams(MATCH, dp(32)))
        p.addView(chips(listOf("Warna asli", "Satu warna"), if (mono) 1 else 0) { sel ->
            mono = sel == 1
            getSharedPreferences(HomeActivity.SP, Context.MODE_PRIVATE).edit().putBoolean("mono", mono).apply()
            Thread { refreshTiles() }.start()
        })
        return p
    }

    private fun pageGlass(): View {
        val p = page(Gravity.CENTER_HORIZONTAL)
        p.addView(tv("Kaca & blur", 28f, Color.WHITE, true))
        p.addView(tv("Atur seberapa buram dan terang panel kaca di widget dan Control Center.", 15f, 0xB3FFFFFF.toInt(), false).apply { setPadding(0, dp(8), 0, dp(20)) })
        glassPrev = GlassPreview(this)
        p.addView(glassPrev, LinearLayout.LayoutParams(dp(300), dp(180)))
        val g = GlassPrefs.get(this)
        glassPrev.set(g.blur, g.style, g.dim, g.sheen, g.radius)
        p.addView(label("Kekuatan blur").apply { setPadding(0, dp(18), 0, dp(8)) })
        p.addView(chips(listOf("Lembut", "Sedang", "Tebal"), g.blur) { sel ->
            GlassPrefs.set(this, blur = sel); glassChanged = true
            GlassPrefs.get(this).let { glassPrev.set(sel, it.style, it.dim, it.sheen, it.radius) }
        })
        p.addView(label("Warna kaca").apply { setPadding(0, dp(14), 0, dp(8)) })
        p.addView(chips(listOf("Gelap", "Terang", "Otomatis"), g.style) { sel ->
            GlassPrefs.set(this, style = sel); glassChanged = true
            GlassPrefs.get(this).let { glassPrev.set(it.blur, sel, it.dim, it.sheen, it.radius) }
        })
        return p
    }

    private fun pageGame(): View {
        val p = page(Gravity.CENTER_HORIZONTAL)
        p.addView(tv("Ringan saat main game", 28f, Color.WHITE, true))
        p.addView(tv("Flow berhenti menggambar dan berjalan di latar saat game ada di depan, jadi game tetap lancar.", 15f, 0xB3FFFFFF.toInt(), false).apply { setPadding(0, dp(8), 0, dp(24)) })
        for (t in listOf("Notch dan Control Center istirahat", "Mikrofon AI dilepas untuk voice chat game", "Widget berhenti memperbarui di latar", "Menyala lagi otomatis setelah keluar game")) {
            p.addView(tv("•  $t", 15f, 0xE6FFFFFF.toInt(), false).apply { setPadding(0, dp(5), 0, dp(5)) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0x26FFFFFF); cornerRadius = dp(20).toFloat() }
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        row.addView(tv("Mode game otomatis", 16f, Color.WHITE, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(Switch(this).apply {
            isChecked = GameMode.enabled(this@OnboardingActivity)
            setOnCheckedChangeListener { _, v -> GameMode.setEnabled(this@OnboardingActivity, v) }
        })
        p.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(24) })
        return p
    }

    private fun pagePrepare(): View {
        val p = page(Gravity.CENTER)
        prepTitle = tv("Menyiapkan ikon", 28f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        prepBody = tv("Mengoptimalkan ikon aplikasi supaya layar utama terasa mulus.", 15f, 0xB3FFFFFF.toInt(), false).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(28)) }
        p.addView(prepTitle); p.addView(prepBody)
        track = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(3).toFloat() } }
        fill = View(this).apply { background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(3).toFloat() } }
        track.addView(fill, FrameLayout.LayoutParams(0, MATCH))
        p.addView(track, LinearLayout.LayoutParams(MATCH, dp(6)))
        prepCount = tv("", 13f, 0x99FFFFFF.toInt(), false).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        p.addView(prepCount)
        return p
    }

    // ---------------- penyiapan ikon (thread latar) ----------------
    private fun prepare() {
        if (preparing || prepared) return
        preparing = true
        val m = mono
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val apps = runCatching { AppRepo.visible(this) }.getOrDefault(emptyList())
            val n = apps.size
            var i = 0
            for (e in apps) {
                runCatching { FolderIcons.tile(this, e.pkg, m, false) }
                i++
                if (i % 3 == 0 || i == n) {
                    val label = e.label
                    val c = i
                    runOnUiThread { if (!isDestroyed) setProgress(c / n.toFloat(), "$label  ($c/$n)") }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                prepared = true; preparing = false
                setProgress(1f, "")
                prepTitle.text = "Siap!"
                prepBody.text = "Semua ikon sudah dioptimalkan. Selamat menikmati Flow Launcher."
                updateChrome()
            }
        }.start()
    }

    private fun setProgress(f: Float, text: String) {
        val lp = fill.layoutParams
        lp.width = (track.width * f.coerceIn(0f, 1f)).toInt()
        fill.layoutParams = lp
        prepCount.text = text
    }

    private fun refreshTiles() {
        val bmps = previewPkgs.map { runCatching { FolderIcons.tile(this, it, mono, false) }.getOrNull() }
        runOnUiThread { tiles.forEachIndexed { i, iv -> bmps.getOrNull(i)?.let { iv.setImageBitmap(it) } } }
    }

    // ---------------- potongan tampilan ----------------
    private fun page(g: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = g
        setPadding(dp(28), dp(8), dp(28), dp(8))
    }

    private fun tv(t: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun label(t: String) = tv(t, 13f, 0xB3FFFFFF.toInt(), false).apply {
        background = null
    }

    private fun glow(color: Int, size: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(size / 2).toFloat()
            colors = intArrayOf(color, 0x00000000)
        }
    }

    /** Baris pilihan (chip) bergaya segmen; [onPick] dipanggil dengan indeks yang dipilih. */
    private fun chips(opts: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            for ((i, v) in views.withIndex()) {
                val on = i == sel
                v.background = GradientDrawable().apply { setColor(if (on) Color.WHITE else 0x26FFFFFF); cornerRadius = dp(22).toFloat() }
                v.setTextColor(if (on) 0xFF101426.toInt() else Color.WHITE)
            }
        }
        for ((i, o) in opts.withIndex()) {
            val v = tv(o, 14f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { paint(i); onPick(i) }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        paint(selected)
        return row
    }
}
