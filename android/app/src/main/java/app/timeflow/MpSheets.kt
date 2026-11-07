package app.timeflow

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Galeri sampul buatan sendiri (bukan pemilih file sistem): lembar dari bawah, grid thumbnail foto / GIF / video,
 * filter Semua / Foto / Video, kartu pertama = kembali ke sampul lagu. Ketuk thumbnail = langsung dipakai.
 */
class MpGallerySheet(
    private val act: Activity,
    private val selected: String?,
    private val onPick: (Uri) -> Unit,
    private val onReset: () -> Unit,
    private val onSystemPicker: () -> Unit,
    private val onClosed: () -> Unit
) {
    class Item(val uri: Uri, val video: Boolean, val date: Long, val dur: Long)

    private val dn = act.resources.displayMetrics.density
    private fun dp(v: Float) = (v * dn).toInt()
    private fun round(color: Int, r: Float, stroke: Int = 0, sc: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r * dn; if (stroke > 0) setStroke(stroke, sc)
    }

    val view = FrameLayout(act)
    private val dim = View(act)
    private val panel = LinearLayout(act)
    private val grid = GridView(act)
    private val gate = LinearLayout(act)
    private val gateTitle = TextView(act)
    private val gateSub = TextView(act)
    private var all: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var filter = 0
    private var closing = false
    private val chips = ArrayList<TextView>()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount / 1024 }
    private val pool = Executors.newFixedThreadPool(3)

    private fun hasAccess(): Boolean {
        fun ok(p: String) = act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= 33)
            ok(Manifest.permission.READ_MEDIA_IMAGES) || ok(Manifest.permission.READ_MEDIA_VIDEO) || ok("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        else ok(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun askAccess() {
        act.requestPermissions(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 78)
    }

    // ---------------- sel grid ----------------
    private inner class Cell(ctx: Context) : FrameLayout(ctx) {
        val iv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val badge = TextView(ctx).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = round(Color.argb(140, 0, 0, 0), 9f); setPadding(dp(7f), dp(2f), dp(7f), dp(2f)); includeFontPadding = false
        }
        val ring = View(ctx).apply { background = round(Color.TRANSPARENT, 14f, dp(3f), Color.WHITE) }
        val note = TextView(ctx).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD); text = "♫\nSampul lagu"; setLineSpacing(0f, 1.15f)
        }
        init {
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 14f * dn) } }
            clipToOutline = true
            addView(iv, LayoutParams(-1, -1)); addView(note, LayoutParams(-1, -1))
            addView(badge, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(6f); bottomMargin = dp(6f) })
            addView(ring, LayoutParams(-1, -1))
        }
        override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, w) }
        fun bind(it: Item?) {
            if (it == null) {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(74, 52, 140), Color.rgb(28, 24, 52)))
                iv.setImageDrawable(null); iv.tag = null; iv.visibility = View.GONE; note.visibility = View.VISIBLE; badge.visibility = View.GONE
                ring.visibility = if (selected == null) View.VISIBLE else View.GONE
                return
            }
            background = ColorDrawableLite
            iv.visibility = View.VISIBLE; note.visibility = View.GONE
            val k = it.uri.toString()
            ring.visibility = if (k == selected) View.VISIBLE else View.GONE
            badge.visibility = if (it.video) View.VISIBLE else View.GONE
            if (it.video) badge.text = "▶ " + (if (it.dur > 0) "%d:%02d".format(it.dur / 60000, (it.dur / 1000) % 60) else "video")
            else if (k.isNotEmpty()) badge.visibility = View.GONE
            iv.tag = k
            val c = cache.get(k)
            if (c != null) { iv.setImageBitmap(c); iv.alpha = 1f; return }
            iv.setImageDrawable(null); iv.alpha = 0f
            pool.execute {
                val b = runCatching { thumb(it) }.getOrNull() ?: return@execute
                cache.put(k, b)
                act.runOnUiThread { if (iv.tag == k) { iv.setImageBitmap(b); iv.animate().alpha(1f).setDuration(200).start() } }
            }
        }
    }

    private val ColorDrawableLite = android.graphics.drawable.ColorDrawable(Color.rgb(34, 34, 38))

    private fun thumb(it: Item): Bitmap? {
        val cr = act.contentResolver
        if (Build.VERSION.SDK_INT >= 29) return cr.loadThumbnail(it.uri, Size(360, 360), null)
        val id = ContentUris.parseId(it.uri)
        @Suppress("DEPRECATION")
        return if (it.video) MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        else MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size + 1
        override fun getItem(p: Int): Any? = if (p == 0) null else shown[p - 1]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val cell = (cv as? Cell) ?: Cell(act)
            cell.bind(if (p == 0) null else shown[p - 1])
            return cell
        }
    }

    // ---------------- isi ----------------
    private fun query(): List<Item> {
        val out = ArrayList<Item>()
        fun q(base: Uri, video: Boolean) {
            val proj = if (video) arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Video.Media.DURATION)
            else arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
            act.contentResolver.query(base, proj, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 400) {
                    out.add(Item(ContentUris.withAppendedId(base, c.getLong(0)), video, c.getLong(1), if (video) c.getLong(2) else 0L)); n++
                }
            }
        }
        runCatching { q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) }
        runCatching { q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true) }
        out.sortByDescending { it.date }
        return out
    }

    fun reload() {
        if (!hasAccess()) { gate.visibility = View.VISIBLE; grid.visibility = View.GONE; return }
        gate.visibility = View.GONE; grid.visibility = View.VISIBLE
        Thread {
            val l = query()
            act.runOnUiThread { all = l; applyFilter() }
        }.start()
    }

    private fun applyFilter() {
        shown = when (filter) { 1 -> all.filter { !it.video }; 2 -> all.filter { it.video }; else -> all }
        adapter.notifyDataSetChanged()
    }

    private fun styleChips() {
        for ((i, c) in chips.withIndex()) {
            val on = i == filter
            c.background = round(if (on) Color.WHITE else Color.argb(38, 255, 255, 255), 18f)
            c.setTextColor(if (on) Color.BLACK else Color.WHITE)
        }
    }

    init {
        dim.setBackgroundColor(Color.argb(175, 0, 0, 0)); dim.setOnClickListener { close() }
        view.addView(dim, FrameLayout.LayoutParams(-1, -1))

        val r = 26f * dn
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply { setColor(Color.rgb(21, 21, 23)); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        panel.isClickable = true
        panel.visibility = View.INVISIBLE

        // pegangan
        panel.addView(View(act).apply { background = round(Color.argb(90, 255, 255, 255), 2f) },
            LinearLayout.LayoutParams(dp(38f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(9f) })

        // kepala: judul + filter + tutup
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(act).apply {
            text = "Edit player"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.create("sans-serif", Typeface.BOLD); includeFontPadding = false
        })
        titles.addView(TextView(act).apply {
            text = "Pilih foto, GIF, atau video untuk sampul"; setTextColor(Color.argb(150, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); includeFontPadding = false
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4f) })
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        for ((i, label) in listOf("Semua", "Foto", "Video").withIndex()) {
            val c = TextView(act).apply {
                text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setPadding(dp(16f), dp(8f), dp(16f), dp(8f)); includeFontPadding = false
                setOnClickListener { filter = i; styleChips(); applyFilter() }
            }
            chips.add(c); head.addView(c, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8f) })
        }
        styleChips()
        head.addView(TextView(act).apply {
            text = "✕"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = round(Color.argb(38, 255, 255, 255), 18f); setOnClickListener { close() }
        }, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { leftMargin = dp(14f) })
        panel.addView(head, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(60f); rightMargin = dp(24f); topMargin = dp(14f); bottomMargin = dp(12f) })

        // grid
        grid.numColumns = 6
        grid.horizontalSpacing = dp(8f); grid.verticalSpacing = dp(8f)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.clipToPadding = false; grid.setPadding(dp(60f), dp(2f), dp(24f), dp(24f))
        grid.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, p, _ ->
            if (p == 0) onReset() else onPick(shown[p - 1].uri)
            close()
        }
        panel.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))

        // izin / kosong
        gate.orientation = LinearLayout.VERTICAL; gate.gravity = Gravity.CENTER; gate.visibility = View.GONE
        gateTitle.apply { text = "Izinkan akses galeri"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        gateSub.apply { text = "Supaya foto dan video kamu tampil di sini."; setTextColor(Color.argb(150, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER }
        gate.addView(gateTitle); gate.addView(gateSub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        gate.addView(TextView(act).apply {
            text = "Izinkan"; setTextColor(Color.BLACK); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
            background = round(Color.WHITE, 24f); setPadding(dp(30f), dp(11f), dp(30f), dp(11f)); setOnClickListener { askAccess() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16f) })
        gate.addView(TextView(act).apply {
            text = "Atau pilih dari file"; setTextColor(Color.argb(190, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f)); setOnClickListener { onSystemPicker(); close() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        panel.addView(gate, LinearLayout.LayoutParams(-1, 0, 1f))

        val h = (act.resources.displayMetrics.heightPixels * 0.80f).toInt()
        view.addView(panel, FrameLayout.LayoutParams(-1, h, Gravity.BOTTOM))
    }

    fun show() {
        dim.alpha = 0f
        reload()
        view.post {
            panel.translationY = panel.height.toFloat(); panel.visibility = View.VISIBLE
            dim.animate().alpha(1f).setDuration(260).start()
            panel.animate().translationY(0f).setDuration(420).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.22f, 1f)).start()
        }
    }

    fun close() {
        if (closing) return
        closing = true
        panel.animate().translationY(panel.height.toFloat()).setDuration(260).setInterpolator(PathInterpolator(0.4f, 0f, 0.8f, 0.3f)).start()
        dim.animate().alpha(0f).setDuration(260).withEndAction {
            (view.parent as? ViewGroup)?.removeView(view)
            pool.shutdownNow()
            onClosed()
        }.start()
    }
}

/** Menu titik tiga: kartu gelap membulat yang muncul dari pojok kanan atas (bukan popup bawaan sistem). */
class MpMenu(private val act: Activity, items: List<Pair<String, () -> Unit>>, private val onGone: () -> Unit) {
    private val dn = act.resources.displayMetrics.density
    private fun dp(v: Float) = (v * dn).toInt()
    val view = FrameLayout(act)
    private val card = LinearLayout(act)
    private var gone = false

    init {
        view.setOnClickListener { dismiss() }
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply { setColor(Color.argb(244, 38, 38, 42)); cornerRadius = 20f * dn; setStroke(dp(1f), Color.argb(40, 255, 255, 255)) }
        card.elevation = 18f * dn
        card.setPadding(0, dp(6f), 0, dp(6f))
        for ((i, item) in items.withIndex()) {
            val row = TextView(act).apply {
                text = item.first; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20f), 0, dp(26f), 0)
                setOnClickListener { _ -> dismiss(); item.second() }
            }
            card.addView(row, LinearLayout.LayoutParams(-2, dp(46f)))
            if (i < items.size - 1) card.addView(View(act).apply { setBackgroundColor(Color.argb(28, 255, 255, 255)) },
                LinearLayout.LayoutParams(-1, dp(1f)).apply { leftMargin = dp(20f); rightMargin = dp(20f) })
        }
        view.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(54f); rightMargin = dp(14f) })
        card.visibility = View.INVISIBLE
    }

    fun show() {
        card.post {
            card.pivotX = card.width.toFloat(); card.pivotY = 0f
            card.scaleX = 0.8f; card.scaleY = 0.8f; card.alpha = 0f; card.visibility = View.VISIBLE
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
    }

    fun dismiss() {
        if (gone) return
        gone = true
        card.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(140).withEndAction {
            (view.parent as? ViewGroup)?.removeView(view); onGone()
        }.start()
    }
}
