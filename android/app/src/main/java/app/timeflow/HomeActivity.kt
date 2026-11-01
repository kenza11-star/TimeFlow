package app.timeflow

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.DragEvent
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.BaseAdapter
import android.widget.BaseExpandableListAdapter
import android.widget.ExpandableListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * TimeFlow Home: peluncur (layar utama) dengan kaca bergaya liquid glass.
 * Halaman ikon + folder + widget (host widget Android), dock kaca, laci aplikasi (geser ke atas), seret-dan-lepas, wallpaper sendiri
 * yang diburamkan sekali untuk semua panel kaca. Widget TimeFlow memakai kaca langsung dari peluncur (tanpa tangkapan layar).
 */
class HomeActivity : Activity() {
    private companion object {
        const val REQ_BIND = 71
        const val REQ_CONFIG = 72
        const val REQ_WALL = 73
        const val REQ_ROLE = 74
        const val HOST_ID = 1024
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val FROM_PAGE = 0
        const val FROM_DOCK = 1
        const val DOCK_H = 88
        const val PILL_H = 30
        const val SP = "timeflow_home"
    }

    private class Payload(val item: HItem, val folder: HItem?)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var wallImg: ImageView
    private lateinit var pager: HPager
    private lateinit var pagesBox: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var dockPanel: FrameLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var dragBar: LinearLayout
    private lateinit var pill: TextView
    private var askedDefault = false
    private var editing = false
    private var itemMenuDismiss: (() -> Unit)? = null
    private var dragX0 = Float.NaN
    private var dragY0 = Float.NaN
    private var menuHidden = true
    private var editBar: LinearLayout? = null
    private var sheet: FrameLayout? = null
    private lateinit var host: AppWidgetHost
    private lateinit var awm: AppWidgetManager
    private lateinit var gd: GestureDetector

    private var topInset = 0
    private var botInset = 0
    private var rootW = 0
    private var rootH = 0
    private var pageW = 0
    private var pageH = 0
    private var cellW = 1
    private var cellH = 1
    private var curPage = 0
    private var mono = true
    private var showLabels = true
    private var drag: Payload? = null
    private var dragView: View? = null
    private var overlay: View? = null
    private var folderTitle: EditText? = null
    private var folderItem: HItem? = null
    private var labelsDirty = false
    private var edgeDir = 0
    private var pendWid = -1
    private var pendInfo: AppWidgetProviderInfo? = null
    private var drawerBmp: Bitmap? = null
    private var lastSig = ""
    private val glassViews = ArrayList<View>()
    private val labels = HashMap<String, String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- siklus hidup ----------
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeStore.load(this)
        mono = getSharedPreferences(SP, MODE_PRIVATE).getBoolean("mono", false)
        showLabels = getSharedPreferences(SP, MODE_PRIVATE).getBoolean("labels", true)
        awm = AppWidgetManager.getInstance(this)
        host = AppWidgetHost(this, HOST_ID)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        buildSkeleton()
        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (overlay != null || drag != null) return false
                if (dy < -dp(80) && Math.abs(dy) > Math.abs(dx) * 1.5f && vy < -600f) { openDrawer(); return true }
                if (dy > dp(80) && Math.abs(dy) > Math.abs(dx) * 1.5f && vy > 600f && e1.y < rootH * 0.6f) {
                    // geser turun: sisi kanan = Control Center (bila layanan aktif), selain itu panel notifikasi
                    if (e1.x > rootW * 0.6f && CcAccessibilityService.open(0)) return true
                    expandShade(); return true
                }
                return false
            }
        })
        Thread { AppRepo.list(this) }.start()
    }

    override fun onStart() {
        super.onStart()
        runCatching { host.startListening() }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgRx, f, Context.RECEIVER_EXPORTED) else registerReceiver(pkgRx, f)
        AppRepo.refresh()
        pruneMissing()
    }

    override fun onResume() {
        super.onResume()
        if (HomeStore.dirty) { HomeStore.dirty = false; if (built()) rebuild() }
        val sp = getSharedPreferences(SP, MODE_PRIVATE)
        val def = HomeUtil.isDefaultHome(this)
        if (def != sp.getBoolean("was_default", false)) {
            sp.edit().putBoolean("was_default", def).apply()
            WidgetUtil.updateAll(this)          // widget TimeFlow ganti ke kaca peluncur (atau kembali ke latar tangkapan layar)
        }
        val sig = GlassPrefs.sig(this) + "|" + mono
        if (built() && sig != lastSig) { lastSig = sig; applyWall(true) }
        warmIcons()
        if (built()) maybeAskDefault()
    }

    override fun onStop() {
        super.onStop()
        runCatching { host.stopListening() }
        runCatching { unregisterReceiver(pkgRx) }
        HomeStore.save(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (sheet != null) closeSheet()
        if (overlay != null) closeOverlay(false)
        if (editing) exitEdit()
        curPage = 0
        if (pageW > 0) pager.goTo(0, true)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (FlowUi.dismissTop()) return
        if (sheet != null) closeSheet()
        else if (overlay != null) closeOverlay(true)
        else if (editing) exitEdit()
        else if (curPage != 0 && pageW > 0) pager.goTo(0, true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun built() = pageW > 0

    /** Tarik panel notifikasi sistem (izin EXPAND_STATUS_BAR). */
    private fun expandShade() {
        runCatching {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        }
    }

    private val pkgRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart
            AppRepo.refresh()
            if (pkg != null) {
                labels.remove(pkg)
                if (i.action == Intent.ACTION_PACKAGE_REMOVED && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    if (HomeStore.purgePkg(pkg)) { HomeStore.save(c); rebuild() }
                }
            }
        }
    }

    private fun pruneMissing() {
        val pm = packageManager
        val gone = HashSet<String>()
        for (x in HomeStore.all()) {
            if (x.type == HItem.APP && pm.getLaunchIntentForPackage(x.pkg) == null) gone.add(x.pkg)
            if (x.type == HItem.FOLDER) for (a in x.apps) if (pm.getLaunchIntentForPackage(a) == null) gone.add(a)
        }
        var ch = false
        for (g in gone) if (HomeStore.purgePkg(g)) ch = true
        if (ch) { HomeStore.save(this); if (built()) rebuild() }
    }

    private fun warmIcons() {
        val m = mono
        Thread { for (e in AppRepo.list(this)) FolderIcons.tile(this, e.pkg, m, false) }.start()
    }

    // ---------- kerangka tampilan ----------
    @Suppress("DEPRECATION")
    private fun buildSkeleton() {
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF0A0A0E.toInt())
        root.setOnClickListener { if (editing) exitEdit() }
        wallImg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(wallImg, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(View(this).apply { setBackgroundColor(Color.argb(36, 0, 0, 0)) }, FrameLayout.LayoutParams(MATCH, MATCH))

        pagesBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pager = HPager(this)
        pager.onPage = { p -> curPage = p; updateDots() }
        pager.onScroll = {
            for (v in glassViews) v.invalidate()
            val w = pageW.toFloat()
            if (w > 0f) for (i in 0 until pagesBox.childCount) {
                val pv = pagesBox.getChildAt(i)
                val k = (Math.abs(pager.scrollX - i * w) / w).coerceIn(0f, 1f)
                pv.alpha = 1f - 0.4f * k
                val sc = 1f - 0.08f * k
                pv.scaleX = sc; pv.scaleY = sc
            }
        }
        pager.addView(pagesBox, FrameLayout.LayoutParams(WRAP, MATCH))
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(dots, FrameLayout.LayoutParams(MATCH, dp(16), Gravity.BOTTOM))

        pill = TextView(this).apply {
            text = "Cari"; textSize = 13f; setTextColor(Color.argb(235, 255, 255, 255)); gravity = Gravity.CENTER
            compoundDrawablePadding = dp(6); setPadding(dp(14), 0, dp(16), 0)
            setCompoundDrawablesWithIntrinsicBounds(BitmapDrawable(resources, magnifier()), null, null, null)
            setOnClickListener { openDrawer(true) }
        }
        pill.background = GlassPanelDrawable(pill, dp(15).toFloat(), 1f, 60, 0.4f)
        root.addView(pill, FrameLayout.LayoutParams(dp(112), dp(PILL_H), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        dockPanel = FrameLayout(this)
        dockRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dockRow.setOnDragListener { v, e -> onDockDrag(v, e) }
        dockPanel.addView(dockRow, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(dockPanel, FrameLayout.LayoutParams(MATCH, dp(DOCK_H), Gravity.BOTTOM))

        buildDragBar()
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, ins ->
            val t = ins.stableInsetTop
            val b = ins.stableInsetBottom
            if (t != topInset || b != botInset) { topInset = t; botInset = b; relayout() }
            ins
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w != rootW || h != rootH) { rootW = w; rootH = h; relayout() }
        }
    }

    private fun relayout() {
        if (rootW == 0 || rootH == 0) return
        val dockH = dp(DOCK_H)
        val dockBottom = botInset + dp(10)
        val pillBottom = dockBottom + dockH + dp(10)
        val dotsBottom = pillBottom + dp(PILL_H) + dp(4)
        val top = topInset + dp(6)
        pageW = rootW
        pageH = rootH - top - (dotsBottom + dp(14) + dp(4))
        if (pageH < dp(200)) return
        cellW = pageW / HomeStore.COLS
        cellH = pageH / HomeStore.ROWS
        (pager.layoutParams as FrameLayout.LayoutParams).apply { width = MATCH; height = pageH; topMargin = top }
        pager.requestLayout()
        pager.pageW = pageW
        (dots.layoutParams as FrameLayout.LayoutParams).bottomMargin = dotsBottom
        (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = pillBottom
        (dockPanel.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dockBottom }
        (dragBar.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + dp(4)
        dockPanel.requestLayout()
        lastSig = GlassPrefs.sig(this) + "|" + mono
        applyWall(false)
        rebuild()
    }

    private fun applyWall(force: Boolean) {
        val w = rootW
        val h = rootH
        if (w == 0 || h == 0) return
        Thread {
            if (force || HomeWall.needs(this, w, h)) HomeWall.build(this, w, h)
            runOnUiThread {
                wallImg.setImageBitmap(HomeWall.full)
                if (force) rebuild() else invalidateGlass()
                if (!maybeAskDefault()) askWallpaperOnce()
            }
        }.start()
    }

    private fun maybeAskDefault(): Boolean = HomeUtil.promptDefault(this, REQ_ROLE) { askWallpaperOnce() }

    private fun requestHome() = HomeUtil.requestHome(this, REQ_ROLE)

    /** Bila wallpaper sistem tidak bisa dibaca (Android baru), tawarkan memilih wallpaper dari galeri satu kali. */
    private fun askWallpaperOnce() {
        val sp = getSharedPreferences(SP, MODE_PRIVATE)
        if (!HomeWall.fallback || sp.getBoolean("asked_wall", false) || isFinishing) return
        sp.edit().putBoolean("asked_wall", true).apply()
        FlowUi.dialog(this, "Pilih wallpaper", "Flow Launcher memakai wallpaper sendiri supaya kaca dan blur akurat. Pilih gambar dari galeri sekarang?",
            listOf("Pilih" to { pickWallpaper() }, "Nanti" to {}))
    }

    private fun invalidateGlass() {
        dockPanel.invalidate()
        for (v in glassViews) v.invalidate()
        overlay?.invalidate()
    }

    // ---------- halaman, dock, ikon ----------
    private fun rebuild() {
        if (!built()) return
        glassViews.clear()
        pagesBox.removeAllViews()
        for (p in HomeStore.pages.indices) pagesBox.addView(buildPage(p), LinearLayout.LayoutParams(pageW, pageH))
        buildDock()
        if (curPage >= HomeStore.pages.size) curPage = HomeStore.pages.size - 1
        updateDots()
        pager.post { pager.goTo(curPage, false) }
    }

    private fun buildPage(p: Int): FrameLayout {
        val page = FrameLayout(this)
        page.isLongClickable = true
        page.setOnLongClickListener { if (!editing) enterEdit(); true }
        page.setOnClickListener { if (editing) exitEdit() }
        page.setOnDragListener { v, e -> onPageDrag(v, e, p) }
        // sorotan sel tujuan saat menyeret (selalu anak pertama)
        val hl = View(this)
        hl.background = GradientDrawable().apply { setColor(Color.argb(46, 255, 255, 255)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(90, 255, 255, 255)) }
        hl.alpha = 0f
        page.addView(hl, FrameLayout.LayoutParams(cellW, cellH))
        for (item in ArrayList<HItem>(HomeStore.pages[p])) {
            val v = makeItemView(item, FROM_PAGE) ?: continue
            v.tag = item
            val lp = FrameLayout.LayoutParams(item.sx * cellW, item.sy * cellH)
            lp.leftMargin = item.cx * cellW
            lp.topMargin = item.cy * cellH
            if (item.type == HItem.WIDGET) {
                lp.width = item.sx * cellW - dp(8); lp.height = item.sy * cellH - dp(8)
                lp.leftMargin += dp(4); lp.topMargin += dp(4)
            }
            page.addView(v, lp)
        }
        return page
    }

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun iconFor(item: HItem): Bitmap? =
        if (item.type == HItem.APP) FolderIcons.tile(this, item.pkg, mono, false)
        else FolderIcons.mini(this, item.apps.take(if (mono) 4 else 9), mono, false, 144, mono)

    private fun magnifier(): Bitmap {
        val s = dp(15)
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = s * 0.13f; strokeCap = Paint.Cap.ROUND
        }
        c.drawCircle(s * 0.42f, s * 0.42f, s * 0.28f, p)
        c.drawLine(s * 0.64f, s * 0.64f, s * 0.88f, s * 0.88f, p)
        return b
    }

    private fun makeItemView(item: HItem, from: Int): View? {
        if (item.type == HItem.WIDGET) return makeWidget(item)
        val pm = packageManager
        if (item.type == HItem.APP && pm.getLaunchIntentForPackage(item.pkg) == null) return null
        if (item.type == HItem.FOLDER) {
            item.apps.retainAll { pm.getLaunchIntentForPackage(it) != null }
            if (item.apps.isEmpty()) return null
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val isz = if (from == FROM_DOCK) dp(62) else minOf(dp(62), (cellW * 0.7f).toInt())
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(iconFor(item)) }
        if (item.type == HItem.FOLDER && !mono) {
            // folder gaya iOS: kotak frosted (kaca asli dari wallpaper kabur) berisi 3x3 ikon mini
            iv.background = GlassPanelDrawable(iv, isz * 0.225f, 1f, 60, 0.4f)
            glassViews.add(iv)
        }
        box.addView(iv, LinearLayout.LayoutParams(isz, isz))
        if (from != FROM_DOCK && showLabels) {
            box.addView(TextView(this).apply {
                text = if (item.type == HItem.APP) item.name.ifEmpty { appLabel(item.pkg) } else item.name.ifEmpty { "Folder" }
                textSize = 11.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5); leftMargin = dp(2); rightMargin = dp(2) })
        }
        box.setOnClickListener { if (item.type == HItem.APP) launch(item.pkg, box) else openFolder(item, box) }
        box.setOnLongClickListener { onItemLong(box, item); true }
        box.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }
        return box
    }

    private fun makeWidget(item: HItem): View? {
        val info = awm.getAppWidgetInfo(item.wid) ?: return null
        val frame = LongPressFrame(this)
        val hv = runCatching { host.createView(this, item.wid, info) }.getOrNull() ?: return null
        frame.addView(hv, FrameLayout.LayoutParams(MATCH, MATCH))
        if (info.provider.packageName == packageName) {
            // widget TimeFlow: kaca langsung dari peluncur (selalu pas di belakang widget, ikut geser)
            frame.background = GlassPanelDrawable(frame, GlassPrefs.get(this).radiusDp * resources.displayMetrics.density)
            glassViews.add(frame)
        }
        frame.onLong = { onItemLong(frame, item) }
        frame.post {
            val d = resources.displayMetrics.density
            val w = (frame.width / d).toInt()
            val h = (frame.height / d).toInt()
            runCatching { hv.updateAppWidgetSize(Bundle(), w, h, w, h) }
        }
        return frame
    }

    private fun buildDock() {
        dockRow.removeAllViews()
        // dock gaya iPhone: bilah frosted bersudut besar, 4 ikon rata (laci aplikasi lewat geser ke atas atau tombol Cari)
        dockPanel.background = GlassPanelDrawable(dockPanel, dp(34).toFloat(), 1f, 62, 0.35f)
        for (s in 0 until HomeStore.DOCK) {
            val cell = FrameLayout(this)
            val item = HomeStore.dock.firstOrNull { it.cx == s }
            val v = if (item != null) makeItemView(item, FROM_DOCK) else null
            if (v != null) { v.tag = item; cell.addView(v, FrameLayout.LayoutParams(MATCH, MATCH)) }
            dockRow.addView(cell, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    private fun drawerButtonBmp(): Bitmap {
        drawerBmp?.let { return it }
        val s = 144
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        FolderIcons.drawGlassTile(c, RectF(0f, 0f, s.toFloat(), s.toFloat()), false)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        val step = s * 0.18f
        val start = s / 2f - step
        for (i in 0..2) for (j in 0..2) c.drawCircle(start + i * step, start + j * step, s * 0.045f, p)
        drawerBmp = b
        return b
    }

    private fun updateDots() {
        dots.removeAllViews()
        val n = HomeStore.pages.size
        if (n <= 1) return
        for (i in 0 until n) {
            val d = View(this)
            d.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            d.alpha = if (i == curPage) 0.95f else 0.38f
            dots.addView(d, LinearLayout.LayoutParams(dp(6), dp(6)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    private fun launch(pkg: String, from: View? = null) {
        val li = packageManager.getLaunchIntentForPackage(pkg) ?: return
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = if (from != null && from.width > 0) ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle() else null
        runCatching { if (opts != null) startActivity(li, opts) else startActivity(li) }
        if (overlay != null) closeOverlay(false)
    }

    // ---------- seret dan lepas ----------
    private fun buildDragBar() {
        dragBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(10), 0, dp(10), 0) }
        val defs = listOf("Hapus" to 0, "Info" to 1, "Copot" to 2)
        for (d in defs) {
            val tv = TextView(this).apply {
                text = d.first; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            tv.background = GlassPanelDrawable(tv, dp(22).toFloat(), 1.2f)
            tv.tag = d.second
            val kind = d.second
            tv.setOnDragListener { v, e -> onBarDrag(v, e, kind) }
            dragBar.addView(tv, LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        root.addView(dragBar, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))
    }

    private fun beginDrag(v: View, p: Payload, withMenu: Boolean = false) {
        if (drag != null) return
        drag = p
        dragView = v
        // halaman cadangan di ujung kanan supaya ikon bisa dilepas ke halaman baru
        if (HomeStore.pages.size < HomeStore.MAX_PAGES && HomeStore.pages[HomeStore.pages.size - 1].isNotEmpty()) addSparePage()
        val clip = ClipData.newPlainText("item", p.item.pkg)
        val started = v.startDragAndDrop(clip, View.DragShadowBuilder(v), null, 0)
        if (!started) { drag = null; dragView = null; return }
        v.alpha = 0.3f
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val placed = p.folder != null || HomeStore.all().contains(p.item)
        val isApp = p.item.type == HItem.APP
        for (i in 0 until dragBar.childCount) {
            val kind = dragBar.getChildAt(i).tag as Int
            dragBar.getChildAt(i).visibility = when (kind) { 0 -> if (placed) View.VISIBLE else View.GONE; else -> if (isApp) View.VISIBLE else View.GONE }
        }
        dragX0 = Float.NaN
        menuHidden = !withMenu
        if (!withMenu) showDragBar()
        if (overlay != null) ui.post { closeOverlay(false) }
    }

    private fun addSparePage() {
        HomeStore.pages.add(ArrayList())
        val idx = HomeStore.pages.size - 1
        pagesBox.addView(buildPage(idx), LinearLayout.LayoutParams(pageW, pageH))
        updateDots()
    }

    private fun endDrag() {
        if (drag == null) return
        drag = null
        dragView?.alpha = 1f
        dragView = null
        dragBar.visibility = View.GONE
        dragX0 = Float.NaN
        ui.removeCallbacks(edgeRun)
        edgeDir = 0
        ui.post {
            val before = HomeStore.pages.size
            HomeStore.trim()
            if (HomeStore.pages.size != before) rebuild()
            HomeStore.save(this)
        }
    }

    private val edgeRun = Runnable {
        val target = curPage + edgeDir
        if (edgeDir != 0 && target >= 0 && target < HomeStore.pages.size) { pager.goTo(target, true); curPage = target; updateDots() }
        edgeDir = 0
    }

    private fun setEdge(dir: Int) {
        if (dir == edgeDir) return
        edgeDir = dir
        ui.removeCallbacks(edgeRun)
        if (dir != 0) ui.postDelayed(edgeRun, 650)
    }

    private fun onPageDrag(v: View, e: DragEvent, p: Int): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return drag != null
            DragEvent.ACTION_DRAG_LOCATION -> {
                noteDragMove(v, e)
                setEdge(if (e.x < dp(36)) -1 else if (e.x > v.width - dp(36)) 1 else 0)
                val it = drag?.item
                val sx = it?.sx ?: 1
                val sy = it?.sy ?: 1
                val cx = (e.x / cellW).toInt().coerceIn(0, HomeStore.COLS - sx)
                val cy = (e.y / cellH).toInt().coerceIn(0, HomeStore.ROWS - sy)
                val hl = (v as ViewGroup).getChildAt(0)
                val lp = hl.layoutParams
                if (lp.width != sx * cellW || lp.height != sy * cellH) { lp.width = sx * cellW; lp.height = sy * cellH; hl.layoutParams = lp }
                hl.animate().x((cx * cellW).toFloat()).y((cy * cellH).toFloat()).alpha(1f).setDuration(110).start()
            }
            DragEvent.ACTION_DRAG_EXITED -> { setEdge(0); (v as ViewGroup).getChildAt(0).animate().alpha(0f).setDuration(120).start() }
            DragEvent.ACTION_DROP -> {
                val pl = drag ?: return false
                val cx = (e.x / cellW).toInt().coerceIn(0, HomeStore.COLS - pl.item.sx)
                val cy = (e.y / cellH).toInt().coerceIn(0, HomeStore.ROWS - pl.item.sy)
                val fx = e.x
                val fy = e.y
                setEdge(0)
                (v as ViewGroup).getChildAt(0).animate().alpha(0f).setDuration(120).start()
                ui.post { dropOnGrid(pl, p, cx, cy, fx, fy) }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> { (v as ViewGroup).getChildAt(0).animate().alpha(0f).setDuration(120).start(); endDrag() }
        }
        return true
    }

    private fun onDockDrag(v: View, e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> { val d = drag; return d != null && d.item.type != HItem.WIDGET }
            DragEvent.ACTION_DRAG_LOCATION -> noteDragMove(v, e)
            DragEvent.ACTION_DROP -> {
                val pl = drag ?: return false
                val slot = (e.x / (v.width / HomeStore.DOCK.toFloat())).toInt().coerceIn(0, HomeStore.DOCK - 1)
                ui.post { dropOnDock(pl, slot) }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    private fun onBarDrag(v: View, e: DragEvent, kind: Int): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return drag != null
            DragEvent.ACTION_DRAG_ENTERED -> v.alpha = 0.6f
            DragEvent.ACTION_DRAG_EXITED -> v.alpha = 1f
            DragEvent.ACTION_DROP -> {
                val pl = drag ?: return false
                ui.post { barAction(pl, kind) }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; endDrag() }
        }
        return true
    }

    /** Lepas item yang sedang diseret dari asalnya (halaman, dock, atau dalam folder). Dari laci tidak ada yang dilepas. */
    private fun detach(pl: Payload) {
        val f = pl.folder
        if (f != null) { f.apps.remove(pl.item.pkg); HomeStore.normalize(f) } else HomeStore.remove(pl.item)
    }

    private fun makeFolder(target: HItem, item: HItem) {
        val a = target.pkg
        target.type = HItem.FOLDER
        target.apps = ArrayList<String>().apply { add(a); add(item.pkg) }
        target.pkg = ""
        target.name = "Folder"
        target.wid = HomeStore.nextId()
    }

    /** Posisi semua item sebelum diubah: [wadah (0 halaman / 1 dock), halaman, kolom, baris]. Dipakai untuk animasi pindah. */
    private fun snapshot(): HashMap<HItem, IntArray> {
        val m = HashMap<HItem, IntArray>()
        for (p in HomeStore.pages.indices) for (x in HomeStore.pages[p]) m[x] = intArrayOf(0, p, x.cx, x.cy)
        for (x in HomeStore.dock) m[x] = intArrayOf(1, 0, x.cx, 0)
        return m
    }

    private fun popIn(v: View, from: Float = 0.5f) {
        v.scaleX = from; v.scaleY = from; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).setInterpolator(OvershootInterpolator(1.8f)).start()
    }

    /**
     * Animasi setelah tata letak diubah: item yang pindah meluncur dari posisi lama (item yang dilepas dari titik jari) dengan
     * pelambatan halus, item baru muncul membesar, folder yang baru menerima ikon memantul.
     */
    private fun animateFrom(snap: Map<HItem, IntArray>, dragged: HItem?, fx: Float, fy: Float, fPage: Int, bounce: HItem?) {
        for (p in 0 until pagesBox.childCount) {
            val page = pagesBox.getChildAt(p) as? ViewGroup ?: continue
            for (i in 0 until page.childCount) {
                val v = page.getChildAt(i)
                val item = v.tag as? HItem ?: continue
                val old = snap[item]
                if (item === dragged && fx >= 0f && p == fPage) {
                    // mendarat dari titik jari
                    val dx = fx - (item.cx * cellW + item.sx * cellW / 2f)
                    val dy = fy - (item.cy * cellH + item.sy * cellH / 2f)
                    v.translationX = dx; v.translationY = dy; v.scaleX = 1.1f; v.scaleY = 1.1f
                    v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(DecelerateInterpolator(1.8f)).start()
                } else if (old == null) popIn(v)
                else if (old[0] == 0 && old[1] == p) {
                    val dx = (old[2] - item.cx) * cellW.toFloat()
                    val dy = (old[3] - item.cy) * cellH.toFloat()
                    if (dx != 0f || dy != 0f) {
                        v.translationX = dx; v.translationY = dy
                        v.animate().translationX(0f).translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(1.8f)).start()
                    }
                } else popIn(v, 0.7f)
                if (item === bounce) {
                    v.scaleX = 1.25f; v.scaleY = 1.25f
                    v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
                }
            }
        }
        val slotW = dockRow.width / HomeStore.DOCK.toFloat()
        for (i in 0 until dockRow.childCount) {
            val cell = dockRow.getChildAt(i) as? ViewGroup ?: continue
            if (cell.childCount == 0) continue
            val v = cell.getChildAt(0)
            val item = v.tag as? HItem ?: continue
            val old = snap[item]
            if (old == null) popIn(v)
            else if (old[0] == 1) {
                val dx = (old[2] - item.cx) * slotW
                if (dx != 0f) { v.translationX = dx; v.animate().translationX(0f).setDuration(300).setInterpolator(DecelerateInterpolator(1.8f)).start() }
            } else popIn(v, 0.7f)
            if (item === bounce) {
                v.scaleX = 1.25f; v.scaleY = 1.25f
                v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
            }
        }
    }

    private fun dropOnGrid(pl: Payload, p: Int, cx: Int, cy: Int, fx: Float, fy: Float) {
        val item = pl.item
        if (p >= HomeStore.pages.size) return
        val snap = snapshot()
        val list = HomeStore.pages[p]
        val target = list.firstOrNull { it !== item && cx >= it.cx && cx < it.cx + it.sx && cy >= it.cy && cy < it.cy + it.sy }
        if (pl.folder != null && target === pl.folder) return
        var bounce: HItem? = null
        var landed = true
        if (target != null && item.type == HItem.APP && target.type == HItem.APP && target.pkg != item.pkg) {
            detach(pl); makeFolder(target, item); bounce = target; landed = false
        } else if (target != null && item.type == HItem.APP && target.type == HItem.FOLDER) {
            detach(pl)
            if (item.pkg !in target.apps) target.apps.add(item.pkg)
            bounce = target; landed = false
        } else {
            detach(pl)
            if (HomeStore.cellFree(list, cx, cy, item.sx, item.sy, null)) { item.cx = cx; item.cy = cy; list.add(item) }
            else if (HomeStore.placeNear(item, p, cx, cy) < 0) FlowUi.toast(this, "Layar penuh")
        }
        HomeStore.save(this)
        rebuild()
        val d = if (landed) item else null
        pager.post { animateFrom(snap, d, fx, fy, p, bounce) }
    }

    private fun dropOnDock(pl: Payload, slot: Int) {
        val item = pl.item
        val snap = snapshot()
        val target = HomeStore.dock.firstOrNull { it.cx == slot && it !== item }
        if (pl.folder != null && target === pl.folder) return
        var bounce: HItem? = null
        if (target != null && item.type == HItem.APP && target.type == HItem.APP && target.pkg != item.pkg) {
            detach(pl); makeFolder(target, item); bounce = target
        } else if (target != null && item.type == HItem.APP && target.type == HItem.FOLDER) {
            detach(pl)
            if (item.pkg !in target.apps) target.apps.add(item.pkg)
            bounce = target
        } else {
            detach(pl)
            if (!HomeStore.placeDock(item, slot)) {
                HomeStore.placeNear(item, 0, 0, 0)
                FlowUi.toast(this, "Dock penuh")
            }
        }
        HomeStore.save(this)
        rebuild()
        pager.post { animateFrom(snap, null, -1f, -1f, -1, bounce) }
    }

    private fun barAction(pl: Payload, kind: Int) {
        val item = pl.item
        when (kind) {
            0 -> {
                if (item.type == HItem.WIDGET && item.wid >= 0) runCatching { host.deleteAppWidgetId(item.wid) }
                detach(pl)
                HomeStore.save(this)
                rebuild()
            }
            1 -> if (item.type == HItem.APP) runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HomeUtil.uriOfPkg(item.pkg))) }
            else -> if (item.type == HItem.APP) runCatching { startActivity(Intent(Intent.ACTION_DELETE, HomeUtil.uriOfPkg(item.pkg))) }
        }
    }

    // ---------- folder ----------
    private fun openFolder(f: HItem, src: View? = null) {
        closeOverlay(false)
        if (f.wid < 0) { f.wid = HomeStore.nextId(); HomeStore.save(this) }
        val ov = FrameLayout(this)
        ov.setBackgroundColor(Color.argb(110, 0, 0, 0))
        ov.setOnClickListener { closeOverlay(true) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(18)); isClickable = true
        }
        panel.background = GlassPanelDrawable(panel, dp(34).toFloat(), 1f, 56, 0.45f)
        val title = EditText(this).apply {
            setText(f.name.ifEmpty { "Folder" }); textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = null; setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_DONE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        title.setOnEditorActionListener { v, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE) { hideKeyboard(v); v.clearFocus(); true } else false
        }
        panel.addView(title, LinearLayout.LayoutParams(MATCH, dp(44)))
        folderTitle = title; folderItem = f
        val cells = ArrayList<View>()
        for (row in f.apps.toList().chunked(4)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (pkg in row) { val cv = folderCell(f, pkg); cells.add(cv); line.addView(cv, LinearLayout.LayoutParams(0, WRAP, 1f)) }
            for (i in row.size until 4) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            panel.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        val manage = TextView(this).apply {
            text = "Atur folder"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener {
                val id = f.wid
                closeOverlay(false)
                startActivity(Intent(this@HomeActivity, FolderConfigActivity::class.java).putExtra("home_folder", id))
            }
        }
        manage.background = GlassPanelDrawable(manage, dp(18).toFloat(), 1f, 50, 0.4f)
        panel.addView(manage, LinearLayout.LayoutParams(WRAP, dp(36)).apply { topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL })
        ov.addView(panel, FrameLayout.LayoutParams((rootW * 0.9f).toInt(), WRAP, Gravity.CENTER))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ov
        ov.alpha = 0f
        ov.animate().alpha(1f).setDuration(180).start()
        // panel membesar dari ikon folder, isinya muncul berurutan
        val sl = IntArray(2)
        if (src != null) src.getLocationInWindow(sl)
        val sx = sl[0] + (src?.width ?: 0) / 2
        val sy = sl[1] + (src?.height ?: 0) / 2
        panel.post {
            val pl = IntArray(2)
            panel.getLocationInWindow(pl)
            panel.pivotX = if (src != null) (sx - pl[0]).toFloat() else panel.width / 2f
            panel.pivotY = if (src != null) (sy - pl[1]).toFloat() else panel.height / 2f
            panel.scaleX = 0.3f; panel.scaleY = 0.3f; panel.alpha = 0f
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
        for ((i, c) in cells.withIndex()) {
            c.alpha = 0f; c.scaleX = 0.7f; c.scaleY = 0.7f; c.translationY = dp(14).toFloat()
            c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(90L + i * 30L).setDuration(300).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    private fun folderCell(f: HItem, pkg: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(FolderIcons.tile(this@HomeActivity, pkg, mono, false)) }, LinearLayout.LayoutParams(dp(60), dp(60)))
        box.addView(TextView(this).apply {
            text = appLabel(pkg); textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
            setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        box.setOnClickListener { launch(pkg, box) }
        box.setOnLongClickListener { beginDrag(box, Payload(HItem(HItem.APP, pkg = pkg), f)); true }
        return box
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun closeOverlay(animate: Boolean) {
        val ov = overlay ?: return
        overlay = null
        val t = folderTitle
        val f = folderItem
        if (t != null && f != null) {
            val n = t.text.toString().trim().ifEmpty { "Folder" }
            if (n != f.name) { f.name = n; HomeStore.save(this); labelsDirty = true }
        }
        folderTitle = null; folderItem = null
        hideKeyboard(ov)
        val remove = Runnable {
            root.removeView(ov)
            if (labelsDirty) { labelsDirty = false; rebuild() }
        }
        if (animate) {
            if (f != null && ov is ViewGroup && ov.childCount > 0) ov.getChildAt(0).animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(180).start()
            else ov.animate().translationY(dp(40).toFloat()).setDuration(180).start()
            ov.animate().alpha(0f).setDuration(190).withEndAction(remove).start()
        } else remove.run()
    }

    // ---------- laci aplikasi ----------
    private fun makeDrawerCell(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(58), dp(58)))
        box.addView(TextView(this).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
            setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5); leftMargin = dp(2); rightMargin = dp(2) })
        return box
    }

    private fun openDrawer(focus: Boolean = false) {
        closeOverlay(false)
        val ov = FrameLayout(this)
        ov.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(HomeWall.blur) }, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.addView(View(this).apply { setBackgroundColor(Color.argb(130, 0, 0, 0)) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), topInset + dp(10), dp(12), 0) }
        val search = EditText(this).apply {
            hint = "Cari aplikasi"; setHintTextColor(0xB3FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 16f
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH; setPadding(dp(20), 0, dp(20), 0)
        }
        search.background = GlassPanelDrawable(search, dp(26).toFloat(), 1.2f)
        col.addView(search, LinearLayout.LayoutParams(MATCH, dp(52)))

        val all = AppRepo.list(this)
        val shown = ArrayList<AppEntry>(all)
        val ad = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val e = shown[position]
                val v = (convertView as? LinearLayout) ?: makeDrawerCell()
                (v.getChildAt(0) as ImageView).setImageBitmap(FolderIcons.tile(this@HomeActivity, e.pkg, mono, false))
                (v.getChildAt(1) as TextView).text = e.label
                return v
            }
        }
        val grid = GridView(this).apply {
            numColumns = 4; verticalSpacing = dp(12); selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(16), 0, botInset + dp(16)); clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
            adapter = ad
        }
        grid.setOnItemClickListener { _, v, pos, _ -> if (pos < shown.size) launch(shown[pos].pkg, v) }
        grid.setOnItemLongClickListener { _, v, pos, _ ->
            if (pos < shown.size) beginDrag(v, Payload(HItem(HItem.APP, pkg = shown[pos].pkg), null))
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                shown.clear()
                shown.addAll(if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) })
                ad.notifyDataSetChanged()
            }
        })
        search.setOnEditorActionListener { _, _, _ -> if (shown.isNotEmpty()) launch(shown[0].pkg); true }
        ov.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ov
        ov.alpha = 0f
        ov.translationY = dp(60).toFloat()
        ov.animate().alpha(1f).translationY(0f).setDuration(200).start()
        if (focus) search.postDelayed({
            search.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
        }, 260)
    }

    // ---------- menu layar, wallpaper, widget ----------
    // ---------- mode edit (tekan lama layar): halaman mengecil, tombol Wallpaper / Widget / Pengaturan ----------
    private fun iconBmp(kind: Int): Bitmap {
        val s = dp(26)
        val f = s.toFloat()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = f * 0.085f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val fl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        when (kind) {
            0 -> {
                c.drawRoundRect(RectF(f * 0.1f, f * 0.14f, f * 0.9f, f * 0.86f), f * 0.14f, f * 0.14f, st)
                val p = Path()
                p.moveTo(f * 0.14f, f * 0.76f); p.lineTo(f * 0.4f, f * 0.46f); p.lineTo(f * 0.58f, f * 0.64f); p.lineTo(f * 0.7f, f * 0.52f); p.lineTo(f * 0.86f, f * 0.74f)
                c.drawPath(p, st)
                c.drawCircle(f * 0.68f, f * 0.32f, f * 0.07f, fl)
            }
            1 -> {
                c.drawRoundRect(RectF(f * 0.12f, f * 0.12f, f * 0.45f, f * 0.45f), f * 0.07f, f * 0.07f, st)
                c.drawRoundRect(RectF(f * 0.12f, f * 0.55f, f * 0.45f, f * 0.88f), f * 0.07f, f * 0.07f, st)
                c.drawRoundRect(RectF(f * 0.55f, f * 0.55f, f * 0.88f, f * 0.88f), f * 0.07f, f * 0.07f, st)
                c.save()
                c.rotate(45f, f * 0.715f, f * 0.285f)
                c.drawRoundRect(RectF(f * 0.55f, f * 0.12f, f * 0.88f, f * 0.45f), f * 0.07f, f * 0.07f, st)
                c.restore()
            }
            else -> {
                for (i in 0..2) {
                    val y = f * (0.24f + i * 0.26f)
                    c.drawLine(f * 0.12f, y, f * 0.88f, y, st)
                    c.drawCircle(f * (0.34f + ((i * 2) % 3) * 0.16f), y, f * 0.085f, fl)
                }
            }
        }
        return b
    }

    private fun editButton(label: String, kind: Int, action: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val ic = ImageView(this).apply { setImageBitmap(iconBmp(kind)); scaleType = ImageView.ScaleType.CENTER }
        ic.background = GlassPanelDrawable(ic, dp(30).toFloat(), 1f, 56, 0.4f)
        box.addView(ic, LinearLayout.LayoutParams(dp(60), dp(60)))
        box.addView(TextView(this).apply {
            text = label; textSize = 12.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        box.setOnClickListener { action() }
        return box
    }

    private fun enterEdit() {
        if (editing || overlay != null || sheet != null || drag != null) return
        editing = true
        pager.pivotX = pageW / 2f
        pager.pivotY = pageH * 0.4f
        pager.animate().scaleX(0.84f).scaleY(0.84f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
        for (v in listOf<View>(dockPanel, pill, dots)) v.animate().alpha(0f).setDuration(180).start()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0) }
        bar.addView(editButton("Wallpaper", 0) { exitEdit(); pickWallpaper() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(editButton("Widget", 1) { openWidgetSheet() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(editButton("Pengaturan", 2) { exitEdit(); showSettings() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = botInset + dp(30) })
        editBar = bar
        bar.alpha = 0f
        bar.translationY = dp(30).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        pager.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun exitEdit() {
        if (!editing) return
        editing = false
        pager.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator(1.6f)).start()
        for (v in listOf<View>(dockPanel, pill, dots)) v.animate().alpha(1f).setDuration(220).start()
        val bar = editBar
        editBar = null
        if (bar != null) bar.animate().alpha(0f).translationY(dp(30).toFloat()).setDuration(160).withEndAction { root.removeView(bar) }.start()
    }

    // ---------- pemilih widget (bottom sheet): cari, kelompok per aplikasi, pratinjau, ukuran ----------
    private class WGroup(val pkg: String, val label: String, val icon: Drawable?, val items: List<AppWidgetProviderInfo>)

    private val previewCache = HashMap<String, Drawable?>()

    private fun loadGroups(q: String): List<WGroup> {
        val pm = packageManager
        val byPkg = LinkedHashMap<String, MutableList<AppWidgetProviderInfo>>()
        for (i in awm.installedProviders) byPkg.getOrPut(i.provider.packageName) { ArrayList() }.add(i)
        val ql = q.trim().lowercase()
        val out = ArrayList<WGroup>()
        for ((pkg, list) in byPkg) {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val items = if (ql.isEmpty() || label.lowercase().contains(ql)) list else list.filter { it.loadLabel(pm).lowercase().contains(ql) }
            if (items.isEmpty()) continue
            out.add(WGroup(pkg, label, runCatching { pm.getApplicationIcon(pkg) }.getOrNull(), items.sortedBy { it.loadLabel(pm).lowercase() }))
        }
        out.sortWith(compareBy<WGroup>({ it.pkg != packageName }, { it.label.lowercase() }))
        return out
    }

    private fun cellsOf(i: AppWidgetProviderInfo): String {
        var w = Math.ceil((i.minWidth + 30) / 70.0).toInt()
        var h = Math.ceil((i.minHeight + 30) / 70.0).toInt()
        if (Build.VERSION.SDK_INT >= 31 && i.targetCellWidth > 0 && i.targetCellHeight > 0) { w = i.targetCellWidth; h = i.targetCellHeight }
        return "$w × $h"
    }

    private fun previewOf(i: AppWidgetProviderInfo): Drawable? {
        val k = i.provider.flattenToString()
        if (previewCache.containsKey(k)) return previewCache[k]
        val d = runCatching { i.loadPreviewImage(this, 0) }.getOrNull() ?: runCatching { i.loadIcon(this, 0) }.getOrNull()
        previewCache[k] = d
        return d
    }

    private fun openWidgetSheet() {
        if (sheet != null) return
        closeOverlay(false)
        val gray = 0xFF9AA7AF.toInt()
        val ov = FrameLayout(this)
        ov.setBackgroundColor(Color.argb(110, 0, 0, 0))
        ov.setOnClickListener { closeSheet() }
        val h = (rootH * 0.86f).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
        val r = dp(28).toFloat()
        box.background = GradientDrawable().apply { setColor(0xFF12161A.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }

        val hbox = FrameLayout(this)
        hbox.addView(View(this).apply { background = GradientDrawable().apply { setColor(0x66FFFFFF.toInt()); cornerRadius = dp(2).toFloat() } }, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER))
        var downY = 0f
        hbox.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_MOVE -> { val dy = e.rawY - downY; if (dy > 0f) box.translationY = dy }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.rawY - downY > dp(110)) closeSheet() else box.animate().translationY(0f).setDuration(180).start()
                }
            }
            true
        }
        box.addView(hbox, LinearLayout.LayoutParams(MATCH, dp(30)))
        box.addView(TextView(this).apply {
            text = "Widget"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(8) })
        val search = EditText(this).apply {
            hint = "Telusuri widget"; setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 16f
            setSingleLine(true); setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply { setColor(0xFF2B3A42.toInt()); cornerRadius = dp(26).toFloat() }
        }
        box.addView(search, LinearLayout.LayoutParams(MATCH, dp(52)).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        var groups = loadGroups("")
        val lv = ExpandableListView(this)
        val ad = object : BaseExpandableListAdapter() {
            override fun getGroupCount() = groups.size
            override fun getChildrenCount(groupPosition: Int) = groups[groupPosition].items.size
            override fun getGroup(groupPosition: Int): Any = groups[groupPosition]
            override fun getChild(groupPosition: Int, childPosition: Int): Any = groups[groupPosition].items[childPosition]
            override fun getGroupId(groupPosition: Int) = groupPosition.toLong()
            override fun getChildId(groupPosition: Int, childPosition: Int) = (groupPosition * 1000 + childPosition).toLong()
            override fun hasStableIds() = false
            override fun isChildSelectable(groupPosition: Int, childPosition: Int) = true

            override fun getGroupView(groupPosition: Int, isExpanded: Boolean, convertView: View?, parent: ViewGroup?): View {
                val g = groups[groupPosition]
                val row = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
                row.background = GradientDrawable().apply { setColor(0xFF1F2A31.toInt()); cornerRadius = dp(24).toFloat() }
                row.addView(ImageView(this@HomeActivity).apply { setImageDrawable(g.icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(48), dp(48)))
                val col = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this@HomeActivity).apply { text = g.label; textSize = 18f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                col.addView(TextView(this@HomeActivity).apply { text = g.items.size.toString() + " widget"; textSize = 14f; setTextColor(gray) })
                row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(16) })
                row.addView(TextView(this@HomeActivity).apply { text = if (isExpanded) "⌃" else "⌄"; textSize = 22f; setTextColor(gray) })
                val wrap = FrameLayout(this@HomeActivity)
                wrap.setPadding(0, dp(4), 0, dp(4))
                wrap.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
                return wrap
            }

            override fun getChildView(groupPosition: Int, childPosition: Int, isLastChild: Boolean, convertView: View?, parent: ViewGroup?): View {
                val info = groups[groupPosition].items[childPosition]
                val box2 = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(12), dp(16), dp(18)) }
                val pv = ImageView(this@HomeActivity).apply {
                    adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; maxHeight = dp(230); setImageDrawable(previewOf(info))
                }
                pv.background = GradientDrawable().apply { setColor(0xFF2B3A42.toInt()); cornerRadius = dp(22).toFloat() }
                box2.addView(pv, LinearLayout.LayoutParams(WRAP, WRAP))
                box2.addView(TextView(this@HomeActivity).apply { text = info.loadLabel(packageManager); textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
                box2.addView(TextView(this@HomeActivity).apply { text = cellsOf(info); textSize = 14f; setTextColor(gray); gravity = Gravity.CENTER })
                val desc = if (Build.VERSION.SDK_INT >= 31) runCatching { info.loadDescription(this@HomeActivity)?.toString() }.getOrNull() else null
                if (!desc.isNullOrEmpty()) box2.addView(TextView(this@HomeActivity).apply { text = desc; textSize = 13f; setTextColor(gray); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
                box2.setOnClickListener { closeSheet(); exitEdit(); bindWidget(info) }
                return box2
            }
        }
        lv.setAdapter(ad)
        lv.setGroupIndicator(null)
        lv.divider = null
        lv.dividerHeight = 0
        lv.setChildDivider(ColorDrawable(Color.TRANSPARENT))
        lv.selector = ColorDrawable(Color.TRANSPARENT)
        lv.overScrollMode = View.OVER_SCROLL_NEVER
        lv.isVerticalScrollBarEnabled = false
        lv.clipToPadding = false
        lv.setPadding(dp(12), 0, dp(12), botInset + dp(16))
        box.addView(lv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                groups = loadGroups(q)
                ad.notifyDataSetChanged()
                if (q.isNotBlank()) for (i in 0 until ad.groupCount) lv.expandGroup(i)
            }
        })
        ov.addView(box, FrameLayout.LayoutParams(MATCH, h, Gravity.BOTTOM))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        sheet = ov
        ov.alpha = 0f
        ov.animate().alpha(1f).setDuration(200).start()
        box.translationY = h.toFloat()
        box.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun closeSheet() {
        val ov = sheet ?: return
        sheet = null
        hideKeyboard(ov)
        val box = ov.getChildAt(0)
        box.animate().translationY(box.height.toFloat()).setDuration(240).start()
        ov.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(ov) }.start()
    }

    private fun centerRect(): Rect = Rect(rootW / 2, rootH / 2, rootW / 2, rootH / 2)

    private fun showSettings() {
        val items = ArrayList<FlowUi.Item>()
        items.add(FlowUi.Item("Ikon: " + (if (mono) "Kaca putih" else "iOS (warna)"), 6) {
            mono = !mono
            getSharedPreferences(SP, MODE_PRIVATE).edit().putBoolean("mono", mono).apply()
            lastSig = GlassPrefs.sig(this) + "|" + mono
            rebuild(); warmIcons()
        })
        items.add(FlowUi.Item("Nama ikon: " + (if (showLabels) "Tampil" else "Sembunyi"), 0) {
            showLabels = !showLabels
            getSharedPreferences(SP, MODE_PRIVATE).edit().putBoolean("labels", showLabels).apply()
            rebuild()
        })
        items.add(FlowUi.Item("Atur folder", 7) { manageFolders() })
        items.add(FlowUi.Item("Susun semua aplikasi", 1) {
            val n = HomeStore.fillAll(this, HomeStore.MAX_PAGES)
            HomeStore.save(this); rebuild()
            FlowUi.toast(this, if (n > 0) "$n aplikasi ditambahkan" else "Semua aplikasi sudah ada di layar")
        })
        items.add(FlowUi.Item("Blur dan gaya kaca", 6) { startActivity(Intent(this, WidgetStyleActivity::class.java)) })
        items.add(FlowUi.Item("Jadikan launcher utama", 4) { requestHome() })
        items.add(FlowUi.Item("Pengaturan Flow Launcher", 6) { startActivity(Intent(this, MainActivity::class.java)) })
        FlowUi.menu(this, centerRect(), items, true)
    }

    private fun manageFolders() {
        val fs = HomeStore.all().filter { it.type == HItem.FOLDER }
        if (fs.isEmpty()) {
            FlowUi.toast(this, "Belum ada folder. Tumpuk satu ikon di atas ikon lain untuk membuat folder.")
            return
        }
        val items = ArrayList<FlowUi.Item>()
        for (f in fs) items.add(FlowUi.Item(f.name.ifEmpty { "Folder" } + " (" + f.apps.size + ")", 7) { editFolderApps(f) })
        FlowUi.menu(this, centerRect(), items, true)
    }

    // ---------- menu tekan lama pada ikon / folder / widget ----------
    private fun onItemLong(v: View, item: HItem) {
        if (drag != null) return
        beginDrag(v, Payload(item, null), true)
        if (drag != null) showItemMenu(v, item)
    }

    private fun showDragBar() {
        if (dragBar.visibility == View.VISIBLE) return
        dragBar.alpha = 0f
        dragBar.visibility = View.VISIBLE
        dragBar.animate().alpha(1f).setDuration(150).start()
    }

    private fun hideItemMenu() {
        val d = itemMenuDismiss ?: return
        itemMenuDismiss = null
        d()
    }

    /** Dipanggil saat jari mulai bergeser: menu popup disembunyikan dan bilah Hapus/Info/Copot muncul. */
    private fun noteDragMove(v: View, e: DragEvent) {
        if (menuHidden) return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val x = loc[0] + e.x
        val y = loc[1] + e.y
        if (dragX0.isNaN()) { dragX0 = x; dragY0 = y; return }
        if (Math.hypot((x - dragX0).toDouble(), (y - dragY0).toDouble()) > dp(14)) {
            menuHidden = true
            hideItemMenu()
            showDragBar()
        }
    }

    private fun showItemMenu(v: View, item: HItem) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        val items = ArrayList<FlowUi.Item>()
        when (item.type) {
            HItem.FOLDER -> {
                items.add(FlowUi.Item("Sunting", 0) { renameItem(item) })
                items.add(FlowUi.Item("Urutan", 1) { sortFolder(item) })
                items.add(FlowUi.Item("Pilih Apl", 2) { editFolderApps(item) })
                items.add(FlowUi.Item("Hapus", 3) { removeItem(item) })
            }
            HItem.APP -> {
                items.add(FlowUi.Item("Sunting", 0) { renameItem(item) })
                items.add(FlowUi.Item("Info aplikasi", 4) { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HomeUtil.uriOfPkg(item.pkg))) } })
                items.add(FlowUi.Item("Copot", 5) { runCatching { startActivity(Intent(Intent.ACTION_DELETE, HomeUtil.uriOfPkg(item.pkg))) } })
                items.add(FlowUi.Item("Hapus", 3) { removeItem(item) })
            }
            else -> items.add(FlowUi.Item("Hapus", 3) { removeItem(item) })
        }
        itemMenuDismiss = FlowUi.menu(this, r, items, false) { itemMenuDismiss = null }
    }

    private fun renameItem(item: HItem) {
        val isFolder = item.type == HItem.FOLDER
        val cur = if (isFolder) item.name.ifEmpty { "Folder" } else item.name.ifEmpty { appLabel(item.pkg) }
        FlowUi.input(this, if (isFolder) "Nama folder" else "Nama ikon", cur, if (isFolder) "Folder" else appLabel(item.pkg), "Simpan") { t ->
            val n = t.trim()
            item.name = if (isFolder) n.ifEmpty { "Folder" } else (if (n == appLabel(item.pkg)) "" else n)
            HomeStore.save(this)
            val snap = snapshot()
            rebuild()
            pager.post { animateFrom(snap, null, -1f, -1f, -1, item) }
        }
    }

    private fun sortFolder(f: HItem) {
        val snap = snapshot()
        f.apps.sortBy { appLabel(it).lowercase() }
        HomeStore.save(this)
        rebuild()
        pager.post { animateFrom(snap, null, -1f, -1f, -1, f) }
        FlowUi.toast(this, "Folder diurutkan A–Z")
    }

    private fun editFolderApps(f: HItem) {
        if (f.wid < 0) { f.wid = HomeStore.nextId(); HomeStore.save(this) }
        startActivity(Intent(this, FolderConfigActivity::class.java).putExtra("home_folder", f.wid))
    }

    private fun findViewFor(item: HItem): View? {
        for (p in 0 until pagesBox.childCount) {
            val page = pagesBox.getChildAt(p) as? ViewGroup ?: continue
            for (i in 0 until page.childCount) if (page.getChildAt(i).tag === item) return page.getChildAt(i)
        }
        for (i in 0 until dockRow.childCount) {
            val cell = dockRow.getChildAt(i) as? ViewGroup ?: continue
            if (cell.childCount > 0 && cell.getChildAt(0).tag === item) return cell.getChildAt(0)
        }
        return null
    }

    /** Hapus dari layar dengan animasi mengecil. */
    private fun removeItem(item: HItem) {
        if (item.type == HItem.WIDGET && item.wid >= 0) runCatching { host.deleteAppWidgetId(item.wid) }
        val done = Runnable {
            HomeStore.remove(item)
            HomeStore.trim()
            HomeStore.save(this)
            rebuild()
        }
        val v = findViewFor(item)
        if (v == null) done.run()
        else v.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(200).withEndAction(done).start()
    }

    private fun pickWallpaper() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        runCatching { startActivityForResult(i, REQ_WALL) }
    }

    private fun setWallpaper(uri: Uri) {
        val w = rootW
        val h = rootH
        Thread {
            val ok = HomeWall.saveFromUri(this, uri)
            if (ok) HomeWall.build(this, w, h)
            runOnUiThread {
                if (ok) { wallImg.setImageBitmap(HomeWall.full); invalidateGlass() }
                else FlowUi.toast(this, "Gambar tidak bisa dibuka")
            }
        }.start()
    }

    private fun bindWidget(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        pendWid = id
        pendInfo = info
        val ok = runCatching { awm.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        if (ok) configureOrAdd(id, info)
        else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            try { startActivityForResult(i, REQ_BIND) } catch (e: Exception) { host.deleteAppWidgetId(id) }
        }
    }

    private fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            val ok = try { host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null); true } catch (e: Exception) { false }
            if (ok) return
        }
        addWidget(id, info)
    }

    private fun addWidget(id: Int, info: AppWidgetProviderInfo) {
        val d = resources.displayMetrics.density
        var sx = Math.ceil((info.minWidth / (cellW / d)).toDouble()).toInt()
        var sy = Math.ceil((info.minHeight / (cellH / d)).toDouble()).toInt()
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) { sx = info.targetCellWidth; sy = info.targetCellHeight }
        sx = sx.coerceIn(1, HomeStore.COLS); sy = sy.coerceIn(1, HomeStore.ROWS)
        val item = HItem(HItem.WIDGET, sx = sx, sy = sy, wid = id)
        val p = HomeStore.placeNear(item, curPage, 0, 0)
        pendWid = -1; pendInfo = null
        if (p < 0) {
            host.deleteAppWidgetId(id)
            FlowUi.toast(this, "Layar penuh")
            return
        }
        HomeStore.save(this)
        curPage = p
        rebuild()
    }

    private fun cancelPending() {
        if (pendWid >= 0) runCatching { host.deleteAppWidgetId(pendWid) }
        pendWid = -1; pendInfo = null
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_BIND -> { val pi = pendInfo; if (resultCode == RESULT_OK && pi != null) configureOrAdd(pendWid, pi) else cancelPending() }
            REQ_CONFIG -> { val pi = pendInfo; if (resultCode == RESULT_OK && pi != null) addWidget(pendWid, pi) else cancelPending() }
            REQ_ROLE -> { if (HomeUtil.isDefaultHome(this)) WidgetUtil.updateAll(this) }
            REQ_WALL -> { val u = data?.data; if (resultCode == RESULT_OK && u != null) setWallpaper(u) }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
