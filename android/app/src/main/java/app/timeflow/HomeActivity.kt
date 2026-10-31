package app.timeflow

import android.app.Activity
import android.app.AlertDialog
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
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.widget.BaseAdapter
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
        const val HOST_ID = 1024
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val FROM_PAGE = 0
        const val FROM_DOCK = 1
        const val DOCK_H = 84
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
        mono = getSharedPreferences(SP, MODE_PRIVATE).getBoolean("mono", true)
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
        val sp = getSharedPreferences(SP, MODE_PRIVATE)
        val def = HomeUtil.isDefaultHome(this)
        if (def != sp.getBoolean("was_default", false)) {
            sp.edit().putBoolean("was_default", def).apply()
            WidgetUtil.updateAll(this)          // widget TimeFlow ganti ke kaca peluncur (atau kembali ke latar tangkapan layar)
        }
        val sig = GlassPrefs.sig(this) + "|" + mono
        if (built() && sig != lastSig) { lastSig = sig; applyWall(true) }
        warmIcons()
    }

    override fun onStop() {
        super.onStop()
        runCatching { host.stopListening() }
        runCatching { unregisterReceiver(pkgRx) }
        HomeStore.save(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (overlay != null) closeOverlay(false)
        curPage = 0
        if (pageW > 0) pager.goTo(0, true)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (overlay != null) closeOverlay(true)
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
        wallImg = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(wallImg, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(View(this).apply { setBackgroundColor(Color.argb(36, 0, 0, 0)) }, FrameLayout.LayoutParams(MATCH, MATCH))

        pagesBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pager = HPager(this)
        pager.onPage = { p -> curPage = p; updateDots() }
        pager.onScroll = { for (v in glassViews) v.invalidate() }
        pager.addView(pagesBox, FrameLayout.LayoutParams(WRAP, MATCH))
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(dots, FrameLayout.LayoutParams(MATCH, dp(16), Gravity.BOTTOM))

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
        val top = topInset + dp(6)
        pageW = rootW
        pageH = rootH - top - (dockBottom + dockH + dp(30))
        if (pageH < dp(200)) return
        cellW = pageW / HomeStore.COLS
        cellH = pageH / HomeStore.ROWS
        (pager.layoutParams as FrameLayout.LayoutParams).apply { width = MATCH; height = pageH; topMargin = top }
        pager.requestLayout()
        pager.pageW = pageW
        (dots.layoutParams as FrameLayout.LayoutParams).bottomMargin = dockBottom + dockH + dp(8)
        (dockPanel.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dockBottom }
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
                askWallpaperOnce()
            }
        }.start()
    }

    /** Bila wallpaper sistem tidak bisa dibaca (Android baru), tawarkan memilih wallpaper dari galeri satu kali. */
    private fun askWallpaperOnce() {
        val sp = getSharedPreferences(SP, MODE_PRIVATE)
        if (!HomeWall.fallback || sp.getBoolean("asked_wall", false) || isFinishing) return
        sp.edit().putBoolean("asked_wall", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Pilih wallpaper")
            .setMessage("Flow Launcher memakai wallpaper sendiri supaya kaca dan blur akurat. Pilih gambar dari galeri sekarang?")
            .setPositiveButton("Pilih") { _, _ -> pickWallpaper() }
            .setNegativeButton("Nanti", null)
            .show()
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
        page.setOnLongClickListener { showHomeMenu(); true }
        page.setOnDragListener { v, e -> onPageDrag(v, e, p) }
        for (item in ArrayList<HItem>(HomeStore.pages[p])) {
            val v = makeItemView(item, FROM_PAGE) ?: continue
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
        else FolderIcons.mini(this, item.apps.take(4), mono, false)

    private fun makeItemView(item: HItem, from: Int): View? {
        if (item.type == HItem.WIDGET) return makeWidget(item)
        val pm = packageManager
        if (item.type == HItem.APP && pm.getLaunchIntentForPackage(item.pkg) == null) return null
        if (item.type == HItem.FOLDER) {
            item.apps.retainAll { pm.getLaunchIntentForPackage(it) != null }
            if (item.apps.isEmpty()) return null
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val isz = dp(if (from == FROM_DOCK) 58 else 56)
        box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(iconFor(item)) }, LinearLayout.LayoutParams(isz, isz))
        if (from != FROM_DOCK && showLabels) {
            box.addView(TextView(this).apply {
                text = if (item.type == HItem.APP) appLabel(item.pkg) else item.name.ifEmpty { "Folder" }
                textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5); leftMargin = dp(2); rightMargin = dp(2) })
        }
        box.setOnClickListener { if (item.type == HItem.APP) launch(item.pkg) else openFolder(item) }
        box.setOnLongClickListener { beginDrag(box, Payload(item, null)); true }
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
        frame.onLong = { beginDrag(frame, Payload(item, null)) }
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
        dockPanel.background = GlassPanelDrawable(dockPanel, dp(32).toFloat())
        for (s in 0..HomeStore.DOCK) {
            val cell = FrameLayout(this)
            if (s < HomeStore.DOCK) {
                val item = HomeStore.dock.firstOrNull { it.cx == s }
                val v = if (item != null) makeItemView(item, FROM_DOCK) else null
                if (v != null) cell.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
            } else {
                val box = LinearLayout(this).apply { gravity = Gravity.CENTER }
                box.addView(ImageView(this).apply {
                    setImageBitmap(drawerButtonBmp()); contentDescription = "Semua aplikasi"
                }, LinearLayout.LayoutParams(dp(58), dp(58)))
                box.setOnClickListener { openDrawer() }
                cell.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
            }
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

    private fun launch(pkg: String) {
        val li = packageManager.getLaunchIntentForPackage(pkg) ?: return
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(li) }
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

    private fun beginDrag(v: View, p: Payload) {
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
        dragBar.visibility = View.VISIBLE
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
            DragEvent.ACTION_DRAG_LOCATION -> setEdge(if (e.x < dp(36)) -1 else if (e.x > v.width - dp(36)) 1 else 0)
            DragEvent.ACTION_DRAG_EXITED -> setEdge(0)
            DragEvent.ACTION_DROP -> {
                val pl = drag ?: return false
                val cx = (e.x / cellW).toInt().coerceIn(0, HomeStore.COLS - 1)
                val cy = (e.y / cellH).toInt().coerceIn(0, HomeStore.ROWS - 1)
                setEdge(0)
                ui.post { dropOnGrid(pl, p, cx, cy) }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag()
        }
        return true
    }

    private fun onDockDrag(v: View, e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> { val d = drag; return d != null && d.item.type != HItem.WIDGET }
            DragEvent.ACTION_DROP -> {
                val pl = drag ?: return false
                val slot = (e.x / (v.width / (HomeStore.DOCK + 1f))).toInt().coerceIn(0, HomeStore.DOCK - 1)
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
    }

    private fun dropOnGrid(pl: Payload, p: Int, cx: Int, cy: Int) {
        val item = pl.item
        if (p >= HomeStore.pages.size) return
        val list = HomeStore.pages[p]
        val target = list.firstOrNull { it !== item && cx >= it.cx && cx < it.cx + it.sx && cy >= it.cy && cy < it.cy + it.sy }
        if (pl.folder != null && target === pl.folder) return
        if (target != null && item.type == HItem.APP && target.type == HItem.APP && target.pkg != item.pkg) {
            detach(pl); makeFolder(target, item)
        } else if (target != null && item.type == HItem.APP && target.type == HItem.FOLDER) {
            detach(pl)
            if (item.pkg !in target.apps) target.apps.add(item.pkg)
        } else {
            detach(pl)
            if (HomeStore.cellFree(list, cx, cy, item.sx, item.sy, null)) { item.cx = cx; item.cy = cy; list.add(item) }
            else if (HomeStore.placeNear(item, p, cx, cy) < 0) Toast.makeText(this, "Layar penuh", Toast.LENGTH_SHORT).show()
        }
        HomeStore.save(this)
        rebuild()
    }

    private fun dropOnDock(pl: Payload, slot: Int) {
        val item = pl.item
        val target = HomeStore.dock.firstOrNull { it.cx == slot && it !== item }
        if (pl.folder != null && target === pl.folder) return
        if (target != null && item.type == HItem.APP && target.type == HItem.APP && target.pkg != item.pkg) {
            detach(pl); makeFolder(target, item)
        } else if (target != null && item.type == HItem.APP && target.type == HItem.FOLDER) {
            detach(pl)
            if (item.pkg !in target.apps) target.apps.add(item.pkg)
        } else {
            detach(pl)
            if (!HomeStore.placeDock(item, slot)) {
                HomeStore.placeNear(item, 0, 0, 0)
                Toast.makeText(this, "Dock penuh", Toast.LENGTH_SHORT).show()
            }
        }
        HomeStore.save(this)
        rebuild()
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
    private fun openFolder(f: HItem) {
        closeOverlay(false)
        val ov = FrameLayout(this)
        ov.setBackgroundColor(Color.argb(110, 0, 0, 0))
        ov.setOnClickListener { closeOverlay(true) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(18)); isClickable = true
        }
        panel.background = GlassPanelDrawable(panel, dp(32).toFloat(), 1.15f)
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
        for (row in f.apps.toList().chunked(4)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (pkg in row) line.addView(folderCell(f, pkg), LinearLayout.LayoutParams(0, WRAP, 1f))
            for (i in row.size until 4) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            panel.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        ov.addView(panel, FrameLayout.LayoutParams((rootW * 0.9f).toInt(), WRAP, Gravity.CENTER))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ov
        ov.alpha = 0f
        ov.animate().alpha(1f).setDuration(140).start()
        panel.scaleX = 0.92f; panel.scaleY = 0.92f
        panel.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun folderCell(f: HItem, pkg: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        box.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(FolderIcons.tile(this@HomeActivity, pkg, mono, false)) }, LinearLayout.LayoutParams(dp(60), dp(60)))
        box.addView(TextView(this).apply {
            text = appLabel(pkg); textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
            setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        box.setOnClickListener { launch(pkg) }
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
        if (animate) ov.animate().alpha(0f).setDuration(120).withEndAction(remove).start() else remove.run()
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

    private fun openDrawer() {
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
        grid.setOnItemClickListener { _, _, pos, _ -> if (pos < shown.size) launch(shown[pos].pkg) }
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
    }

    // ---------- menu layar, wallpaper, widget ----------
    private fun showHomeMenu() {
        val items = arrayOf("Tambah widget", "Ganti wallpaper", "Susun semua aplikasi ke layar", "Pengaturan peluncur")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> pickWidget()
                1 -> pickWallpaper()
                2 -> {
                    val n = HomeStore.fillAll(this, HomeStore.MAX_PAGES)
                    HomeStore.save(this); rebuild()
                    Toast.makeText(this, if (n > 0) "$n aplikasi ditambahkan" else "Semua aplikasi sudah ada di layar", Toast.LENGTH_SHORT).show()
                }
                else -> showSettings()
            }
        }.show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Gaya ikon: " + (if (mono) "Kaca putih" else "Warna asli") + " (ketuk untuk ganti)",
            "Nama ikon: " + (if (showLabels) "Tampil" else "Sembunyi") + " (ketuk untuk ganti)",
            "Blur dan gaya kaca…",
            "Jadikan peluncur bawaan…",
            "Buka pengaturan Flow Launcher"
        )
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    mono = !mono
                    getSharedPreferences(SP, MODE_PRIVATE).edit().putBoolean("mono", mono).apply()
                    lastSig = GlassPrefs.sig(this) + "|" + mono
                    rebuild(); warmIcons()
                }
                1 -> {
                    showLabels = !showLabels
                    getSharedPreferences(SP, MODE_PRIVATE).edit().putBoolean("labels", showLabels).apply()
                    rebuild()
                }
                2 -> startActivity(Intent(this, WidgetStyleActivity::class.java))
                3 -> runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                else -> startActivity(Intent(this, MainActivity::class.java))
            }
        }.show()
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
                else Toast.makeText(this, "Gambar tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun pickWidget() {
        val pm = packageManager
        val list = awm.installedProviders.sortedWith(compareBy<AppWidgetProviderInfo>({ it.provider.packageName != packageName }, { it.loadLabel(pm).lowercase() }))
        val names = Array(list.size) { i -> list[i].loadLabel(pm) + "  ·  " + appLabel(list[i].provider.packageName) }
        AlertDialog.Builder(this).setTitle("Pilih widget").setItems(names) { _, i -> bindWidget(list[i]) }.show()
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
            Toast.makeText(this, "Layar penuh", Toast.LENGTH_SHORT).show()
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
            REQ_WALL -> { val u = data?.data; if (resultCode == RESULT_OK && u != null) setWallpaper(u) }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
