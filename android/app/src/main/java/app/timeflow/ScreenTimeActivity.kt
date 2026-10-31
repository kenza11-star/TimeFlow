package app.timeflow

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/**
 * Halaman Waktu Layar (dibuka dengan mengetuk widget Waktu Layar). Tiga menu di bagian atas (seperti Digital Wellbeing):
 *  - Waktu pemakaian perangkat (menu utama): total, grafik Minggu-Sabtu, daftar aplikasi dengan durasi
 *  - Notifikasi diterima: jumlah notifikasi per hari dan per aplikasi (dari riwayat notifikasi TimeFlow, butuh akses notifikasi)
 *  - Frekuensi dibuka: berapa kali tiap aplikasi dibuka
 * Di menu utama tiap aplikasi bisa disembunyikan dari widget (tombol "Tampil" / "Tersembunyi"). Data hanya di perangkat.
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
        private const val CHIP = 0xFF4A3F7A.toInt()
    }

    private enum class Mode(val title: String) {
        TIME("Waktu pemakaian perangkat"), NOTIF("Notifikasi diterima"), OPENS("Frekuensi dibuka")
    }

    private var mode = Mode.TIME
    private var snap: ScreenTimeRepo.Snapshot? = null
    private var sel = -1
    private var pendingDay = -1

    private lateinit var body: LinearLayout
    private lateinit var emptyBox: LinearLayout
    private lateinit var msg: TextView
    private lateinit var grant: TextView
    private lateinit var chip: TextView
    private lateinit var total: TextView
    private lateinit var sub: TextView
    private lateinit var chart: ChartView
    private lateinit var navDate: TextView
    private lateinit var prev: TextView
    private lateinit var next: TextView
    private lateinit var note: TextView
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(ctx: Context, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun icon(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res); contentDescription = desc; scaleType = ImageView.ScaleType.CENTER
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG; window.navigationBarColor = BG
        pendingDay = intent?.getIntExtra(EXTRA_DAY, -1) ?: -1

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); fitsSystemWindows = true }

        // bilah atas: kembali | menu (Waktu pemakaian perangkat v) | perbarui
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(icon(R.drawable.ic_st_back, "Kembali") { finish() }, LinearLayout.LayoutParams(dp(48), dp(56)))
        bar.addView(View(this), LinearLayout.LayoutParams(dp(48), dp(56)))   // penyeimbang agar chip menu tetap di tengah
        val mid = LinearLayout(this).apply { gravity = Gravity.CENTER }
        chip = tv(this, 15f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(14), dp(10)); maxLines = 1
            background = GradientDrawable().apply { setColor(CHIP); cornerRadius = dp(22).toFloat() }
            setOnClickListener { showMenu() }
        }
        mid.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        bar.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(icon(R.drawable.ic_st_gear, "Pengaturan widget") { startActivity(android.content.Intent(this, WidgetStyleActivity::class.java)) }, LinearLayout.LayoutParams(dp(48), dp(56)))
        bar.addView(icon(R.drawable.ic_st_refresh, "Perbarui") { reload(true) }, LinearLayout.LayoutParams(dp(48), dp(56)))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))

        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // keadaan kosong / izin belum aktif
        emptyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(80), dp(32), dp(32)); visibility = View.GONE }
        msg = tv(this, 16f, INK).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        emptyBox.addView(msg)
        grant = tv(this, 15f, 0xFF1C1C1E.toInt(), true).apply {
            text = "Buka pengaturan"; gravity = Gravity.CENTER; setPadding(dp(24), dp(12), dp(24), dp(12)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(24).toFloat() }
            setOnClickListener { runCatching { startActivity(ScreenTimeRepo.settingsIntent(this@ScreenTimeActivity)) } }
        }
        emptyBox.addView(grant, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
        col.addView(emptyBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // isi utama
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        total = tv(this, 46f, INK).apply { gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        body.addView(total, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        sub = tv(this, 16f, INK).apply { gravity = Gravity.CENTER }
        body.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        chart = ChartView(this) { i -> sel = i; show() }
        body.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(24); marginStart = dp(16); marginEnd = dp(16) })

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        prev = tv(this, 30f, INK).apply { text = "‹"; gravity = Gravity.CENTER; contentDescription = "Hari sebelumnya"; setOnClickListener { if (sel > 0) { sel--; show() } } }
        next = tv(this, 30f, INK).apply { text = "›"; gravity = Gravity.CENTER; contentDescription = "Hari berikutnya"; setOnClickListener { sel++; show() } }
        navDate = tv(this, 17f, INK).apply { gravity = Gravity.CENTER }
        nav.addView(prev, LinearLayout.LayoutParams(dp(64), dp(48)))
        nav.addView(navDate, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(next, LinearLayout.LayoutParams(dp(64), dp(48)))
        body.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })

        note = tv(this, 13f, MUTED).apply { setPadding(dp(24), dp(8), dp(24), dp(4)); setLineSpacing(0f, 1.15f) }
        body.addView(note, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = dp(24) })
        col.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun showMenu() {
        val m = PopupMenu(this, chip)
        Mode.values().forEachIndexed { i, md -> m.menu.add(0, i, i, md.title) }
        m.setOnMenuItemClickListener { mi -> mode = Mode.values()[mi.itemId]; show(); true }
        m.show()
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

    private fun fmtValue(v: Long): String = when (mode) {
        Mode.TIME -> ScreenTimeRepo.fmt(v)
        Mode.NOTIF -> "$v notifikasi"
        Mode.OPENS -> "$v kali"
    }

    private fun show() {
        chip.text = mode.title + "  ▾"
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

        // data menurut menu
        val notif: List<List<ScreenTimeRepo.App>> = if (mode == Mode.NOTIF) days.map { ScreenTimeRepo.notifCounts(this, it.key) } else emptyList()
        val perDay: List<Long> = days.mapIndexed { i, d ->
            when (mode) { Mode.TIME -> d.total; Mode.OPENS -> d.opensTotal; Mode.NOTIF -> notif[i].sumOf { it.ms } }
        }
        val rows: List<ScreenTimeRepo.App> = when (mode) { Mode.TIME -> day.apps; Mode.OPENS -> day.opens; Mode.NOTIF -> notif[sel] }

        total.text = fmtValue(perDay[sel])
        sub.text = if (sel == todayIdx) "Hari ini" else DAY_FULL[sel]
        chart.set(perDay, sel, todayIdx, mode == Mode.TIME)
        navDate.text = dateLabel(day.key, sel)
        prev.alpha = if (sel > 0) 1f else 0.25f
        next.alpha = if (sel < todayIdx) 1f else 0.25f

        note.text = when {
            mode == Mode.TIME -> "Ketuk \"Tampil\" pada sebuah aplikasi untuk menyembunyikannya dari widget. Total waktu tetap menghitung semua aplikasi."
            mode == Mode.NOTIF && perDay.all { it == 0L } -> "Belum ada notifikasi tercatat. Dihitung dari riwayat notifikasi TimeFlow, jadi butuh akses notifikasi TimeFlow (dinyalakan di Settings) dan hanya mencatat sejak akses itu menyala."
            mode == Mode.OPENS -> "Berapa kali aplikasi dibuka (naik ke layar depan) pada hari ini."
            else -> ""
        }.replace("hari ini.", if (sel == todayIdx) "hari ini." else "hari itu.")
        note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE

        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(tv(this, 15f, MUTED).apply { text = "Tidak ada data untuk hari ini."; gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) })
            return
        }
        val pm = packageManager
        val hidden = ScreenTimeRepo.hidden(this).toMutableSet()
        for (a in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
            val ic = ImageView(this).apply {
                setImageDrawable(runCatching { pm.getApplicationIcon(a.pkg) }.getOrNull() ?: pm.defaultActivityIcon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val label = runCatching { pm.getApplicationInfo(a.pkg, 0).loadLabel(pm).toString() }.getOrDefault(a.pkg)
            names.addView(tv(this, 18f, INK).apply { text = label; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            names.addView(tv(this, 14f, MUTED).apply { text = fmtValue(a.ms) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })

            if (mode == Mode.TIME) {
                val tog = tv(this, 12f, INK).apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6)) }
                fun paint() {
                    val h = a.pkg in hidden
                    tog.text = if (h) "Tersembunyi" else "Tampil"
                    tog.setTextColor(if (h) MUTED else INK)
                    tog.background = GradientDrawable().apply { setStroke(dp(1), if (h) 0xFF55555F.toInt() else 0xFF8A8A98.toInt()); cornerRadius = dp(14).toFloat() }
                    row.alpha = if (h) 0.5f else 1f
                    tog.contentDescription = label + (if (h) ": tersembunyi dari widget" else ": tampil di widget")
                }
                paint()
                tog.setOnClickListener {
                    val h = a.pkg !in hidden
                    if (h) hidden.add(a.pkg) else hidden.remove(a.pkg)
                    ScreenTimeRepo.setHidden(this, a.pkg, h)
                    paint()
                    ScreenTimeWidget.render(applicationContext)         // widget langsung menyesuaikan
                }
                row.addView(tog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            list.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Grafik batang Minggu-Sabtu dengan garis bantu dan sumbu di kanan (jam untuk waktu, angka untuk hitungan). Ketuk batang = pilih hari. */
    private inner class ChartView(ctx: Context, private val onPick: (Int) -> Unit) : View(ctx) {
        private var vals: List<Long> = emptyList()
        private var isTime = true
        private var selIdx = 0
        private var todayIdx = 0
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDF3.toInt(); strokeWidth = dp(1).toFloat() }
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = dp(12).toFloat() }
        private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = dp(12).toFloat() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        fun set(v: List<Long>, sel: Int, today: Int, time: Boolean) { vals = v; selIdx = sel; todayIdx = today; isTime = time; invalidate() }

        override fun onDraw(c: Canvas) {
            if (vals.isEmpty()) return
            val w = width.toFloat(); val h = height.toFloat()
            val axisW = dp(40).toFloat(); val labelH = dp(30).toFloat(); val top = dp(10).toFloat()
            val plotR = w - axisW; val plotB = h - labelH
            fun unit(x: Long) = if (isTime) x / 3_600_000f else x.toFloat()
            val maxV = vals.maxOf { unit(it) }
            val steps = if (isTime) listOf(1f, 2f, 5f, 10f) else listOf(1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f)
            val step = steps.firstOrNull { maxV / it <= 4f } ?: steps.last()      // paling banyak 4 garis bantu
            val topV = max(step, ceil(maxV / step) * step)

            var v = 0f
            while (v <= topV + 0.001f) {
                val y = plotB - (plotB - top) * (v / topV)
                c.drawLine(0f, y, plotR, y, grid)
                c.drawText(if (isTime) "${v.toInt()} j" else "${v.toInt()}", plotR + dp(8), y + dp(4), axis)
                v += step
            }
            val colW = plotR / 7f
            val barW = colW * 0.62f
            val rad = dp(3).toFloat()
            for (i in 0..6) {
                val cx = colW * i + colW / 2f
                if (i <= todayIdx) {
                    val bh = max(dp(2).toFloat(), (plotB - top) * (unit(vals[i]) / topV))
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
            if (vals.isEmpty()) return false
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
