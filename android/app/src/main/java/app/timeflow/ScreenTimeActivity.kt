package app.timeflow

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/**
 * Halaman Waktu Layar (dibuka dengan mengetuk widget Waktu Layar): total hari terpilih, grafik Minggu-Sabtu (ketuk batang atau pakai panah),
 * dan daftar semua aplikasi hari itu dengan ikon, nama, dan durasi. Data dari ScreenTimeRepo (UsageStatsManager, hanya di perangkat).
 * Tanpa pustaka tambahan: tampilan dibuat dengan kode.
 */
class ScreenTimeActivity : Activity() {
    companion object {
        const val EXTRA_DAY = "day"
        private val DAY_FULL = arrayOf("Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu")
        private val DAY_SHORT = arrayOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")
        private val MONTH_SHORT = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
        private const val BG = 0xFF121216.toInt()
        private const val BAR = 0xFFF2F2FA.toInt()
        private const val BAR_SEL = 0xFFC9CCFF.toInt()
        private const val INK = 0xFFEDEDF3.toInt()
        private const val MUTED = 0xFF9A9AA8.toInt()
    }

    private var snap: ScreenTimeRepo.Snapshot? = null
    private var sel = -1
    private var pendingDay = -1

    private lateinit var body: LinearLayout
    private lateinit var emptyBox: LinearLayout
    private lateinit var msg: TextView
    private lateinit var grant: TextView
    private lateinit var total: TextView
    private lateinit var sub: TextView
    private lateinit var chart: ChartView
    private lateinit var navDate: TextView
    private lateinit var prev: TextView
    private lateinit var next: TextView
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(ctx: Context, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG; window.navigationBarColor = BG
        pendingDay = intent?.getIntExtra(EXTRA_DAY, -1) ?: -1

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); fitsSystemWindows = true }

        // bilah atas
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = text(this, 26f, INK).apply { text = "←"; contentDescription = "Kembali"; gravity = Gravity.CENTER; setOnClickListener { finish() } }
        bar.addView(back, LinearLayout.LayoutParams(dp(56), dp(56)))
        val title = text(this, 20f, INK).apply { text = "Waktu layar" }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val refresh = text(this, 24f, INK).apply { text = "↻"; contentDescription = "Perbarui"; gravity = Gravity.CENTER; setOnClickListener { reload(true) } }
        bar.addView(refresh, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // keadaan kosong / izin belum aktif
        emptyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(80), dp(32), dp(32)); visibility = View.GONE }
        msg = text(this, 16f, INK).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        emptyBox.addView(msg)
        grant = text(this, 15f, 0xFF1C1C1E.toInt(), true).apply {
            text = "Buka pengaturan"; gravity = Gravity.CENTER; setPadding(dp(24), dp(12), dp(24), dp(12)); visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(24).toFloat() }
            setOnClickListener { runCatching { startActivity(ScreenTimeRepo.settingsIntent(this@ScreenTimeActivity)) } }
        }
        emptyBox.addView(grant, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        col.addView(emptyBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // isi utama
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        total = text(this, 46f, INK).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        body.addView(total, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        sub = text(this, 16f, INK).apply { gravity = Gravity.CENTER }
        body.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        chart = ChartView(this) { i -> sel = i; show() }
        body.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(24); marginStart = dp(16); marginEnd = dp(16) })

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prev = text(this, 26f, INK).apply { text = "‹"; gravity = Gravity.CENTER; contentDescription = "Hari sebelumnya"; setOnClickListener { if (sel > 0) { sel--; show() } } }
        next = text(this, 26f, INK).apply { text = "›"; gravity = Gravity.CENTER; contentDescription = "Hari berikutnya"; setOnClickListener { sel++; show() } }
        navDate = text(this, 17f, INK).apply { gravity = Gravity.CENTER }
        nav.addView(prev, LinearLayout.LayoutParams(dp(64), dp(48)))
        nav.addView(navDate, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(next, LinearLayout.LayoutParams(dp(64), dp(48)))
        body.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(24) })
        col.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDay = intent.getIntExtra(EXTRA_DAY, -1)
        snap = ScreenTimeRepo.load(this); show()
    }

    override fun onResume() {
        super.onResume()
        reload(false)
    }

    /** Muat dari cache, tampilkan, lalu hitung ulang di latar bila data sudah basi (atau [force]). */
    private fun reload(force: Boolean) {
        snap = ScreenTimeRepo.load(this); show()
        ScreenTimeWidget.refreshAsync(applicationContext, force) {
            runOnUiThread { if (!isFinishing && !isDestroyed) { snap = ScreenTimeRepo.load(this); show() } }
        }
    }

    private fun dateLabel(key: String, idx: Int): String {
        val m = key.substring(5, 7).toIntOrNull() ?: 1
        val d = key.substring(8, 10).toIntOrNull() ?: 1
        return DAY_SHORT[idx.coerceIn(0, 6)] + ", " + d + " " + MONTH_SHORT[(m - 1).coerceIn(0, 11)]
    }

    private fun show() {
        val s = snap
        val st = ScreenTimeRepo.state(this, s)
        val ok = st == ScreenTimeRepo.State.OK && s != null
        body.visibility = if (ok) View.VISIBLE else View.GONE
        emptyBox.visibility = if (ok) View.GONE else View.VISIBLE
        if (!ok) {
            msg.text = when (st) {
                ScreenTimeRepo.State.NO_ACCESS -> "Nyalakan Akses penggunaan untuk TimeFlow agar waktu layar bisa ditampilkan.\n\nData hanya diproses di perangkat ini dan tidak diunggah."
                ScreenTimeRepo.State.UNSUPPORTED -> "Data penggunaan tidak tersedia di perangkat ini."
                ScreenTimeRepo.State.LOADING -> "Memuat data penggunaan…"
                else -> "Belum ada data penggunaan."
            }
            grant.visibility = if (st == ScreenTimeRepo.State.NO_ACCESS) View.VISIBLE else View.GONE
            return
        }
        val days = s!!.days
        val todayKey = ScreenTimeRepo.keyOf(System.currentTimeMillis())
        val todayIdx = days.indexOfFirst { it.key == todayKey }.let { if (it < 0) days.lastIndex else it }
        if (pendingDay in 0..todayIdx) { sel = pendingDay; pendingDay = -1 } else if (sel !in 0..todayIdx) sel = todayIdx
        val day = days[sel]

        total.text = ScreenTimeRepo.fmt(day.total)
        sub.text = if (sel == todayIdx) "Hari ini" else DAY_FULL[sel]
        chart.set(days, sel, todayIdx)
        navDate.text = dateLabel(day.key, sel)
        prev.alpha = if (sel > 0) 1f else 0.25f
        next.alpha = if (sel < todayIdx) 1f else 0.25f
        next.isEnabled = sel < todayIdx

        list.removeAllViews()
        if (day.apps.isEmpty()) {
            list.addView(text(this, 15f, MUTED).apply { text = "Tidak ada aplikasi yang tercatat."; gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) })
            return
        }
        val pm = packageManager
        for (a in day.apps) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
            val icon = ImageView(this).apply {
                setImageDrawable(runCatching { pm.getApplicationIcon(a.pkg) }.getOrNull() ?: pm.defaultActivityIcon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val label = runCatching { pm.getApplicationInfo(a.pkg, 0).loadLabel(pm).toString() }.getOrDefault(a.pkg)
            names.addView(text(this, 18f, INK).apply { text = label; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            names.addView(text(this, 14f, MUTED).apply { text = ScreenTimeRepo.fmt(a.ms) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            list.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Grafik batang Minggu-Sabtu dengan garis bantu dan sumbu jam di kanan. Ketuk batang = pilih hari. */
    private inner class ChartView(ctx: Context, private val onPick: (Int) -> Unit) : View(ctx) {
        private var days: List<ScreenTimeRepo.Day> = emptyList()
        private var selIdx = 0
        private var todayIdx = 0
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDF3.toInt(); strokeWidth = dp(1).toFloat() }
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = dp(12).toFloat() }
        private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(12).toFloat() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        fun set(d: List<ScreenTimeRepo.Day>, sel: Int, today: Int) { days = d; selIdx = sel; todayIdx = today; invalidate() }

        override fun onDraw(c: Canvas) {
            if (days.isEmpty()) return
            val w = width.toFloat(); val h = height.toFloat()
            val axisW = dp(40).toFloat(); val labelH = dp(30).toFloat(); val top = dp(10).toFloat()
            val plotR = w - axisW; val plotB = h - labelH
            val maxH = days.maxOf { it.total } / 3_600_000f
            val step = listOf(1f, 2f, 5f, 10f).firstOrNull { maxH / it <= 4f } ?: 20f      // paling banyak 4 garis bantu
            val topH = max(step, ceil(maxH / step) * step)

            var v = 0f
            while (v <= topH + 0.001f) {
                val y = plotB - (plotB - top) * (v / topH)
                c.drawLine(0f, y, plotR, y, grid)
                c.drawText("${v.toInt()} j", plotR + dp(8), y + dp(4), axis)
                v += step
            }
            val colW = plotR / 7f
            val barW = colW * 0.62f
            val rad = dp(3).toFloat()
            for (i in 0..6) {
                val cx = colW * i + colW / 2f
                if (i <= todayIdx) {
                    val bh = max(dp(2).toFloat(), (plotB - top) * (days[i].total / 3_600_000f / topH))
                    fill.color = if (i == selIdx) BAR_SEL else BAR
                    val p = Path().apply { addRoundRect(RectF(cx - barW / 2f, plotB - bh, cx + barW / 2f, plotB), floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW) }
                    c.drawPath(p, fill)
                }
                lab.color = if (i == selIdx) 0xFFFFFFFF.toInt() else MUTED
                lab.typeface = if (i == selIdx) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
                c.drawText(DAY_FULL[i], cx, h - dp(8), lab)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (days.isEmpty()) return false
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val colW = (width - dp(40)) / 7f
                val i = (e.x / colW).toInt()
                if (i in 0..todayIdx) onPick(i)
                performClick()
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
