package app.timeflow

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UI sendiri Flow Launcher (tanpa dialog/menu bawaan sistem): dialog, input, menu popup, dan toast bergaya kaca dengan animasi.
 * Semuanya ditambahkan sebagai overlay di atas isi Activity, jadi bisa dipakai di peluncur maupun Activity lain.
 */
object FlowUi {
    class Item(val label: String, val icon: Int, val action: () -> Unit)

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val main = Handler(Looper.getMainLooper())
    private val stack = ArrayList<() -> Unit>()

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    private fun content(a: Activity): ViewGroup = a.findViewById<ViewGroup>(android.R.id.content)

    fun isShowing(): Boolean = stack.isNotEmpty()

    /** Tutup overlay teratas (untuk tombol kembali). true bila ada yang ditutup. */
    fun dismissTop(): Boolean {
        val c = stack.lastOrNull() ?: return false
        c()
        return true
    }

    private fun panelBg(host: View, r: Float, a: Activity): Drawable {
        if (a is HomeActivity && HomeWall.blur != null) return GlassPanelDrawable(host, r, 1.4f, 30, 1.8f)
        return GradientDrawable().apply {
            setColor(0xF01E232B.toInt()); cornerRadius = r
            setStroke(maxOf(1, dp(a, 1)), 0x33FFFFFF)
        }
    }

    /** Ikon putih 24dp: 0 pensil, 1 urutan A-Z, 2 daftar+tambah, 3 silang, 4 info, 5 tempat sampah, 6 pengaturan, 7 folder. */
    fun icon(c: Context, kind: Int): Bitmap {
        val s = dp(c, 24)
        val f = s.toFloat()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = f * 0.09f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val fl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        when (kind) {
            0 -> {
                val p = Path()
                p.moveTo(f * 0.18f, f * 0.82f); p.lineTo(f * 0.26f, f * 0.56f); p.lineTo(f * 0.66f, f * 0.16f)
                p.lineTo(f * 0.84f, f * 0.34f); p.lineTo(f * 0.44f, f * 0.74f); p.close()
                cv.drawPath(p, st)
                cv.drawLine(f * 0.55f, f * 0.27f, f * 0.73f, f * 0.45f, st)
            }
            1 -> {
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = f * 0.42f; typeface = Typeface.DEFAULT_BOLD }
                cv.drawText("A", f * 0.06f, f * 0.44f, tp)
                cv.drawText("Z", f * 0.06f, f * 0.92f, tp)
                cv.drawLine(f * 0.78f, f * 0.16f, f * 0.78f, f * 0.84f, st)
                cv.drawLine(f * 0.64f, f * 0.70f, f * 0.78f, f * 0.84f, st)
                cv.drawLine(f * 0.92f, f * 0.70f, f * 0.78f, f * 0.84f, st)
            }
            2 -> {
                cv.drawLine(f * 0.1f, f * 0.25f, f * 0.62f, f * 0.25f, st)
                cv.drawLine(f * 0.1f, f * 0.5f, f * 0.62f, f * 0.5f, st)
                cv.drawLine(f * 0.1f, f * 0.75f, f * 0.4f, f * 0.75f, st)
                cv.drawLine(f * 0.8f, f * 0.52f, f * 0.8f, f * 0.88f, st)
                cv.drawLine(f * 0.62f, f * 0.7f, f * 0.98f, f * 0.7f, st)
            }
            3 -> {
                cv.drawLine(f * 0.22f, f * 0.22f, f * 0.78f, f * 0.78f, st)
                cv.drawLine(f * 0.78f, f * 0.22f, f * 0.22f, f * 0.78f, st)
            }
            4 -> {
                cv.drawCircle(f * 0.5f, f * 0.5f, f * 0.4f, st)
                cv.drawCircle(f * 0.5f, f * 0.32f, f * 0.05f, fl)
                cv.drawLine(f * 0.5f, f * 0.46f, f * 0.5f, f * 0.72f, st)
            }
            5 -> {
                cv.drawLine(f * 0.2f, f * 0.28f, f * 0.8f, f * 0.28f, st)
                val h = Path()
                h.moveTo(f * 0.4f, f * 0.28f); h.lineTo(f * 0.4f, f * 0.18f); h.lineTo(f * 0.6f, f * 0.18f); h.lineTo(f * 0.6f, f * 0.28f)
                cv.drawPath(h, st)
                val bd = Path()
                bd.moveTo(f * 0.27f, f * 0.36f); bd.lineTo(f * 0.33f, f * 0.84f); bd.lineTo(f * 0.67f, f * 0.84f); bd.lineTo(f * 0.73f, f * 0.36f)
                cv.drawPath(bd, st)
            }
            6 -> {
                for (i in 0..2) {
                    val y = f * (0.2f + i * 0.3f)
                    cv.drawLine(f * 0.1f, y, f * 0.9f, y, st)
                    cv.drawCircle(f * (0.3f + ((i * 2) % 3) * 0.2f), y, f * 0.09f, fl)
                }
            }
            8 -> {
                cv.drawLine(f * 0.2f, f * 0.8f, f * 0.8f, f * 0.2f, st)
                cv.drawLine(f * 0.8f, f * 0.2f, f * 0.8f, f * 0.46f, st)
                cv.drawLine(f * 0.8f, f * 0.2f, f * 0.54f, f * 0.2f, st)
                cv.drawLine(f * 0.2f, f * 0.8f, f * 0.2f, f * 0.54f, st)
                cv.drawLine(f * 0.2f, f * 0.8f, f * 0.46f, f * 0.8f, st)
            }
            else -> {
                val p = Path()
                p.moveTo(f * 0.1f, f * 0.28f); p.lineTo(f * 0.4f, f * 0.28f); p.lineTo(f * 0.48f, f * 0.38f); p.lineTo(f * 0.9f, f * 0.38f)
                p.lineTo(f * 0.9f, f * 0.8f); p.lineTo(f * 0.1f, f * 0.8f); p.close()
                cv.drawPath(p, st)
            }
        }
        return b
    }

    private fun hideKb(v: View) {
        (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Dialog kaca dengan judul, pesan, [extra] (mis. kolom isian) dan tombol-tombol; tombol [primary] ditonjolkan. */
    fun dialog(
        a: Activity, title: String, message: String?, buttons: List<Pair<String, () -> Unit>>,
        primary: Int = 0, onDismiss: (() -> Unit)? = null, extra: View? = null, top: Boolean = false
    ) {
        val host = content(a)
        val ov = FrameLayout(a)
        ov.setBackgroundColor(Color.argb(130, 0, 0, 0))
        ov.isClickable = true
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(a, 24), dp(a, 24), dp(a, 24), dp(a, 14)); isClickable = true
        }
        card.background = panelBg(card, dp(a, 28).toFloat(), a)
        card.addView(TextView(a).apply {
            text = title; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        if (message != null) {
            card.addView(TextView(a).apply {
                text = message; textSize = 15f; setTextColor(Color.argb(215, 255, 255, 255)); setLineSpacing(0f, 1.12f)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(a, 10) })
        }
        if (extra != null) card.addView(extra, LinearLayout.LayoutParams(MATCH, dp(a, 52)).apply { topMargin = dp(a, 14) })

        var closed = false
        var closer: (() -> Unit)? = null
        fun close(after: (() -> Unit)?) {
            if (closed) return
            closed = true
            closer?.let { stack.remove(it) }
            hideKb(ov)
            card.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).setDuration(150).start()
            ov.animate().alpha(0f).setDuration(170).withEndAction {
                host.removeView(ov)
                after?.invoke()
                onDismiss?.invoke()
            }.start()
        }
        closer = { close(null) }
        stack.add(closer!!)

        buttons.forEachIndexed { i, btn ->
            val tv = TextView(a).apply {
                text = btn.first; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(if (i == primary) Color.WHITE else Color.argb(220, 255, 255, 255))
                typeface = Typeface.create(if (i == primary) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                if (i == primary) background = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = dp(a, 24).toFloat() }
                setOnClickListener { close(btn.second) }
            }
            card.addView(tv, LinearLayout.LayoutParams(MATCH, dp(a, 48)).apply { topMargin = dp(a, if (i == 0) 18 else 6) })
        }
        val w = minOf((host.width * 0.88f).toInt(), dp(a, 340))
        val lp = FrameLayout.LayoutParams(if (w > 0) w else dp(a, 320), WRAP, if (top) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER)
        if (top) lp.topMargin = dp(a, 110)
        ov.addView(card, lp)
        host.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.alpha = 0f
        card.scaleX = 0.9f; card.scaleY = 0.9f
        ov.animate().alpha(1f).setDuration(170).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(OvershootInterpolator(1.5f)).start()
    }

    /** Dialog isian teks (mis. ganti nama). */
    fun input(a: Activity, title: String, initial: String, hint: String, okLabel: String, onOk: (String) -> Unit) {
        val et = EditText(a).apply {
            setText(initial); this.hint = hint; setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 17f
            setSingleLine(true); setSelectAllOnFocus(true); setPadding(dp(a, 18), 0, dp(a, 18), 0)
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(a, 18).toFloat() }
        }
        dialog(a, title, null, listOf(okLabel to { onOk(et.text.toString()) }, "Batal" to {}), 0, null, et, true)
        et.postDelayed({
            et.requestFocus()
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, 0)
        }, 320)
    }

    /**
     * Menu popup kaca di dekat [anchor] (koordinat jendela) atau di tengah layar bila [centered]. Mengembalikan fungsi penutup.
     * Setiap baris: ikon + label; mengetuk baris menutup menu lalu menjalankan aksinya.
     */
    /** Pengaturan peluncur: sisipkan "Asisten AI" tepat setelah "Layar tersembunyi". */
    private fun withAi(a: Activity, items: List<Item>): List<Item> {
        val i = items.indexOfFirst { it.label == "Layar tersembunyi" }
        if (i < 0 || items.any { it.label == "Asisten AI" }) return items
        val out = ArrayList(items)
        out.add(i + 1, Item("Asisten AI", 9) { a.startActivity(android.content.Intent(a, AiSettingsActivity::class.java)) })
        return out
    }

    fun menu(a: Activity, anchor: Rect, items0: List<Item>, centered: Boolean = false, onClosed: (() -> Unit)? = null): () -> Unit {
        val items = withAi(a, items0)
        val host = content(a)
        val ov = FrameLayout(a)
        ov.isClickable = true
        val panel = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
        panel.background = panelBg(panel, dp(a, 22).toFloat(), a)
        val rowH = dp(a, 52)
        val w = dp(a, if (centered) 290 else 228)
        var closed = false
        var closer: (() -> Unit)? = null
        fun close(after: (() -> Unit)?) {
            if (closed) return
            closed = true
            closer?.let { stack.remove(it) }
            panel.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(150).start()
            ov.animate().alpha(0f).setDuration(160).withEndAction {
                host.removeView(ov)
                after?.invoke()
                onClosed?.invoke()
            }.start()
        }
        closer = { close(null) }
        stack.add(closer!!)
        ov.setOnClickListener { close(null) }

        for ((i, item) in items.withIndex()) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(a, 18), 0, dp(a, 20), 0) }
            row.addView(ImageView(a).apply { setImageBitmap(icon(a, item.icon)); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)))
            row.addView(TextView(a).apply {
                text = item.label; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(a, 16) })
            row.setOnClickListener { close(item.action) }
            panel.addView(row, LinearLayout.LayoutParams(MATCH, rowH))
            if (i < items.size - 1) {
                panel.addView(View(a).apply { setBackgroundColor(Color.argb(36, 255, 255, 255)) }, LinearLayout.LayoutParams(MATCH, 1).apply { leftMargin = dp(a, 56) })
            }
        }
        val h = items.size * rowH + (items.size - 1)
        val cw = if (host.width > 0) host.width else a.resources.displayMetrics.widthPixels
        val ch = if (host.height > 0) host.height else a.resources.displayMetrics.heightPixels
        val m = dp(a, 12)
        var left: Int
        var topPos: Int
        var below = true
        if (centered) { left = (cw - w) / 2; topPos = (ch - h) / 2 }
        else {
            left = (anchor.centerX() - w / 2).coerceIn(m, maxOf(m, cw - w - m))
            val bt = anchor.bottom + dp(a, 10)
            if (bt + h + dp(a, 24) < ch) topPos = bt
            else { below = false; topPos = maxOf(dp(a, 24), anchor.top - h - dp(a, 10)) }
        }
        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START)
        lp.leftMargin = left; lp.topMargin = topPos
        ov.addView(panel, lp)
        host.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        panel.pivotX = (if (centered) w / 2 else (anchor.centerX() - left).coerceIn(0, w)).toFloat()
        panel.pivotY = if (centered || below) 0f else h.toFloat()
        panel.alpha = 0f; panel.scaleX = 0.55f; panel.scaleY = 0.55f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(230).setInterpolator(OvershootInterpolator(1.25f)).start()
        return { close(null) }
    }

    /** Toast kaca singkat di bawah layar. */
    fun toast(a: Activity, msg: String) {
        val host = content(a)
        val tv = TextView(a).apply {
            text = msg; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(a, 22), dp(a, 12), dp(a, 22), dp(a, 12)); maxLines = 3
        }
        tv.background = panelBg(tv, dp(a, 24).toFloat(), a)
        host.addView(tv, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(a, 128); leftMargin = dp(a, 24); rightMargin = dp(a, 24)
        })
        tv.alpha = 0f
        tv.translationY = dp(a, 18).toFloat()
        tv.animate().alpha(1f).translationY(0f).setDuration(220).start()
        main.postDelayed({
            tv.animate().alpha(0f).translationY(dp(a, 10).toFloat()).setDuration(200).withEndAction { host.removeView(tv) }.start()
        }, 2300)
    }
}
