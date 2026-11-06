package app.timeflow

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.AppOpsManager
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
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.DragEvent
import android.view.GestureDetector
import android.view.ScaleGestureDetector
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
        const val REQ_RECONF = 75
        const val REQ_BACKUP = 76
        const val REQ_RESTORE = 77
        const val REQ_HIDDEN = 78
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

    /** Bayangan seret: ikon terangkat (membesar sedikit) dan berpusat di jari. */
    private class LiftShadow(v: View) : View.DragShadowBuilder(v) {
        private val k = 1.18f
        override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
            val w = (view.width * k).toInt()
            val h = (view.height * k).toInt()
            size.set(w, h)
            touch.set(w / 2, h / 2)
        }
        override fun onDrawShadow(canvas: Canvas) {
            canvas.scale(k, k)
            view.draw(canvas)
        }
    }

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
    private val widgetViews = HashMap<Int, android.appwidget.AppWidgetHostView>()
    private val sizeSent = HashMap<Int, Int>()
    private var pendPlace: IntArray? = null
    private var pendSpan: IntArray? = null
    private var dragNewInfo: AppWidgetProviderInfo? = null
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
    private var scaleDet: ScaleGestureDetector? = null
    private var pinchAcc = 1f
    private var pinchFired = false
    private var pinchAt = 0L
    private var hiddenLayer: PullDownLayer? = null
    private var pickerLayer: View? = null
    private var hiddenRefresh: (() -> Unit)? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- siklus hidup ----------
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // pertama kali dipasang: tampilkan pengenalan; pengguna lama (sudah punya tata letak) tidak diganggu
        if (!OnboardingActivity.done(this)) {
            if (getSharedPreferences(SP, MODE_PRIVATE).contains("layout")) OnboardingActivity.markDone(this)
            else startActivity(Intent(this, OnboardingActivity::class.java))
        }
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
                if (android.os.SystemClock.uptimeMillis() - pinchAt < 700L || e2.pointerCount > 1) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                // gestur geser vertikal layar utama hanya berlaku di layar utama polos (bukan di atas laci, pemilih widget, mode edit, menu, atau widget yang bisa digulir)
                if (overlay != null || drag != null || sheet != null || editing || resizing != null || FlowUi.isShowing()) return false
                // jendela tanpa fokus / baru kembali fokus = bayangan notifikasi sistem sedang ditutup; jangan anggap geser itu gestur laci
                if (!hasWindowFocus() || android.os.SystemClock.uptimeMillis() - focusAt < 700L) return false
                if (onWidget(e1.rawX, e1.rawY)) return false
                if (dy < -dp(80) && Math.abs(dy) > Math.abs(dx) * 1.5f && vy < -600f) { openDrawer(); return true }
                if (dy > dp(80) && Math.abs(dy) > Math.abs(dx) * 1.5f && vy > 600f && e1.y < rootH * 0.6f) {
                    // geser turun: sisi kanan = Control Center (bila layanan aktif), selain itu panel notifikasi
                    if (e1.x > rootW * 0.6f && CcAccessibilityService.open(0)) return true
                    expandShade(); return true
                }
                return false
            }
        })
        scaleDet = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                pinchAcc = 1f
                pinchFired = false
                return pinchAllowed()
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (pinchFired) return true
                pinchAcc *= d.scaleFactor
                if (hiddenLayer == null && pinchAcc > 1.28f) {
                    // jari melebar (zoom in) di layar utama = buka Layar Tersembunyi
                    pinchFired = true
                    pinchAt = android.os.SystemClock.uptimeMillis()
                    cancelTouches()
                    root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    requestHidden()
                } else if (hiddenLayer != null && pickerLayer == null && pinchAcc < 0.78f) {
                    // jari menyempit (zoom out) di Layar Tersembunyi = tutup
                    pinchFired = true
                    pinchAt = android.os.SystemClock.uptimeMillis()
                    cancelTouches()
                    closeOverlay(true)
                }
                return true
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
        // dulu: muat ulang SEMUA aplikasi + periksa semuanya tiap kali layar utama muncul. Sekarang hanya bila ada paket berubah.
        Thread {
            val ch = AppRepo.refreshIfChanged(this)
            if (ch || !pruned) { pruned = true; runOnUiThread { if (!isFinishing && !isDestroyed) pruneMissing() } }
        }.start()
    }

    private var pruned = false

    override fun onResume() {
        super.onResume()
        if (HomeStore.dirty) { HomeStore.dirty = false; if (built()) rebuild() }
        val sp = getSharedPreferences(SP, MODE_PRIVATE)
        val def = HomeUtil.isDefaultHomeCached(this)
        if (def != sp.getBoolean("was_default", false)) {
            sp.edit().putBoolean("was_default", def).apply()
            WidgetUtil.updateAll(this)          // widget TimeFlow ganti ke kaca peluncur (atau kembali ke latar tangkapan layar)
        }
        mono = getSharedPreferences(SP, MODE_PRIVATE).getBoolean("mono", false)    // bisa berubah dari pengenalan/pengaturan
        val sig = GlassPrefs.sig(this) + "|" + mono
        if (built() && sig != lastSig) { lastSig = sig; applyWall(true) }
        warmIcons()
        if (built()) maybeAskDefault()
    }

    override fun onStop() {
        super.onStop()
        if (hiddenLayer != null) closeOverlay(false)      // keluar dari layar ini = terkunci lagi (buka berikutnya minta verifikasi)
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
        if (resizing != null) { endResize(); return }
        if (pickerLayer != null) { closePicker(); return }
        if (sheet != null) closeSheet()
        else if (overlay != null) closeOverlay(true)
        else if (editing) exitEdit()
        else if (curPage != 0 && pageW > 0) pager.goTo(0, true)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gd.onTouchEvent(ev)
        scaleDet?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private var focusAt = 0L

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusAt = android.os.SystemClock.uptimeMillis()
    }

    private fun built() = pageW > 0

    /** Apakah titik layar (x, y) berada di atas widget pada halaman aktif (widget bisa punya daftar yang digulir sendiri). */
    private fun onWidget(x: Float, y: Float): Boolean {
        if (curPage < 0 || curPage >= pagesBox.childCount) return false
        val page = pagesBox.getChildAt(curPage) as? ViewGroup ?: return false
        val loc = IntArray(2)
        for (i in 1 until page.childCount) {
            val c = page.getChildAt(i)
            val item = c.tag as? HItem ?: continue
            if (item.type != HItem.WIDGET) continue
            c.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + c.width && y >= loc[1] && y <= loc[1] + c.height) return true
        }
        return false
    }

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
                    HiddenStore.remove(c, pkg)
                    if (HomeStore.purgePkg(pkg)) { HomeStore.save(c); rebuild() }
                }
            }
        }
    }

    private var prunedAt = 0L

    private fun pruneMissing() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - prunedAt < 30000L) return
        prunedAt = now
        val pkgs = HashSet<String>()
        for (x in HomeStore.all()) {
            if (x.type == HItem.APP) pkgs.add(x.pkg)
            else if (x.type == HItem.FOLDER) pkgs.addAll(x.apps)
        }
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val pm = packageManager
            val gone = pkgs.filter { pm.getLaunchIntentForPackage(it) == null }
            if (gone.isNotEmpty()) runOnUiThread {
                var ch = false
                for (g in gone) if (HomeStore.purgePkg(g)) ch = true
                if (ch) { HomeStore.save(this); if (built()) rebuild() }
            }
        }.start()
    }

    private var warmedMono: Boolean? = null

    private fun warmIcons() {
        val m = mono
        if (warmedMono == m) return
        warmedMono = m
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            for (e in AppRepo.list(this)) FolderIcons.tile(this, e.pkg, m, false)
        }.start()
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

    // selama pengenalan belum selesai, jangan menimpa layarnya dengan dialog launcher utama (ada tombolnya di akhir pengenalan)
    private fun maybeAskDefault(): Boolean = OnboardingActivity.done(this) && HomeUtil.promptDefault(this, REQ_ROLE) { askWallpaperOnce() }

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
        stopJiggle(false)
        resizing = null; resizeUi = null
        hoverView = null; hoverKey = -1
        pagesBox.removeAllViews()
        for (p in HomeStore.pages.indices) pagesBox.addView(buildPage(p), LinearLayout.LayoutParams(pageW, pageH))
        buildDock()
        if (curPage >= HomeStore.pages.size) curPage = HomeStore.pages.size - 1
        updateDots()
        pager.post { pager.goTo(curPage, false) }
        if (editing) startJiggle()
    }

    private fun buildPage(p: Int): FrameLayout {
        val page = FrameLayout(this)
        page.isLongClickable = true
        page.setOnLongClickListener { if (!editing) enterEdit(); true }
        page.setOnClickListener { if (editing) exitEdit() else if (resizing != null) endResize() }
        page.setOnDragListener { v, e -> onPageDrag(v, e, p) }
        // sorotan sel tujuan saat menyeret (selalu anak pertama)
        val hl = View(this)       // penanda sel (tak terlihat; tata letak halaman mengandalkan anak pertama)
        hl.alpha = 0f
        page.addView(hl, FrameLayout.LayoutParams(cellW, cellH))
        for (item in ArrayList<HItem>(HomeStore.pages[p])) {
            val v = makeItemView(item, FROM_PAGE) ?: continue
            v.tag = item
            val lp = FrameLayout.LayoutParams(item.sx * cellW, item.sy * cellH)
            lp.leftMargin = item.cx * cellW
            lp.topMargin = item.cy * cellH
            if (item.type == HItem.WIDGET || (item.type == HItem.FOLDER && (item.sx > 2 || item.sy > 2))) {
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
        if (item.type == HItem.FOLDER && (item.sx > 2 || item.sy > 2) && from == FROM_PAGE) return makeBigFolder(item)
        val pm = packageManager
        if (item.type == HItem.APP && pm.getLaunchIntentForPackage(item.pkg) == null) return null
        if (item.type == HItem.FOLDER) {
            item.apps.retainAll { pm.getLaunchIntentForPackage(it) != null }
            if (item.apps.isEmpty()) return null
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val isz = if (from == FROM_DOCK) dp(62) else minOf(dp(62), (cellW * 1.4f).toInt())
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(iconFor(item)) }
        if (item.type == HItem.FOLDER && !mono) {
            // folder gaya iOS: kotak frosted (kaca asli dari wallpaper kabur) berisi 3x3 ikon mini
            iv.background = GlassPanelDrawable(iv, isz * 0.225f, 1f, 60, 0.4f)
            glassViews.add(iv)
        }
        box.addView(iv, LinearLayout.LayoutParams(isz, isz))
        if (from != FROM_DOCK && showLabels) {
            box.addView(TextView(this).apply {
                text = if (item.type == HItem.APP) item.name.ifEmpty { appLabel(item.pkg) } else item.name
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

    /** Folder berukuran besar: kartu kaca berisi ikon aplikasi langsung (ketuk = buka aplikasi), sel terakhir = sisa aplikasi. */
    private fun makeBigFolder(item: HItem): View? {
        val pm = packageManager
        item.apps.retainAll { pm.getLaunchIntentForPackage(it) != null }
        if (item.apps.isEmpty()) return null
        val frame = LongPressFrame(this)
        frame.background = GlassPanelDrawable(frame, dp(28).toFloat(), 1f, 54, 0.5f)
        glassViews.add(frame)
        val cols = maxOf(1, item.sx / 2)
        val rows = maxOf(1, item.sy / 2)
        val cells = cols * rows
        val shown = if (item.apps.size <= cells) item.apps.size else cells - 1
        val uw = (item.sx * cellW - dp(8)) / cols
        val uh = (item.sy * cellH - dp(8)) / rows
        val isz = minOf(dp(60), (minOf(uw, uh) * 0.62f).toInt())
        for (i in 0 until cells) {
            val col = i % cols
            val row = i / cols
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
            if (i < shown) {
                val pkg = item.apps[i]
                cell.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(FolderIcons.tile(this@HomeActivity, pkg, mono, false)) }, LinearLayout.LayoutParams(isz, isz))
                if (showLabels) cell.addView(TextView(this).apply {
                    text = appLabel(pkg); textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                    setShadowLayer(3f, 0f, 1f, 0x80000000.toInt())
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4); leftMargin = dp(2); rightMargin = dp(2) })
                cell.setOnClickListener { launch(pkg, cell) }
            } else if (i == shown && item.apps.size > cells) {
                val rest = item.apps.subList(shown, item.apps.size)
                val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(FolderIcons.mini(this@HomeActivity, rest.take(9), false, false, 144, false)) }
                iv.background = GradientDrawable().apply { setColor(Color.argb(60, 255, 255, 255)); cornerRadius = isz * 0.225f }
                cell.addView(iv, LinearLayout.LayoutParams(isz, isz))
                cell.setOnClickListener { openFolder(item, cell) }
            } else continue
            frame.addView(cell, FrameLayout.LayoutParams(uw, uh).apply { leftMargin = col * uw; topMargin = row * uh })
        }
        frame.setOnClickListener { openFolder(item, frame) }
        frame.onLong = { onItemLong(frame, item) }
        return frame
    }

    private fun makeWidget(item: HItem): View? {
        val info = awm.getAppWidgetInfo(item.wid) ?: return null
        val frame = LongPressFrame(this)
        // host view dipakai ulang antar rebuild (membuat ulang widget = menginflate RemoteViews lagi = patah)
        val hv = widgetViews[item.wid] ?: runCatching { host.createView(this, item.wid, info) }.getOrNull()?.also { widgetViews[item.wid] = it } ?: return null
        (hv.parent as? ViewGroup)?.removeView(hv)
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
            val key = w * 10000 + h
            if (sizeSent[item.wid] != key) {
                sizeSent[item.wid] = key
                runCatching { hv.updateAppWidgetSize(Bundle(), w, h, w, h) }
            }
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
        val started = v.startDragAndDrop(clip, LiftShadow(v), null, 0)
        if (!started) { drag = null; dragView = null; return }
        v.animate().alpha(0.22f).scaleX(0.88f).scaleY(0.88f).setDuration(170).setInterpolator(DecelerateInterpolator(1.5f)).start()
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
        dragView?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(240)?.setInterpolator(OvershootInterpolator(1.6f))?.start()
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

    /**
     * Sel tujuan dari posisi jari: jari = pusat item. Widget dan folder besar bebas per sub-sel (setengah sel);
     * ikon dan folder kecil menempel ke sel penuh.
     */
    private fun snapCell(item: HItem, x: Float, y: Float): IntArray {
        var cx = Math.round(x / cellW - item.sx / 2f)
        var cy = Math.round(y / cellH - item.sy / 2f)
        if (item.type != HItem.WIDGET && item.sx <= 2 && item.sy <= 2) {
            cx = Math.round(cx / 2f) * 2
            cy = Math.round(cy / 2f) * 2
        }
        cx = cx.coerceIn(0, maxOf(0, HomeStore.COLS - item.sx))
        cy = cy.coerceIn(0, maxOf(0, HomeStore.ROWS - item.sy))
        return intArrayOf(cx, cy)
    }

    private fun onPageDrag(v: View, e: DragEvent, p: Int): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return drag != null
            DragEvent.ACTION_DRAG_LOCATION -> {
                noteDragMove(v, e)
                setEdge(if (e.x < dp(36)) -1 else if (e.x > v.width - dp(36)) 1 else 0)
                val di = drag?.item
                if (di != null) {
                    val sn = snapCell(di, e.x, e.y)
                    updateHover(v, p, (e.x / cellW).toInt(), (e.y / cellH).toInt(), sn[0], sn[1], di)
                }
            }
            DragEvent.ACTION_DRAG_EXITED -> { setEdge(0); clearHover(); (v as ViewGroup).getChildAt(0).animate().alpha(0f).setDuration(120).start() }
            DragEvent.ACTION_DROP -> {
                val pl = drag ?: return false
                val sn = snapCell(pl.item, e.x, e.y)
                val cx = sn[0]
                val cy = sn[1]
                val fcx = (e.x / cellW).toInt()
                val fcy = (e.y / cellH).toInt()
                val fx = e.x
                val fy = e.y
                setEdge(0)
                clearHover()
                (v as ViewGroup).getChildAt(0).animate().alpha(0f).setDuration(120).start()
                ui.post { dropOnGrid(pl, p, cx, cy, fcx, fcy, fx, fy) }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> { clearHover(); (v as ViewGroup).getChildAt(0).animate().alpha(0f).setDuration(120).start(); endDrag() }
        }
        return true
    }

    private var hoverView: View? = null
    private var hoverKey = -1

    private fun clearHover() {
        hoverKey = -1
        hoverView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start()
        hoverView = null
        for (pi in 0 until pagesBox.childCount) {
            val page = pagesBox.getChildAt(pi) as? ViewGroup ?: continue
            for (i in 1 until page.childCount) {
                val c = page.getChildAt(i)
                if (c.translationX != 0f || c.translationY != 0f) c.animate().translationX(0f).translationY(0f).setDuration(200).setInterpolator(OvershootInterpolator(2f)).start()
            }
        }
    }

    /** Saat menyeret: ikon yang akan ditumpuk membesar, ikon di sekitarnya bergeser menjauh seperti memberi ruang. (fcx, fcy) = sub-sel di bawah jari. */
    private fun updateHover(page: View, p: Int, fcx: Int, fcy: Int, cx: Int, cy: Int, dragged: HItem) {
        val key = p * 100000 + fcy * 100 + fcx
        if (key == hoverKey) return
        hoverKey = key
        val pg = page as? ViewGroup ?: return
        val list = HomeStore.pages[p]
        val target = list.firstOrNull { it !== dragged && fcx >= it.cx && fcx < it.cx + it.sx && fcy >= it.cy && fcy < it.cy + it.sy }
        val tv = if (target != null && dragged.type == HItem.APP && (target.type == HItem.APP || target.type == HItem.FOLDER)) findViewFor(target) else null
        if (hoverView !== tv) {
            hoverView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(160)?.start()
            hoverView = tv
            tv?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(240)?.setInterpolator(OvershootInterpolator(2.2f))?.start()
        }
        val tcx = cx + dragged.sx / 2f
        val tcy = cy + dragged.sy / 2f
        for (i in 1 until pg.childCount) {
            val c = pg.getChildAt(i)
            val it2 = c.tag as? HItem ?: continue
            if (it2 === dragged || c === tv) continue
            val dx = it2.cx + it2.sx / 2f - tcx
            val dy = it2.cy + it2.sy / 2f - tcy
            val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val d = dist / 2f                      // dalam satuan sel penuh
            var tx = 0f
            var ty = 0f
            if (d in 0.01f..1.7f && dist > 0.01f) {
                val push = (1f - d / 1.7f) * dp(10)
                tx = dx / dist * push; ty = dy / dist * push
            }
            c.animate().translationX(tx).translationY(ty).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
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
        target.name = ""
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
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(400).setInterpolator(OvershootInterpolator(1.6f)).start()
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
                    v.translationX = dx; v.translationY = dy; v.scaleX = 1.14f; v.scaleY = 1.14f
                    v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(0.8f)).start()
                } else if (old == null) popIn(v)
                else if (old[0] == 0 && old[1] == p) {
                    val dx = (old[2] - item.cx) * cellW.toFloat()
                    val dy = (old[3] - item.cy) * cellH.toFloat()
                    if (dx != 0f || dy != 0f) {
                        v.translationX = dx; v.translationY = dy
                        v.animate().translationX(0f).translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
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
                if (dx != 0f) { v.translationX = dx; v.animate().translationX(0f).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start() }
            } else popIn(v, 0.7f)
            if (item === bounce) {
                v.scaleX = 1.25f; v.scaleY = 1.25f
                v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
            }
        }
    }

    private fun dropOnGrid(pl: Payload, p: Int, cx: Int, cy: Int, fcx: Int, fcy: Int, fx: Float, fy: Float) {
        val item = pl.item
        if (p >= HomeStore.pages.size) return
        if (item.type == HItem.WIDGET && item.wid < 0) { placeNewWidget(item, p, cx, cy); return }
        val snap = snapshot()
        val list = HomeStore.pages[p]
        val target = list.firstOrNull { it !== item && fcx >= it.cx && fcx < it.cx + it.sx && fcy >= it.cy && fcy < it.cy + it.sy }
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
                if (item.type == HItem.WIDGET && item.wid >= 0) { widgetViews.remove(item.wid); runCatching { host.deleteAppWidgetId(item.wid) } }
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
            setText(f.name); hint = "Nama folder"; setHintTextColor(0x66FFFFFF); textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
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
        if (ov === hiddenLayer) {
            hiddenLayer = null
            hiddenRefresh = null
            pickerLayer?.let { root.removeView(it) }
            pickerLayer = null
            hideKeyboard(ov)
            restoreHome()
            val gone = Runnable { root.removeView(ov) }
            if (animate) ov.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(210).withEndAction(gone).start() else gone.run()
            return
        }
        val t = folderTitle
        val f = folderItem
        if (t != null && f != null) {
            val n = t.text.toString().trim()
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
            else ov.animate().translationY(ov.height.toFloat()).setDuration(230).start()
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
        val ov = PullDownLayer(this)
        ov.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(HomeWall.blur) }, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.addView(View(this).apply { setBackgroundColor(Color.argb(130, 0, 0, 0)) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), topInset + dp(10), dp(12), 0) }
        val search = EditText(this).apply {
            hint = "Cari aplikasi"; setHintTextColor(0xB3FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 16f
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH; setPadding(dp(20), 0, dp(20), 0)
        }
        search.background = GlassPanelDrawable(search, dp(26).toFloat(), 1.2f)
        col.addView(search, LinearLayout.LayoutParams(MATCH, dp(52)))

        val all = AppRepo.visible(this)
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
        ov.atTop = { grid.firstVisiblePosition == 0 && (grid.childCount == 0 || grid.getChildAt(0).top >= grid.paddingTop) }
        ov.onDismiss = { closeOverlay(true) }
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

    // ---------- Layar Tersembunyi: cubit melebar (zoom in) di layar utama ----------
    private fun pinchAllowed(): Boolean {
        if (hiddenLayer != null) return pickerLayer == null
        if (overlay != null || drag != null || sheet != null || editing || resizing != null || FlowUi.isShowing()) return false
        return hasWindowFocus()
    }

    /** Batalkan sentuhan yang sedang berjalan di semua tampilan anak (supaya cubitan tidak ikut mengetuk ikon). */
    private fun cancelTouches() {
        val t = android.os.SystemClock.uptimeMillis()
        val c = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(c)
        c.recycle()
    }

    /** Buka Layar Tersembunyi; bila HP punya kunci layar dan kunci Layar Tersembunyi nyala, minta PIN/pola/sidik jari dulu. */
    private fun requestHidden() {
        if (hiddenLayer != null || editing || !built()) return
        val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (HiddenStore.lockOn(this) && km != null && km.isDeviceSecure) {
            val i = runCatching { km.createConfirmDeviceCredentialIntent("Layar Tersembunyi", "Verifikasi untuk membuka aplikasi tersembunyi") }.getOrNull()
            val ok = i != null && runCatching { startActivityForResult(i, REQ_HIDDEN); true }.getOrDefault(false)
            if (!ok) FlowUi.toast(this, "Tidak bisa memverifikasi kunci layar")
            return
        }
        openHidden()
        if (HiddenStore.lockOn(this) && km?.isDeviceSecure != true) {
            FlowUi.toast(this, "Pasang PIN, pola, atau sidik jari di Pengaturan HP supaya Layar Tersembunyi terkunci")
        }
    }

    private fun restoreHome() {
        for (v in listOf<View>(pager, dockPanel, pill, dots)) {
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    private fun plusBmp(): Bitmap {
        val s = 144
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        FolderIcons.drawGlassTile(c, RectF(10f, 10f, s - 10f, s - 10f), false)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        c.drawLine(s / 2f, 46f, s / 2f, s - 46f, p)
        c.drawLine(46f, s / 2f, s - 46f, s / 2f, p)
        return b
    }

    private fun openHidden() {
        if (!built() || hiddenLayer != null) return
        closeOverlay(false)
        val ov = PullDownLayer(this)
        ov.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(HomeWall.blur) }, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.addView(View(this).apply { setBackgroundColor(Color.argb(150, 12, 8, 28)) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), topInset + dp(20), dp(12), 0) }
        col.addView(TextView(this).apply {
            text = "Layar Tersembunyi"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(TextView(this).apply {
            text = "Aplikasi di sini hilang dari layar utama, folder, dan laci. Ketuk + untuk menambah, tekan lama untuk mengeluarkan, cubit menyempit untuk menutup."
            textSize = 13f; setTextColor(Color.argb(200, 255, 255, 255)); gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); leftMargin = dp(20); rightMargin = dp(20) })

        val rows = ArrayList<AppEntry?>()
        fun reload() {
            rows.clear()
            val hid = HiddenStore.all(this).toHashSet()
            for (e in AppRepo.list(this)) if (e.pkg in hid) rows.add(e)
            rows.add(null)
        }
        reload()
        val plus = plusBmp()
        val ad = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int): Any? = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val e = rows[position]
                val v = (convertView as? LinearLayout) ?: makeDrawerCell()
                val iv = v.getChildAt(0) as ImageView
                val tv = v.getChildAt(1) as TextView
                if (e == null) { iv.setImageBitmap(plus); tv.text = "Tambah" }
                else { iv.setImageBitmap(FolderIcons.tile(this@HomeActivity, e.pkg, mono, false)); tv.text = e.label }
                return v
            }
        }
        val grid = GridView(this).apply {
            numColumns = 4; verticalSpacing = dp(12); selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(22), 0, dp(16)); clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
            adapter = ad
        }
        grid.setOnItemClickListener { _, v, pos, _ ->
            if (pos in rows.indices) {
                val e = rows[pos]
                if (e == null) openPicker() else launch(e.pkg, v)
            }
        }
        grid.setOnItemLongClickListener { _, v, pos, _ ->
            val e = if (pos in rows.indices) rows[pos] else null
            if (e != null) {
                val loc = IntArray(2)
                v.getLocationInWindow(loc)
                val r = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                FlowUi.menu(this, r, listOf(
                    FlowUi.Item("Buka", 8) { launch(e.pkg, v) },
                    FlowUi.Item("Keluarkan dari sini", 3) { unhideApp(e.pkg) }
                ), false)
            }
            true
        }
        col.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val lock = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0) }
        fun paintLock() {
            val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            lock.text = when {
                km?.isDeviceSecure != true -> "Kunci: pasang kunci layar HP dulu"
                HiddenStore.lockOn(this) -> "Kunci: Nyala"
                else -> "Kunci: Mati"
            }
        }
        paintLock()
        lock.background = GlassPanelDrawable(lock, dp(18).toFloat(), 1f, 50, 0.4f)
        lock.setOnClickListener {
            val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            if (km?.isDeviceSecure != true) {
                FlowUi.toast(this, "Pasang PIN, pola, atau sidik jari di Pengaturan HP supaya Layar Tersembunyi bisa dikunci")
            } else {
                HiddenStore.setLock(this, !HiddenStore.lockOn(this))
                paintLock()
            }
        }
        col.addView(lock, LinearLayout.LayoutParams(WRAP, dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = botInset + dp(20) })

        ov.atTop = { grid.firstVisiblePosition == 0 && (grid.childCount == 0 || grid.getChildAt(0).top >= grid.paddingTop) }
        ov.onDismiss = { closeOverlay(true) }
        ov.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ov
        hiddenLayer = ov
        hiddenRefresh = { reload(); ad.notifyDataSetChanged() }

        // animasi zoom: layar utama membesar dan memudar, Layar Tersembunyi muncul dari kecil
        ov.alpha = 0f; ov.scaleX = 0.86f; ov.scaleY = 0.86f
        ov.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(DecelerateInterpolator(1.6f)).start()
        for (v in listOf<View>(pager, dockPanel, pill, dots)) {
            v.animate().scaleX(1.14f).scaleY(1.14f).alpha(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.4f)).start()
        }
    }

    /** Pemilih aplikasi (centang beberapa) untuk disembunyikan. */
    private fun openPicker() {
        if (pickerLayer != null || hiddenLayer == null) return
        val ov = FrameLayout(this)
        ov.isClickable = true
        ov.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(HomeWall.blur) }, FrameLayout.LayoutParams(MATCH, MATCH))
        ov.addView(View(this).apply { setBackgroundColor(Color.argb(180, 8, 6, 20)) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), topInset + dp(14), dp(12), botInset + dp(12)) }
        col.addView(TextView(this).apply {
            text = "Pilih aplikasi yang disembunyikan"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        val search = EditText(this).apply {
            hint = "Cari aplikasi"; setHintTextColor(0xB3FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 16f
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_DONE; setPadding(dp(20), 0, dp(20), 0)
        }
        search.background = GlassPanelDrawable(search, dp(26).toFloat(), 1.2f)
        col.addView(search, LinearLayout.LayoutParams(MATCH, dp(52)))

        val all = AppRepo.visible(this)
        val shown = ArrayList<AppEntry>(all)
        val picked = LinkedHashSet<String>()
        val btn = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(0x55FFFFFF); cornerRadius = dp(24).toFloat() }
        }
        fun paintBtn() {
            btn.text = if (picked.isEmpty()) "Pilih aplikasi" else "Sembunyikan " + picked.size + " aplikasi"
            btn.alpha = if (picked.isEmpty()) 0.5f else 1f
        }
        paintBtn()
        val ad = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val e = shown[position]
                val fl = (convertView as? FrameLayout) ?: FrameLayout(this@HomeActivity).also { f ->
                    f.addView(makeDrawerCell(), FrameLayout.LayoutParams(MATCH, WRAP))
                    f.addView(TextView(this@HomeActivity).apply {
                        text = "✓"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        typeface = Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF34C759.toInt()) }
                    }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { topMargin = dp(2); rightMargin = dp(10) })
                }
                val cell = fl.getChildAt(0) as LinearLayout
                (cell.getChildAt(0) as ImageView).setImageBitmap(FolderIcons.tile(this@HomeActivity, e.pkg, mono, false))
                (cell.getChildAt(1) as TextView).text = e.label
                val on = e.pkg in picked
                fl.getChildAt(1).visibility = if (on) View.VISIBLE else View.GONE
                cell.alpha = if (on) 0.55f else 1f
                return fl
            }
        }
        val grid = GridView(this).apply {
            numColumns = 4; verticalSpacing = dp(12); selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(16), 0, dp(12)); clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
            adapter = ad
        }
        grid.setOnItemClickListener { _, _, pos, _ ->
            if (pos < shown.size) {
                val p = shown[pos].pkg
                if (!picked.add(p)) picked.remove(p)
                paintBtn()
                ad.notifyDataSetChanged()
            }
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
        btn.setOnClickListener {
            if (picked.isNotEmpty()) {
                val n = picked.size
                hideApps(picked.toList())
                closePicker()
                hiddenRefresh?.invoke()
                FlowUi.toast(this, "$n aplikasi disembunyikan")
            }
        }
        col.addView(btn, LinearLayout.LayoutParams(MATCH, dp(50)).apply { topMargin = dp(6) })
        col.addView(TextView(this).apply {
            text = "Batal"; textSize = 15f; setTextColor(Color.argb(220, 255, 255, 255)); gravity = Gravity.CENTER
            setOnClickListener { closePicker() }
        }, LinearLayout.LayoutParams(MATCH, dp(44)).apply { topMargin = dp(4) })
        ov.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        pickerLayer = ov
        ov.alpha = 0f
        ov.translationY = dp(40).toFloat()
        ov.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun closePicker() {
        val p = pickerLayer ?: return
        pickerLayer = null
        hideKeyboard(p)
        p.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(200).withEndAction { root.removeView(p) }.start()
    }

    /** Sembunyikan aplikasi: masuk daftar tersembunyi dan langsung hilang dari layar utama, dock, dan folder (laci otomatis menyaring). */
    private fun hideApps(pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        HiddenStore.add(this, pkgs)
        for (p in pkgs) HomeStore.purgePkg(p)
        HomeStore.trim()
        HomeStore.save(this)
        rebuild()
    }

    private fun unhideApp(pkg: String) {
        if (HiddenStore.remove(this, pkg)) {
            hiddenRefresh?.invoke()
            FlowUi.toast(this, appLabel(pkg) + " kembali ke laci aplikasi")
        }
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
        startJiggle()
        bar.alpha = 0f
        bar.translationY = dp(30).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
        pager.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun exitEdit() {
        if (!editing) return
        editing = false
        stopJiggle(true)
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
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(2) })
        box.addView(TextView(this).apply {
            text = "Tekan lama sebuah widget, lalu seret ke tempat di layar yang kamu mau."
            textSize = 13f; setTextColor(gray)
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
                box2.setOnLongClickListener { beginWidgetDrag(box2, info); true }
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
        items.add(FlowUi.Item("Layar tersembunyi", 9) { requestHidden() })
        items.add(FlowUi.Item("Susun semua aplikasi", 1) {
            val n = HomeStore.fillAll(this, HomeStore.MAX_PAGES)
            HomeStore.save(this); rebuild()
            FlowUi.toast(this, if (n > 0) "$n aplikasi ditambahkan" else "Semua aplikasi sudah ada di layar")
        })
        items.add(FlowUi.Item("Blur dan gaya kaca", 6) { startActivity(Intent(this, WidgetStyleActivity::class.java)) })
        items.add(FlowUi.Item("Cadangan dan izin", 7) { showBackupMenu() })
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
        for (f in fs) items.add(FlowUi.Item(f.name.ifEmpty { "Folder tanpa nama" } + " (" + f.apps.size + ")", 7) { editFolderApps(f) })
        FlowUi.menu(this, centerRect(), items, true)
    }

    // ---------- menu tekan lama pada ikon / folder / widget ----------
    private fun onItemLong(v: View, item: HItem) {
        if (drag != null || resizing != null) return
        beginDrag(v, Payload(item, null), true)
        if (drag != null) showItemMenu(v, item)
    }

    /** Bilah Hapus/Info/Copot di atas layar sudah dihapus; Hapus, Info, dan Copot kini ada di menu tekan lama. */
    private fun showDragBar() {}

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

    private fun onPage(v: View): Boolean {
        val par = v.parent as? View ?: return false
        return pagesBox.indexOfChild(par) >= 0
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
                if (onPage(v)) items.add(FlowUi.Item("Ubah ukuran", 8) { startResize(item) })
                items.add(FlowUi.Item("Hapus", 3) { removeItem(item) })
            }
            HItem.APP -> {
                items.add(FlowUi.Item("Sunting", 0) { renameItem(item) })
                items.add(FlowUi.Item("Info aplikasi", 4) { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, HomeUtil.uriOfPkg(item.pkg))) } })
                items.add(FlowUi.Item("Copot", 5) { runCatching { startActivity(Intent(Intent.ACTION_DELETE, HomeUtil.uriOfPkg(item.pkg))) } })
                items.add(FlowUi.Item("Hapus", 3) { removeItem(item) })
            }
            else -> {
                val wi = awm.getAppWidgetInfo(item.wid)
                items.add(FlowUi.Item("Ubah ukuran", 8) { startResize(item) })
                if (wi != null && wi.configure != null) items.add(FlowUi.Item("Pengaturan widget", 6) { reconfigure(item) })
                items.add(FlowUi.Item("Hapus", 3) { removeItem(item) })
            }
        }
        itemMenuDismiss = FlowUi.menu(this, r, items, false) { itemMenuDismiss = null }
    }

    private fun reconfigure(item: HItem) {
        val ok = try { host.startAppWidgetConfigureActivityForResult(this, item.wid, 0, REQ_RECONF, null); true } catch (e: Exception) { false }
        if (!ok) FlowUi.toast(this, "Widget ini tidak punya pengaturan")
    }

    private fun renameItem(item: HItem) {
        val isFolder = item.type == HItem.FOLDER
        val cur = if (isFolder) item.name else item.name.ifEmpty { appLabel(item.pkg) }
        FlowUi.input(this, if (isFolder) "Nama folder" else "Nama ikon", cur, if (isFolder) "Tanpa nama" else appLabel(item.pkg), "Simpan") { t ->
            val n = t.trim()
            item.name = if (isFolder) n else (if (n == appLabel(item.pkg)) "" else n)
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

    // ---------- ubah ukuran folder/widget: bingkai dengan titik tarik di tepi, berhenti per sel ----------
    private var resizing: HItem? = null
    private var resizeUi: FrameLayout? = null
    private val jiggles = ArrayList<ObjectAnimator>()

    private fun animateLp(view: View, l: Int, t: Int, w: Int, h: Int) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val l0 = lp.leftMargin
        val t0 = lp.topMargin
        val w0 = lp.width
        val h0 = lp.height
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 180
        a.interpolator = DecelerateInterpolator(1.5f)
        a.addUpdateListener { an ->
            val f = an.animatedFraction
            lp.leftMargin = (l0 + (l - l0) * f).toInt()
            lp.topMargin = (t0 + (t - t0) * f).toInt()
            lp.width = (w0 + (w - w0) * f).toInt()
            lp.height = (h0 + (h - h0) * f).toInt()
            view.layoutParams = lp
        }
        a.start()
    }

    private fun startResize(item: HItem) {
        if (resizing != null) return
        if (editing) exitEdit()
        val v = findViewFor(item) ?: return
        val page = v.parent as? FrameLayout ?: return
        val pIdx = pagesBox.indexOfChild(page)
        if (pIdx < 0) return
        // ukuran bebas per setengah sel; batas bawah satu sel penuh, tidak terikat batas bawaan penyedia widget
        val minX = 2
        val minY = 2
        val canW = true
        val canH = true
        val liveView = item.type == HItem.WIDGET || item.sx > 2 || item.sy > 2
        resizing = item
        val list = HomeStore.pages[pIdx]
        var cx = item.cx
        var cy = item.cy
        var sx = item.sx
        var sy = item.sy
        val inset = dp(4)
        val vlp = v.layoutParams as FrameLayout.LayoutParams
        val baseL = vlp.leftMargin
        val baseT = vlp.topMargin
        val baseW = maxOf(1, vlp.width).toFloat()
        val baseH = maxOf(1, vlp.height).toFloat()
        v.pivotX = 0f; v.pivotY = 0f
        val frame = FrameLayout(this)
        frame.background = GradientDrawable().apply { setStroke(dp(2), Color.WHITE); cornerRadius = dp(24).toFloat(); setColor(Color.argb(22, 255, 255, 255)) }
        resizeUi = frame
        val lp0 = FrameLayout.LayoutParams(sx * cellW - 2 * inset, sy * cellH - 2 * inset)
        lp0.leftMargin = cx * cellW + inset
        lp0.topMargin = cy * cellH + inset
        page.addView(frame, lp0)
        if (!liveView) v.animate().alpha(0.25f).setDuration(150).start()
        frame.scaleX = 0.94f; frame.scaleY = 0.94f; frame.alpha = 0f
        frame.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(OvershootInterpolator(1.6f)).start()

        fun layoutAll() {
            val tl = cx * cellW + inset
            val tt = cy * cellH + inset
            val tw = sx * cellW - 2 * inset
            val th = sy * cellH - 2 * inset
            animateLp(frame, tl, tt, tw, th)
            // widget hanya ditransformasi (skala/geser, murah di GPU); tata letak sebenarnya dilakukan sekali saat selesai
            if (liveView) v.animate().translationX((tl - baseL).toFloat()).translationY((tt - baseT).toFloat())
                .scaleX(tw / baseW).scaleY(th / baseH).setDuration(170).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }

        fun addHandle(edge: Int) {
            if ((edge < 2 && !canW) || (edge >= 2 && !canH)) return
            val hv = View(this)
            hv.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(2), Color.argb(70, 0, 0, 0)) }
            val sz = dp(24)
            val g = when (edge) {
                0 -> Gravity.START or Gravity.CENTER_VERTICAL
                1 -> Gravity.END or Gravity.CENTER_VERTICAL
                2 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            val hl = FrameLayout.LayoutParams(sz, sz, g)
            when (edge) { 0 -> hl.leftMargin = -sz / 2; 1 -> hl.rightMargin = -sz / 2; 2 -> hl.topMargin = -sz / 2; else -> hl.bottomMargin = -sz / 2 }
            frame.addView(hv, hl)
            var sX = 0f
            var sY = 0f
            var bcx = 0
            var bcy = 0
            var bsx = 0
            var bsy = 0
            hv.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        sX = e.rawX; sY = e.rawY; bcx = cx; bcy = cy; bsx = sx; bsy = sy
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        view.animate().scaleX(1.35f).scaleY(1.35f).setDuration(110).start()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dxc = Math.round((e.rawX - sX) / cellW)
                        val dyc = Math.round((e.rawY - sY) / cellH)
                        var ncx = bcx
                        var ncy = bcy
                        var nsx = bsx
                        var nsy = bsy
                        when (edge) {
                            0 -> { ncx = bcx + dxc; nsx = bsx - dxc }
                            1 -> nsx = bsx + dxc
                            2 -> { ncy = bcy + dyc; nsy = bsy - dyc }
                            else -> nsy = bsy + dyc
                        }
                        val changed = ncx != cx || ncy != cy || nsx != sx || nsy != sy
                        if (changed && nsx >= minX && nsy >= minY && HomeStore.cellFree(list, ncx, ncy, nsx, nsy, item)) {
                            cx = ncx; cy = ncy; sx = nsx; sy = nsy
                            layoutAll()
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        item.cx = cx; item.cy = cy; item.sx = sx; item.sy = sy
                        HomeStore.save(this)
                    }
                }
                true
            }
        }
        for (edge in 0..3) addHandle(edge)
        FlowUi.toast(this, "Seret titik di tepi untuk mengubah ukuran. Ketuk di luar untuk selesai.")
    }

    private fun endResize() {
        val item = resizing ?: return
        resizing = null
        resizeUi = null
        HomeStore.save(this)
        val snap = snapshot()
        rebuild()
        pager.post { animateFrom(snap, null, -1f, -1f, -1, item) }
    }

    // ---------- goyang ikon saat mode edit ----------
    private fun startJiggle() {
        stopJiggle(false)
        val rnd = java.util.Random()
        for (p in 0 until pagesBox.childCount) {
            val page = pagesBox.getChildAt(p) as? ViewGroup ?: continue
            for (i in 1 until page.childCount) {
                val v = page.getChildAt(i)
                val item = v.tag as? HItem ?: continue
                if (item.type == HItem.WIDGET || item.sx > 2 || item.sy > 2) continue
                val a = ObjectAnimator.ofFloat(v, View.ROTATION, -2.2f, 2.2f)
                a.duration = 110L + rnd.nextInt(50)
                a.repeatCount = ValueAnimator.INFINITE
                a.repeatMode = ValueAnimator.REVERSE
                a.startDelay = rnd.nextInt(90).toLong()
                a.start()
                jiggles.add(a)
            }
        }
    }

    private fun stopJiggle(reset: Boolean) {
        for (a in jiggles) {
            val t = a.target as? View
            a.cancel()
            if (reset) t?.animate()?.rotation(0f)?.setDuration(120)?.start() else t?.rotation = 0f
        }
        jiggles.clear()
    }

    /** Hapus dari layar dengan animasi mengecil. */
    private fun removeItem(item: HItem) {
        if (item.type == HItem.WIDGET && item.wid >= 0) { widgetViews.remove(item.wid); runCatching { host.deleteAppWidgetId(item.wid) } }
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

    // ---------- cadangan tata letak, pemulihan (termasuk widget), dan pusat izin ----------
    private class RW(val page: Int, val cx: Int, val cy: Int, val sx: Int, val sy: Int, val prov: String)

    private val restoreQueue = ArrayList<RW>()
    private var restoring = false

    private fun showBackupMenu() {
        val items = ArrayList<FlowUi.Item>()
        items.add(FlowUi.Item("Cadangkan tata letak", 7) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "FlowLauncher-cadangan.json")
            runCatching { startActivityForResult(i, REQ_BACKUP) }
        })
        items.add(FlowUi.Item("Pulihkan tata letak", 2) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            runCatching { startActivityForResult(i, REQ_RESTORE) }
        })
        items.add(FlowUi.Item("Atur izin", 4) { showPermissions() })
        FlowUi.menu(this, centerRect(), items, true)
    }

    private fun backupJson(): String {
        val o = JSONObject()
        o.put("app", "FlowLauncher"); o.put("v", 3)
        val pa = JSONArray()
        for (p in HomeStore.pages) {
            val arr = JSONArray()
            for (x in p) {
                val j = x.toJson()
                if (x.type == HItem.WIDGET) j.put("prov", awm.getAppWidgetInfo(x.wid)?.provider?.flattenToString() ?: "")
                arr.put(j)
            }
            pa.put(arr)
        }
        o.put("pages", pa)
        val da = JSONArray()
        for (x in HomeStore.dock) da.put(x.toJson())
        o.put("dock", da)
        o.put("mono", mono); o.put("labels", showLabels)
        val wf = HomeWall.file(this)
        if (wf.exists() && wf.length() < 6_000_000L) o.put("wall", Base64.encodeToString(wf.readBytes(), Base64.NO_WRAP))
        return o.toString()
    }

    private fun restoreFrom(json: String): Boolean {
        return try {
            val o = JSONObject(json)
            if (o.optString("app") != "FlowLauncher") return false
            val np = ArrayList<ArrayList<HItem>>()
            val q = ArrayList<RW>()
            val pa = o.getJSONArray("pages")
            for (i in 0 until pa.length()) {
                val arr = pa.getJSONArray(i)
                val l = ArrayList<HItem>()
                for (j in 0 until arr.length()) {
                    val jo = arr.getJSONObject(j)
                    val hi = hItemFrom(jo)
                    if (hi.type == HItem.WIDGET) q.add(RW(i, hi.cx, hi.cy, hi.sx, hi.sy, jo.optString("prov"))) else l.add(hi)
                }
                np.add(l)
            }
            val nd = ArrayList<HItem>()
            val da = o.getJSONArray("dock")
            for (j in 0 until da.length()) nd.add(hItemFrom(da.getJSONObject(j)))
            HomeStore.replace(np, nd)
            HomeStore.save(this)
            mono = o.optBoolean("mono", false)
            showLabels = o.optBoolean("labels", true)
            getSharedPreferences(SP, MODE_PRIVATE).edit().putBoolean("mono", mono).putBoolean("labels", showLabels).apply()
            val w = o.optString("wall")
            if (w.isNotEmpty()) HomeWall.file(this).writeBytes(Base64.decode(w, Base64.NO_WRAP))
            restoreQueue.clear()
            restoreQueue.addAll(q)
            true
        } catch (e: Exception) { false }
    }

    /** Pasang ulang widget hasil pemulihan satu per satu di posisi dan ukuran semula (izin bind sistem mungkin muncul). */
    private fun processRestore() {
        if (restoreQueue.isEmpty()) {
            restoring = false
            FlowUi.toast(this, "Tata letak dipulihkan")
            return
        }
        val rw = restoreQueue.removeAt(0)
        val info = awm.installedProviders.firstOrNull { it.provider.flattenToString() == rw.prov }
        if (info == null) { processRestore(); return }
        pendPlace = intArrayOf(rw.page, rw.cx, rw.cy)
        pendSpan = intArrayOf(rw.sx, rw.sy)
        bindWidget(info)
    }

    private fun showPermissions() {
        fun mark(b: Boolean) = if (b) "✓ " else "✗ "
        val acc = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName)
        val notif = (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
        val usage = if (Build.VERSION.SDK_INT >= 29) runCatching {
            val ao = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false) else false
        val write = Settings.System.canWrite(this)
        val exact = if (Build.VERSION.SDK_INT >= 31) runCatching { (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms() }.getOrDefault(true) else true
        val post = if (Build.VERSION.SDK_INT >= 33) checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED else true
        val pkgUri = Uri.parse("package:$packageName")
        val items = ArrayList<FlowUi.Item>()
        items.add(FlowUi.Item(mark(HomeUtil.isDefaultHome(this)) + "Launcher utama", 4) { requestHome() })
        items.add(FlowUi.Item(mark(acc) + "Aksesibilitas (Control Center)", 6) { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        items.add(FlowUi.Item(mark(notif) + "Akses notifikasi (musik)", 6) { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } })
        items.add(FlowUi.Item(mark(usage) + "Akses penggunaan (waktu layar)", 6) { runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } })
        items.add(FlowUi.Item(mark(write) + "Ubah pengaturan sistem (kecerahan)", 6) { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)) } })
        items.add(FlowUi.Item(mark(exact) + "Alarm tepat waktu", 6) { if (Build.VERSION.SDK_INT >= 31) runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)) } })
        items.add(FlowUi.Item(mark(post) + "Notifikasi", 6) { if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 91) })
        FlowUi.menu(this, centerRect(), items, true)
    }

    private fun pickWallpaper() {
        // galeri foto (bukan pemilih berkas): Android 13+ pemilih foto sistem, di bawahnya aplikasi galeri
        val tries = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= 33) tries.add(Intent(MediaStore.ACTION_PICK_IMAGES))
        tries.add(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType("image/*"))
        tries.add(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"))
        for (i in tries) { try { startActivityForResult(i, REQ_WALL); return } catch (e: Exception) { } }
        FlowUi.toast(this, "Tidak ada aplikasi galeri")
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
        if (!restoring && info.configure != null) {
            val ok = try { host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null); true } catch (e: Exception) { false }
            if (ok) return
        }
        addWidget(id, info)
    }

    /** Ukuran awal widget dalam sub-sel (setengah sel), dari ukuran minimum widget. */
    private fun spanOf(info: AppWidgetProviderInfo): IntArray {
        val d = resources.displayMetrics.density
        var sx = Math.ceil((info.minWidth / (cellW / d)).toDouble()).toInt()
        var sy = Math.ceil((info.minHeight / (cellH / d)).toDouble()).toInt()
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) { sx = info.targetCellWidth * 2; sy = info.targetCellHeight * 2 }
        return intArrayOf(sx.coerceIn(2, HomeStore.COLS), sy.coerceIn(2, HomeStore.ROWS))
    }

    private fun addWidget(id: Int, info: AppWidgetProviderInfo) {
        val place = pendPlace
        val span = pendSpan
        pendPlace = null; pendSpan = null
        val sp = span ?: spanOf(info)
        val item = HItem(HItem.WIDGET, sx = sp[0], sy = sp[1], wid = id)
        val snap = snapshot()
        var p: Int
        if (place != null) {
            item.cx = place[1]; item.cy = place[2]
            while (HomeStore.pages.size <= place[0]) HomeStore.pages.add(ArrayList())
            HomeStore.pages[place[0]].add(item)
            p = place[0]
        } else p = HomeStore.placeNear(item, curPage, 0, 0)
        pendWid = -1; pendInfo = null
        if (p < 0) {
            host.deleteAppWidgetId(id)
            FlowUi.toast(this, "Layar penuh")
            if (restoring) ui.post { processRestore() }
            return
        }
        HomeStore.save(this)
        curPage = p
        rebuild()
        pager.post { animateFrom(snap, null, -1f, -1f, -1, null) }
        if (restoring) ui.post { processRestore() }
    }

    /** Widget baru dari pemilih: ditekan lama, lalu diseret ke tempat yang diinginkan di layar (bayangan = pratinjau widget). */
    private fun beginWidgetDrag(v: View, info: AppWidgetProviderInfo) {
        if (drag != null) return
        val sp = spanOf(info)
        val item = HItem(HItem.WIDGET, sx = sp[0], sy = sp[1], wid = -1)
        drag = Payload(item, null)
        dragView = null
        dragNewInfo = info
        if (HomeStore.pages.size < HomeStore.MAX_PAGES && HomeStore.pages[HomeStore.pages.size - 1].isNotEmpty()) addSparePage()
        val w = sp[0] * cellW
        val h = sp[1] * cellH
        val prev = previewOf(info)
        val shadow = object : View.DragShadowBuilder(v) {
            override fun onProvideShadowMetrics(size: android.graphics.Point, touch: android.graphics.Point) {
                size.set(w, h)
                touch.set(w / 2, h / 2)
            }
            override fun onDrawShadow(canvas: Canvas) {
                if (prev != null) {
                    prev.setBounds(0, 0, w, h)
                    prev.alpha = 235
                    prev.draw(canvas)
                    prev.alpha = 255
                } else {
                    canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), dp(24).toFloat(), dp(24).toFloat(), Paint().apply { color = Color.argb(120, 255, 255, 255) })
                }
            }
        }
        val ok = v.startDragAndDrop(ClipData.newPlainText("widget", info.provider.packageName), shadow, null, 0)
        if (!ok) { drag = null; dragNewInfo = null; return }
        dragX0 = Float.NaN
        menuHidden = true
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        closeSheet()
        if (editing) exitEdit()
    }

    private fun placeNewWidget(item: HItem, p: Int, cx: Int, cy: Int) {
        val info = dragNewInfo ?: return
        dragNewInfo = null
        val spot = HomeStore.findSpot(item, p, cx, cy)
        if (spot == null) { FlowUi.toast(this, "Tidak ada ruang untuk widget ini"); return }
        pendPlace = spot
        pendSpan = intArrayOf(item.sx, item.sy)
        bindWidget(info)
    }

    private fun cancelPending() {
        if (pendWid >= 0) runCatching { host.deleteAppWidgetId(pendWid) }
        pendWid = -1; pendInfo = null
        pendPlace = null; pendSpan = null
        if (restoring) ui.post { processRestore() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_BIND -> { val pi = pendInfo; if (resultCode == RESULT_OK && pi != null) configureOrAdd(pendWid, pi) else cancelPending() }
            REQ_CONFIG -> { val pi = pendInfo; if (resultCode == RESULT_OK && pi != null) addWidget(pendWid, pi) else cancelPending() }
            REQ_ROLE -> { if (HomeUtil.isDefaultHome(this)) WidgetUtil.updateAll(this) }
            REQ_RECONF -> { rebuild() }
            REQ_BACKUP -> {
                val u = data?.data
                if (resultCode == RESULT_OK && u != null) {
                    val json = backupJson()
                    Thread {
                        val ok = runCatching { contentResolver.openOutputStream(u)?.use { it.write(json.toByteArray()) } != null }.getOrDefault(false)
                        runOnUiThread { FlowUi.toast(this, if (ok) "Tata letak dicadangkan" else "Gagal menyimpan cadangan") }
                    }.start()
                }
            }
            REQ_RESTORE -> {
                val u = data?.data
                if (resultCode == RESULT_OK && u != null) {
                    Thread {
                        val text = runCatching { contentResolver.openInputStream(u)?.use { String(it.readBytes()) } }.getOrNull()
                        runOnUiThread {
                            if (text != null && restoreFrom(text)) {
                                curPage = 0
                                applyWall(true)
                                rebuild()
                                restoring = restoreQueue.isNotEmpty()
                                if (restoring) { FlowUi.toast(this, "Memulihkan widget…"); processRestore() } else FlowUi.toast(this, "Tata letak dipulihkan")
                            } else FlowUi.toast(this, "Berkas cadangan tidak valid")
                        }
                    }.start()
                }
            }
            REQ_HIDDEN -> { if (resultCode == RESULT_OK) openHidden() }
            REQ_WALL -> { val u = data?.data; if (resultCode == RESULT_OK && u != null) setWallpaper(u) }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
