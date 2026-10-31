package app.timeflow

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Gaya widget: satu pengaturan untuk semua widget kaca (Jam, Kalender, Cuaca, Agenda, Musik, Waktu Layar).
 * Gaya (Gelap / Terang / Otomatis), kekuatan blur, kepekatan, kilau, sudut. Perubahan disimpan di GlassPrefs dan langsung membuat latar baru.
 * Latar kaca butuh layanan aksesibilitas TimeFlow (tangkapan layar, Android 11+); tanpa itu widget memakai kaca gelap bawaan.
 */
class WidgetStyleActivity : Activity() {
    private companion object {
        const val BG = 0xFF121216.toInt()
        const val INK = 0xFFEDEDF3.toInt()
        const val MUTED = 0xFF9A9AA8.toInt()
        const val ON = 0xFFC9CCFF.toInt()
        const val OFF = 0xFF2A2A33.toInt()
    }

    private lateinit var status: TextView
    private val groups = ArrayList<() -> Unit>()       // penyegar tampilan pilihan setelah "Kembalikan bawaan"
    private val seeks = ArrayList<() -> Unit>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(ctx: Context, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // dibuka sebagai pengaturan widget (tahan lama widget): launcher meminta hasil OK + id widget, kalau tidak widget dianggap batal dipasang
        intent?.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1)?.takeIf { it >= 0 }?.let {
            setResult(RESULT_OK, android.content.Intent().putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, it))
        }
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); fitsSystemWindows = true }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_st_back); contentDescription = "Kembali"; scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val title = tv(this, 20f, INK).apply { text = "Pengaturan widget" }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(32)) }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = tv(this, 13f, MUTED).apply { setLineSpacing(0f, 1.2f) }
        col.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        section(col, "Gaya kaca")
        choice(col, listOf("Gelap", "Terang", "Otomatis"), { GlassPrefs.get(this).style }) { GlassPrefs.set(this, style = it) }
        hint(col, "Terang = kaca putih susu dengan teks gelap. Otomatis memilih gelap atau terang untuk tiap widget menurut kecerahan wallpaper di belakangnya.")

        section(col, "Blur")
        choice(col, listOf("Rendah", "Sedang", "Tinggi"), { GlassPrefs.get(this).blur }) { GlassPrefs.set(this, blur = it) }

        section(col, "Kepekatan kaca")
        slider(col, { GlassPrefs.get(this).dim }) { GlassPrefs.set(this, dim = it) }
        hint(col, "Makin besar, makin gelap (gaya gelap) atau makin putih (gaya terang) dan teks makin jelas.")

        section(col, "Kilau")
        slider(col, { GlassPrefs.get(this).sheen }) { GlassPrefs.set(this, sheen = it) }

        section(col, "Sudut")
        choice(col, listOf("Lancip", "Sedang", "Bulat"), { GlassPrefs.get(this).radius }) { GlassPrefs.set(this, radius = it) }

        val reset = tv(this, 15f, INK, true).apply {
            text = "Kembalikan bawaan"; gravity = Gravity.CENTER; setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply { setStroke(dp(1), 0xFF55555F.toInt()); cornerRadius = dp(24).toFloat() }
            setOnClickListener {
                GlassPrefs.reset(this@WidgetStyleActivity); GlassPrefs.applyNow(this@WidgetStyleActivity)
                groups.forEach { it() }; seeks.forEach { it() }
            }
        }
        col.addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28); gravity = Gravity.CENTER_HORIZONTAL })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val on = CcAccessibilityService.connected()
        val sdk = android.os.Build.VERSION.SDK_INT
        status.text = when {
            sdk < 30 -> "Perangkat ini Android 10 atau lebih lama: latar blur tidak tersedia, widget memakai kaca gelap bawaan."
            on -> "Layanan aksesibilitas TimeFlow aktif. Perubahan muncul di widget dalam beberapa detik; bila belum, geser halaman layar utama."
            else -> "Layanan aksesibilitas TimeFlow belum aktif, jadi latar blur belum bisa dibuat dan widget memakai kaca cadangan (tetap mengikuti pilihan Gelap/Terang, tanpa blur). Nyalakan di Pengaturan > Aksesibilitas > TimeFlow Control Center."
        }
        val log = (0 until CcLog.json().length()).map { CcLog.json().getString(it) }.filter { it.contains("glass") || it.contains("tangkapan") }.takeLast(4)
        if (on && log.isNotEmpty()) status.text = status.text.toString() + "\n\nCatatan latar kaca:\n" + log.joinToString("\n")
        else if (on) status.text = status.text.toString() + "\n\nBelum ada percobaan membuat latar blur. Buka layar utama sampai widget terlihat penuh, lalu kembali ke sini."
    }

    private fun section(col: LinearLayout, title: String) {
        col.addView(tv(this, 16f, INK, true).apply { text = title }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24); bottomMargin = dp(10) })
    }

    private fun hint(col: LinearLayout, t: String) {
        col.addView(tv(this, 12.5f, MUTED).apply { text = t; setLineSpacing(0f, 1.2f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    /** Deretan pilihan (satu terpilih). */
    private fun choice(col: LinearLayout, names: List<String>, current: () -> Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var repaint: () -> Unit = {}
        val views = names.mapIndexed { i, n ->
            tv(this, 14f, INK, true).apply {
                text = n; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { onPick(i); GlassPrefs.applyNow(this@WidgetStyleActivity); repaint() }
            }
        }
        fun paint() {
            val cur = current()
            views.forEachIndexed { i, v ->
                v.setTextColor(if (i == cur) 0xFF1C1C1E.toInt() else INK)
                v.background = GradientDrawable().apply { setColor(if (i == cur) ON else OFF); cornerRadius = dp(14).toFloat() }
            }
        }
        views.forEachIndexed { i, v -> row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) }) }
        repaint = { paint() }
        paint(); groups.add { paint() }
        col.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Penggeser 0..100. Disimpan saat jari diangkat (latar kaca dibuat ulang hanya sekali, bukan tiap geser). */
    private fun slider(col: LinearLayout, current: () -> Int, onSet: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sb = SeekBar(this).apply { max = 100; progress = current() }
        val label = tv(this, 14f, INK).apply { gravity = Gravity.END; minWidth = dp(48); text = "${current()}%" }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { label.text = "$p%" }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { onSet(s.progress); GlassPrefs.applyNow(this@WidgetStyleActivity) }
        })
        seeks.add { sb.progress = current(); label.text = "${current()}%" }
        row.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
