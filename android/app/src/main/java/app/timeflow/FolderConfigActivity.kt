package app.timeflow

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Pilih isi widget Folder App: nama folder, gaya ikon (kaca putih / warna asli), dan aplikasi (maks 12, urutan sesuai urutan dipilih).
 * Dibuka saat widget dipasang (configure) atau dari "Ubah isi folder". Disimpan per widget di FolderStore.
 */
class FolderConfigActivity : Activity() {
    private companion object {
        const val BG = 0xFF0E0E12.toInt()
        const val INK = 0xFFF2F2F7.toInt()
        const val MUTED = 0xFF9A9AA8.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private var wid = AppWidgetManager.INVALID_APPWIDGET_ID
    private var homeId = -1                 // >= 0: mengatur folder di Flow Launcher (bukan widget)
    private var maxApps = FolderStore.MAX
    private val picked = LinkedHashSet<String>()
    private var mono = true
    private lateinit var counter: TextView
    private lateinit var nameEt: EditText
    private lateinit var listBox: LinearLayout
    private lateinit var btnMono: TextView
    private lateinit var btnColor: TextView
    private val checks = HashMap<String, TextView>()
    private val rows = ArrayList<Pair<View, String>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun glass(r: Int): Drawable = CcGlassDrawable(dp(r).toFloat(), resources.displayMetrics.density)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wid = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        homeId = intent?.getIntExtra("home_folder", -1) ?: -1
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        if (homeId < 0 && wid == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        HomeStore.load(this)
        val hf = if (homeId >= 0) HomeStore.findFolder(homeId) else null
        if (homeId >= 0 && hf == null) { finish(); return }
        val data = if (hf != null) FolderStore.Data(hf.name, hf.apps.toList(), true) else FolderStore.get(this, wid)
        maxApps = if (hf != null) 24 else FolderStore.MAX
        picked.addAll(data.pkgs)
        mono = data.mono

        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); fitsSystemWindows = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        root.addView(content, LinearLayout.LayoutParams(MATCH, MATCH))

        content.addView(TextView(this).apply {
            text = if (homeId >= 0) "Atur folder" else "Folder App"; textSize = 22f; setTextColor(INK); includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        content.addView(TextView(this).apply {
            text = "Pilih aplikasi untuk folder ini. Ketuk ikon di widget untuk membuka aplikasinya."
            textSize = 13f; setTextColor(MUTED)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })

        nameEt = EditText(this).apply {
            setText(data.name); hint = "Nama folder (boleh kosong)"; setTextColor(INK); setHintTextColor(MUTED)
            textSize = 15f; setSingleLine(true); background = glass(16)
            setPadding(dp(16), 0, dp(16), 0)
        }
        content.addView(nameEt, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(14) })

        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnMono = TextView(this).apply { text = "Kaca putih"; textSize = 13f; gravity = Gravity.CENTER; setOnClickListener { mono = true; refreshStyle() } }
        btnColor = TextView(this).apply { text = "Warna asli"; textSize = 13f; gravity = Gravity.CENTER; setOnClickListener { mono = false; refreshStyle() } }
        styleRow.addView(btnMono, LinearLayout.LayoutParams(0, dp(40), 1f))
        styleRow.addView(btnColor, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(8) })
        content.addView(styleRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        refreshStyle()
        if (homeId >= 0) styleRow.visibility = View.GONE

        content.addView(TextView(this).apply {
            text = "Pengaturan blur & gaya kaca  ›"; textSize = 13f; setTextColor(INK); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { startActivity(Intent(this@FolderConfigActivity, WidgetStyleActivity::class.java)) }
        }, LinearLayout.LayoutParams(MATCH, dp(40)).apply { topMargin = dp(6) })

        counter = TextView(this).apply { textSize = 13f; setTextColor(MUTED) }
        content.addView(counter, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        updateCounter()

        val searchEt = EditText(this).apply {
            hint = "Cari aplikasi"; setHintTextColor(MUTED); setTextColor(INK); textSize = 15f
            setSingleLine(true); background = glass(16); setPadding(dp(16), 0, dp(16), 0)
        }
        content.addView(searchEt, LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(8) })
        searchEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                for (r in rows) r.first.visibility = if (q.isEmpty() || r.second.contains(q)) View.VISIBLE else View.GONE
            }
        })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listBox.addView(TextView(this).apply { text = "Memuat aplikasi…"; textSize = 14f; setTextColor(MUTED); setPadding(dp(8), dp(16), dp(8), dp(16)) })
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        scroll.addView(listBox, ViewGroup.LayoutParams(MATCH, WRAP))
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) })

        val save = TextView(this).apply {
            text = "Simpan"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(Color.argb(110, 255, 255, 255)); cornerRadius = dp(24).toFloat() }
            setOnClickListener { save() }
        }
        content.addView(save, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(10) })
        setContentView(root)

        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = runCatching { pm.queryIntentActivities(q, 0) }.getOrDefault(emptyList())
                .distinctBy { it.activityInfo.packageName }
                .map { Triple(it.activityInfo.packageName, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
                .sortedBy { it.second.lowercase() }
            runOnUiThread { if (!isFinishing) fill(apps) }
        }.start()
    }

    private fun refreshStyle() {
        fun st(tv: TextView, on: Boolean) {
            tv.setTextColor(INK)
            tv.background = if (on) GradientDrawable().apply { setColor(Color.argb(110, 255, 255, 255)); cornerRadius = dp(20).toFloat() } else glass(20)
        }
        st(btnMono, mono); st(btnColor, !mono)
    }

    private fun updateCounter() { counter.text = "Dipilih: ${picked.size} / $maxApps" }

    private fun mark(pkg: String) { checks[pkg]?.text = if (pkg in picked) "✓" else "○" }

    private fun toggle(pkg: String) {
        if (pkg in picked) picked.remove(pkg)
        else {
            if (picked.size >= maxApps) { Toast.makeText(this, "Maksimal $maxApps aplikasi", Toast.LENGTH_SHORT).show(); return }
            picked.add(pkg)
        }
        mark(pkg); updateCounter()
    }

    private fun fill(apps: List<Triple<String, String, Drawable?>>) {
        listBox.removeAllViews()
        rows.clear()
        for (a in apps) {
            val pkg = a.first
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            row.addView(ImageView(this).apply { setImageDrawable(a.third); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(TextView(this).apply {
                text = a.second; setTextColor(INK); textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
            val ck = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setTextColor(INK) }
            checks[pkg] = ck
            mark(pkg)
            row.addView(ck, LinearLayout.LayoutParams(dp(32), dp(32)))
            row.setOnClickListener { toggle(pkg) }
            listBox.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            rows.add(Pair(row, a.second.lowercase()))
        }
    }

    private fun save() {
        val name = nameEt.text.toString().trim()
        if (homeId >= 0) {
            val f = HomeStore.findFolder(homeId)
            if (f != null) {
                f.name = name
                f.apps = ArrayList<String>(picked)
                HomeStore.normalize(f)
                HomeStore.save(this)
                HomeStore.dirty = true
            }
            setResult(RESULT_OK)
            finish()
            return
        }
        FolderStore.set(this, wid, FolderStore.Data(name, picked.toList(), mono))
        FolderUtil.updateOne(this, wid)
        CcAccessibilityService.glassNudge()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
        finish()
    }
}
