package app.timeflow

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Folder terbuka (ketuk widget Folder App): panel kaca di atas wallpaper yang diburamkan, berisi semua aplikasi folder.
 * Ketuk ikon = buka aplikasi asli, ketuk di luar panel = tutup, "Ubah isi folder" = pilih aplikasi lagi.
 */
class FolderActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wid = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        val data = FolderStore.get(this, wid)
        val pm = packageManager
        val pkgs = data.pkgs.filter { pm.getLaunchIntentForPackage(it) != null }

        runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val lp = window.attributes
            lp.dimAmount = 0.28f
            if (Build.VERSION.SDK_INT >= 31) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                lp.blurBehindRadius = 40
            }
            window.attributes = lp
        }

        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        root.setOnClickListener { finish() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = LayerDrawable(arrayOf<Drawable>(
                GradientDrawable().apply { setColor(Color.argb(70, 16, 16, 24)); cornerRadius = dp(32).toFloat() },
                CcGlassDrawable(dp(32).toFloat(), d)))
            isClickable = true
        }

        panel.addView(TextView(this).apply {
            text = data.name; textSize = 20f; setTextColor(Color.WHITE); includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(2f, 0f, 1f, 0x59000000)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val col = 4
        pkgs.chunked(col).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { pkg -> line.addView(cell(pkg, data.mono), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            repeat(col - row.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            panel.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }
        if (pkgs.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "Folder masih kosong."; textSize = 14f; setTextColor(0xB3FFFFFF.toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }

        val edit = TextView(this).apply {
            text = "Ubah isi folder"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = CcGlassDrawable(dp(20).toFloat(), d)
            setOnClickListener {
                startActivity(Intent(this@FolderActivity, FolderConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid))
                finish()
            }
        }
        panel.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(18); gravity = Gravity.END })

        root.addView(panel, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
    }

    private fun cell(pkg: String, mono: Boolean): LinearLayout {
        val pm = packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(FolderIcons.tile(this@FolderActivity, pkg, mono, false, 192))
        }, LinearLayout.LayoutParams(dp(60), dp(60)))
        box.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
            setShadowLayer(2f, 0f, 1f, 0x59000000)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        box.setOnClickListener {
            val li = pm.getLaunchIntentForPackage(pkg)
            if (li != null) { li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); runCatching { startActivity(li) } }
            finish()
        }
        return box
    }
}
