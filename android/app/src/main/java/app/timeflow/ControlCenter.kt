package app.timeflow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Control Center gaya iPhone. Dihidupkan oleh NotchListener (layanan yang sama, jadi tetap hidup walau aplikasi ditutup).
 * Dua jendela overlay: pemicu tipis di kanan atas (geser ke bawah) dan panel layar penuh berisi kontrol.
 * Android tidak mengizinkan aplikasi biasa menyalakan Wi-Fi, Bluetooth, data seluler, atau mode pesawat langsung:
 * ketukan pada tombol itu membuka panel pengaturan sistem, sedangkan statusnya (aktif / mati) tetap dibaca dan ditampilkan langsung.
 */
class ControlCenter(private val svc: Context, private val host: CcHost, private val type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
    private val d = svc.resources.displayMetrics.density
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val art = CcArt()
    private var trigger: CcTrigger? = null
    private var triggerOn = false
    private var handle: CcHandle? = null
    private var handleOn = false
    @Volatile var lastErr = ""
    private var panel: Panel? = null
    private var closing = false

    private fun dp(v: Float) = (v * d).toInt()

    private val poll = object : Runnable {
        override fun run() {
            val p = panel ?: return
            if (closing) return
            runCatching { p.refresh() }
            ui.postDelayed(this, 700)
        }
    }

    // ---------- siklus hidup ----------
    private fun allowed(c: CcPrefs.Cfg): Boolean {
        return c.enabled && (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY || Settings.canDrawOverlays(svc))
    }

    private fun isLand() = svc.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private var lastLand = isLand()

    /** Dipanggil tiap pengaturan / orientasi berubah: pasang atau lepas pemicu sesuai kondisi. */
    fun sync() {
        val c = CcPrefs.get(svc)
        if (!allowed(c)) { detachTrigger(); hide(false); return }
        if (lastLand != isLand()) { lastLand = isLand(); hide(false) }   // panel dibangun ulang untuk orientasi baru
        val land = isLand()
        // mendatar: tanpa pemicu geser (supaya ketukan di game tidak tertelan), pakai pegangan di tepi kanan saja
        if (land) detachTrigger(keepHandle = true)
        else {
            val lp = triggerLp(c)
            val t = trigger ?: CcTrigger(svc) { onSwipe() }.also { trigger = it }
            CcLog.add("sync: tipe=${if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) "a11y" else "app"} strip=${lp.width}x${lp.height}")
            if (triggerOn) runCatching { wm.updateViewLayout(t, lp) }
            else runCatching { wm.addView(t, lp); triggerOn = true }.onFailure { lastErr = "Pemicu gagal dipasang: " + (it.message ?: it.javaClass.simpleName) }
        }
        if (c.handle || land) {
            val hv = handle ?: CcHandle(svc) { onSwipe() }.also { handle = it }
            val hl = handleLp()
            if (handleOn) runCatching { wm.updateViewLayout(hv, hl) }
            else runCatching { wm.addView(hv, hl); handleOn = true }.onFailure { lastErr = "Pegangan gagal dipasang: " + (it.message ?: it.javaClass.simpleName) }
        } else detachHandle()
    }

    fun triggerAttached() = triggerOn
    fun handleAttached() = handleOn
    fun panelShown() = panel != null

    private fun handleLp(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dp(28f), dp(120f), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or baseFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT; x = 0
            y = (svc.resources.displayMetrics.heightPixels * 0.28f).toInt()
            cutout(this); title = "TimeFlowCcHandle"
        }

    private fun detachHandle() {
        val h = handle
        if (h != null && handleOn) runCatching { wm.removeViewImmediate(h) }
        handleOn = false; handle = null
    }

    fun release() {
        detachTrigger()
        hide(false)
        Torch.release()
    }

    private fun detachTrigger(keepHandle: Boolean = false) {
        if (!keepHandle) detachHandle()
        val t = trigger
        if (t != null && triggerOn) runCatching { wm.removeViewImmediate(t) }
        triggerOn = false; trigger = null
    }

    private fun statusBarH(): Int {
        val id = svc.resources.getIdentifier(if (isLand()) "status_bar_height_landscape" else "status_bar_height", "dimen", "android")
        val h = if (id > 0) svc.resources.getDimensionPixelSize(id) else dp(24f)
        return h.coerceIn(dp(20f), dp(48f))
    }

    private fun baseFlags() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun cutout(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun triggerLp(c: CcPrefs.Cfg): WindowManager.LayoutParams {
        val w = (svc.resources.displayMetrics.widthPixels * c.zone / 100f).toInt()
        return WindowManager.LayoutParams(
            w, statusBarH() + dp(4f), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or baseFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.RIGHT; x = 0; y = 0; cutout(this); title = "TimeFlowCcTrigger" }
    }

    private fun panelLp(blur: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or baseFlags()
        if (blur) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            cutout(this)
            if (blur && Build.VERSION.SDK_INT >= 31) blurBehindRadius = 40
            title = "TimeFlowControlCenter"
        }
    }

    // ---------- tampil / sembunyi ----------
    private fun onSwipe() {
        (trigger ?: handle)?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        try { show() } catch (e: Throwable) { fail("Panel gagal dibuka: " + e.javaClass.simpleName + " " + (e.message ?: "")) }
    }

    private fun fail(msg: String) { CcLog.add("ERROR " + msg); lastErr = msg; runCatching { Toast.makeText(svc, msg, Toast.LENGTH_LONG).show() } }

    fun show() {
        CcLog.add("show() panel=${panel != null} closing=$closing")
        if (panel != null || closing) return
        if (!allowed(CcPrefs.get(svc))) { CcLog.add("show: tidak diizinkan (fitur mati / izin kurang)"); return }
        Torch.init(svc)
        val blur = Build.VERSION.SDK_INT >= 31 && runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        val p = try { Panel(svc, blur) } catch (e: Throwable) {
            fail("Panel gagal dibuat: " + e.javaClass.simpleName + " " + (e.message ?: "")); return
        }
        panel = p
        val added = runCatching { wm.addView(p, panelLp(blur)) }
        if (added.isFailure) { panel = null; fail("Panel gagal dipasang: " + (added.exceptionOrNull()?.message ?: "izin ditolak sistem")); return }
        lastErr = ""
        CcLog.add("panel terpasang (tipe $type, blur=$blur)")
        runCatching { p.setMedia(host.mediaNow()) }
        runCatching { p.refresh() }
        p.animateIn()
        ui.removeCallbacks(poll)
        ui.postDelayed(poll, 700)
    }

    fun hide(animated: Boolean = true) {
        val p = panel ?: return
        if (closing) return
        CcLog.add("hide(anim=$animated) dari " + (Exception().stackTrace.getOrNull(1)?.let { it.methodName + ":" + it.lineNumber } ?: "?"))
        closing = true
        ui.removeCallbacks(poll)
        val fin: () -> Unit = {
            runCatching { wm.removeViewImmediate(p) }
            if (panel === p) panel = null
            closing = false
        }
        if (animated) p.animateOut(fin) else fin()
    }

    fun setMedia(m: MediaInfo?) { panel?.setMedia(m) }

    // ---------- aksi ----------
    private fun launch(i: Intent): Boolean = try {
        svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    /** Coba beberapa aksi pengaturan berurutan sampai ada yang terbuka. */
    private fun settings(vararg actions: String) {
        for (a in actions) if (launch(Intent(a))) break
        hide()
    }

    private fun toast(msg: String) { runCatching { Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show() } }

    private fun grantWrite() {
        toast("Izinkan \"Ubah pengaturan sistem\" untuk TimeFlow")
        launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + svc.packageName)))
        hide()
    }

    private fun openCalculator() {
        val sel = runCatching { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR) }.getOrNull()
        if (sel != null && launch(sel)) { hide(); return }
        // cadangan: cari aplikasi peluncur yang namanya mengandung "calcul"
        val pm = svc.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val hit = runCatching { pm.queryIntentActivities(q, 0).firstOrNull { it.activityInfo.packageName.contains("calcul", true) } }.getOrNull()
        if (hit != null) {
            launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(hit.activityInfo.packageName, hit.activityInfo.name)))
        } else toast("Aplikasi kalkulator tidak ditemukan")
        hide()
    }

    /** Membuka perekam layar bawaan HP (di Poco/Xiaomi: Perekam Layar). Perekaman layar oleh aplikasi lain butuh persetujuan sistem tiap kali, jadi yang paling andal adalah memakai perekam bawaan. */
    private fun openRecorder() {
        val pm = svc.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching { pm.queryIntentActivities(q, 0) }.getOrDefault(emptyList())
        val hit = apps.firstOrNull { it.activityInfo.packageName == "com.miui.screenrecorder" }
            ?: apps.firstOrNull { it.activityInfo.packageName.contains("screenrecord", true) }
            ?: apps.firstOrNull { val l = runCatching { it.loadLabel(pm).toString().lowercase() }.getOrDefault(""); l.contains("perekam layar") || l.contains("screen record") }
        if (hit != null) launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(hit.activityInfo.packageName, hit.activityInfo.name)))
        else toast("Perekam layar bawaan tidak ditemukan. Buka lewat bar notifikasi HP-mu.")
        hide()
    }

    private fun launcherApps(): List<ResolveInfo> = runCatching {
        svc.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    }.getOrDefault(emptyList())

    /** Cari aplikasi peluncur untuk satu kontrol: paket persis dulu, lalu potongan nama. */
    private fun findApp(def: CcDef, apps: List<ResolveInfo> = launcherApps()): ResolveInfo? {
        if (def.pkgs.isEmpty() && def.names.isEmpty()) return null
        val pm = svc.packageManager
        apps.firstOrNull { it.activityInfo.packageName in def.pkgs }?.let { return it }
        return apps.firstOrNull { ri ->
            val l = runCatching { ri.loadLabel(pm).toString().lowercase() }.getOrDefault("")
            def.names.any { l.contains(it) }
        }
    }

    /** Kontrol umum dari katalog: buka aplikasinya dan/atau halaman pengaturannya. */
    private fun openDef(def: CcDef) {
        val app = findApp(def)
        fun runApp(): Boolean = app != null && launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.activityInfo.packageName, app.activityInfo.name)))
        fun runActs(): Boolean { for (a in def.acts) if (launch(Intent(a))) return true; return false }
        val ok = if (def.appFirst) runApp() || runActs() else runActs() || runApp()
        if (!ok) toast(if (def.pkgs.isNotEmpty() || def.names.isNotEmpty()) "${def.label}: aplikasi belum terpasang" else "${def.label} tidak tersedia di HP ini")
        hide()
    }

    private fun act(kind: String) {
        when (kind) {
            "plane" -> settings(Settings.ACTION_AIRPLANE_MODE_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
            "cell" -> if (Build.VERSION.SDK_INT >= 29) settings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
                      else settings(Settings.ACTION_DATA_ROAMING_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) settings(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS)
                      else settings(Settings.ACTION_WIFI_SETTINGS)
            "bt" -> settings(Settings.ACTION_BLUETOOTH_SETTINGS)
            "cast" -> settings(Settings.ACTION_CAST_SETTINGS)
            "rotate" -> {
                if (CcSys.writeOk(svc)) { CcSys.setRotate(svc, !CcSys.rotate(svc)); panel?.refresh() } else grantWrite()
            }
            "moon" -> {
                if (CcSys.dndAccess(svc)) { CcSys.setDnd(svc, !CcSys.dnd(svc)); panel?.refresh() }
                else {
                    toast("Izinkan akses \"Jangan ganggu\" untuk TimeFlow")
                    launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); hide()
                }
            }
            "torch" -> {
                if (!Torch.toggle()) toast("Senter tidak tersedia di perangkat ini")
                panel?.refresh()
            }
            "timer" -> { host.openApp(); hide() }
            "calc" -> openCalculator()
            "camera" -> {
                if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) toast("Aplikasi kamera tidak ditemukan")
                hide()
            }
            "record" -> openRecorder()
            else -> CcCatalog.byId[kind]?.let { openDef(it) }
        }
    }

    /** Tekan lama: halaman pengaturan lengkap (bukan panel kecil). */
    private fun longAct(kind: String) {
        when (kind) {
            "wifi" -> settings(Settings.ACTION_WIFI_SETTINGS)
            "cell" -> settings(Settings.ACTION_WIRELESS_SETTINGS)
            "moon" -> settings("android.settings.ZEN_MODE_PRIORITY_SETTINGS", Settings.ACTION_SOUND_SETTINGS)
            else -> act(kind)
        }
    }

    private fun buzz(v: View, strong: Boolean = false) {
        if (CcPrefs.get(svc).haptic) v.performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CONTEXT_CLICK)
    }

    // ---------- panel ----------
    private inner class Panel(ctx: Context, private val blur: Boolean) : FrameLayout(ctx) {
        private val grid = FrameLayout(ctx)
        private val hint = TextView(ctx)
        private val scrimA = if (blur) 95 else 165
        private var hadFocus = false
        private var x0 = 0f
        private var y0 = 0f

        private lateinit var tPlane: CcTile
        private lateinit var tCell: CcTile
        private lateinit var tWifi: CcTile
        private lateinit var tBt: CcTile
        private lateinit var tRot: CcTile
        private lateinit var tCast: CcTile
        private lateinit var tDnd: CcTile
        private lateinit var sBright: CcSlider
        private lateinit var sVol: CcSlider
        private lateinit var media: CcMedia
        private val extra = ArrayList<CcTile>()

        init {
            build()
            progress(0f)
        }

        private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

        private fun glass(r: Float) = GradientDrawable().apply { setColor(CcColor.glass); cornerRadius = r; setStroke(1, CcColor.glassEdge) }

        private fun place(v: View, col: Float, row: Float, cw: Float, rh: Float, u: Float, gap: Float) {
            val w = cw * u + (cw - 1f) * gap
            val h = rh * u + (rh - 1f) * gap
            grid.addView(v, FrameLayout.LayoutParams(w.toInt(), h.toInt()).apply {
                leftMargin = (col * (u + gap)).toInt(); topMargin = (row * (u + gap)).toInt()
            })
        }

        private fun wire(t: CcTile, kind: String) {
            t.setOnClickListener { buzz(it); act(kind) }
            t.setOnLongClickListener { buzz(it, true); longAct(kind); true }
        }

        private fun build() {
            val dm = resources.displayMetrics
            val sw = dm.widthPixels; val sh = dm.heightPixels
            val land = sw > sh
            val cfg = CcPrefs.get(svc)
            val tiles = cfg.tiles.filter { CcCatalog.byId.containsKey(it) }
            val cols = if (land) 8 else 4
            // baris tetap: potret 5 (konektivitas+slider, musik lebar, rotasi/cast/jangan ganggu), mendatar 2 (+ jangan ganggu mengalir bersama kontrol lain)
            val flowCount = tiles.size + (if (land) 3 else 0)
            val fixedRows = if (land) 2 else 5
            val rows = fixedRows + (flowCount + cols - 1) / cols
            val gap = dp(12f).toFloat()
            val sbh = statusBarH()
            var u = (sw - dp(40f) - (cols - 1) * gap) / cols
            u = min(u, dp(if (land) 80f else 96f).toFloat())
            val b = 2f * u + gap
            val gw = (cols * u + (cols - 1) * gap).toInt()
            val gh = (rows * u + (rows - 1) * gap).toInt()
            val baseTop = if (land) dp(14f) else sbh + dp(14f)
            val top = max(baseTop, (sh - gh - dp(50f)) / 2)
            val holderH = max(sh, top + gh + dp(70f))

            val holder = FrameLayout(context)
            holder.isClickable = true            // ketuk area kosong = tutup
            holder.setOnClickListener { hide() }
            grid.isClickable = true              // sentuhan di sela kontrol tidak menutup panel
            grid.pivotX = gw.toFloat() / 2f; grid.pivotY = 0f
            holder.addView(grid, FrameLayout.LayoutParams(gw, gh, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top })
            val sv = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = true
            }
            sv.addView(holder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, holderH))
            addView(sv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            // blok konektivitas 2x2 (pesawat, data, Wi-Fi, Bluetooth)
            val box = View(context).apply { background = glass(u * 0.3f) }
            place(box, 0f, 0f, 2f, 2f, u, gap)
            val pad = b * 0.07f
            val dc = (b - 3f * pad) / 2f
            fun circ(t: CcTile, i: Int, j: Int) {
                grid.addView(t, FrameLayout.LayoutParams(dc.toInt(), dc.toInt()).apply {
                    leftMargin = (pad + i * (dc + pad)).toInt(); topMargin = (pad + j * (dc + pad)).toInt()
                })
            }
            tPlane = CcTile(context, art, "plane", "Mode pesawat", 0).apply { onBg = CcColor.orange; onFg = Color.WHITE }
            tCell = CcTile(context, art, "cell", "Data seluler", 0).apply { onBg = CcColor.green; onFg = Color.WHITE }
            tWifi = CcTile(context, art, "wifi", "Wi-Fi", 0).apply { onBg = CcColor.blue; onFg = Color.WHITE }
            tBt = CcTile(context, art, "bt", "Bluetooth", 0).apply { onBg = CcColor.blue; onFg = Color.WHITE }
            circ(tPlane, 0, 0); circ(tCell, 1, 0); circ(tWifi, 0, 1); circ(tBt, 1, 1)
            wire(tPlane, "plane"); wire(tCell, "cell"); wire(tWifi, "wifi"); wire(tBt, "bt")

            // kartu musik: besar (potret: selebar layar, 2 baris; mendatar: 3 kolom x 2 baris)
            media = CcMedia(context, art, host, u, { buzz(it) }, { host.openMedia(); hide() })
            if (land) place(media, 2f, 0f, 4f, 2f, u, gap) else place(media, 0f, 2f, 4f, 2f, u, gap)

            tRot = CcTile(context, art, "rotate", "Rotasi", 1)
            tCast = CcTile(context, art, "cast", "Cast", 1)
            tDnd = CcTile(context, art, "moon", "Jangan Ganggu", if (land) 1 else 2).apply { onBg = CcColor.purple; onFg = Color.WHITE }
            sBright = CcSlider(context, art, "sun").apply {
                canAdjust = { CcSys.writeOk(svc) }
                onDenied = { grantWrite() }
                onChange = { CcSys.setBrightness(svc, it) }
            }
            sVol = CcSlider(context, art, "speaker").apply { onChange = { CcSys.setVolume(svc, it) } }
            if (land) {
                place(sBright, 6f, 0f, 1f, 2f, u, gap); place(sVol, 7f, 0f, 1f, 2f, u, gap)
                place(tRot, 0f, 2f, 1f, 1f, u, gap); place(tCast, 1f, 2f, 1f, 1f, u, gap); place(tDnd, 2f, 2f, 1f, 1f, u, gap)
            } else {
                place(sBright, 2f, 0f, 1f, 2f, u, gap); place(sVol, 3f, 0f, 1f, 2f, u, gap)
                place(tRot, 0f, 4f, 1f, 1f, u, gap); place(tCast, 1f, 4f, 1f, 1f, u, gap); place(tDnd, 2f, 4f, 2f, 1f, u, gap)
            }
            wire(tRot, "rotate"); wire(tCast, "cast"); wire(tDnd, "moon")

            // semua kontrol cepat dari katalog Poco C71
            val apps = launcherApps()
            val pm = svc.packageManager
            val off = if (land) 3 else 0
            for ((i, k) in tiles.withIndex()) {
                val def = CcCatalog.byId[k] ?: continue
                val t = CcTile(context, art, def.icon, def.label, 1)
                if (def.pkgs.isNotEmpty() || def.names.isNotEmpty()) {
                    val app = findApp(def, apps)
                    if (app != null && (def.appFirst || def.acts.isEmpty())) t.icon = runCatching { app.loadIcon(pm) }.getOrNull()
                }
                when (k) {
                    "location" -> { t.onBg = CcColor.blue; t.onFg = Color.WHITE }
                    "battery" -> t.onBg = CcColor.yellow
                    "nightlight" -> { t.onBg = CcColor.orange; t.onFg = Color.WHITE }
                    "datasaver" -> { t.onBg = CcColor.green; t.onFg = Color.WHITE }
                    "torch", "dim", "invert", "daltonizer", "fontsize", "display" -> {}
                }
                val j = i + off
                if (land) place(t, (j % 8).toFloat(), (2 + j / 8).toFloat(), 1f, 1f, u, gap)
                else place(t, (j % 4).toFloat(), (5 + j / 4).toFloat(), 1f, 1f, u, gap)
                wire(t, k)
                t.tag = k
                extra.add(t)
            }

            hint.text = "Ketuk di luar untuk menutup"
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            hint.setTextColor(Color.WHITE)
            hint.gravity = Gravity.CENTER
            holder.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { topMargin = top + gh + dp(14f) })
        }

        fun setMedia(m: MediaInfo?) { media.setMedia(m) }

        fun refresh() {
            tPlane.state(CcSys.airplane(svc))
            tCell.state(CcSys.data(svc))
            tWifi.state(CcSys.wifi(svc))
            tBt.state(CcSys.bluetooth(svc))
            tRot.state(CcSys.rotate(svc))
            tCast.state(false)
            tDnd.state(CcSys.dnd(svc))
            for (t in extra) t.state(when (t.tag) {
                "torch" -> Torch.on
                "location" -> CcSys.location(svc)
                "battery" -> CcSys.batterySaver(svc)
                "display" -> CcSys.dark(svc)
                "nightlight" -> CcSys.nightLight(svc)
                "dim" -> CcSys.dim(svc)
                "invert" -> CcSys.invert(svc)
                "daltonizer" -> CcSys.daltonizer(svc)
                "fontsize" -> CcSys.bigFont(svc)
                "datasaver" -> CcSys.dataSaver(svc)
                else -> false
            })
            media.tick()
            sBright.setValue(CcSys.brightness(svc))
            val v = CcSys.volume(svc)
            sVol.setValue(v, v <= 0.001f)
        }

        fun progress(t: Float) {
            setBackgroundColor(Color.argb((scrimA * t).toInt(), 0, 0, 0))
            grid.alpha = t
            val s = 0.88f + 0.12f * t
            grid.scaleX = s; grid.scaleY = s
            grid.translationY = -dp(24f) * (1f - t)
            hint.alpha = 0.6f * t
        }

        fun animateIn() {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260; interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { progress(it.animatedValue as Float) }
                start()
            }
        }

        fun animateOut(done: () -> Unit) {
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 170; interpolator = DecelerateInterpolator(1.2f)
                addUpdateListener { progress(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { done() }
                })
                start()
            }
        }

        // ketuk area kosong = tutup; geser ke atas = tutup
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY }
                MotionEvent.ACTION_MOVE -> if (y0 - e.rawY > dp(40f)) { y0 = Float.MAX_VALUE; hide() }
                MotionEvent.ACTION_UP -> if (abs(e.rawX - x0) < dp(10f) && abs(e.rawY - y0) < dp(10f)) hide()
            }
            return true
        }

        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) hide(); return true }
            return super.dispatchKeyEvent(e)
        }

        // tombol Home / pindah aplikasi mengambil fokus -> tutup panel
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            CcLog.add("fokus panel=$hasWindowFocus")
            if (hasWindowFocus) hadFocus = true else if (hadFocus && !closing) hide(false)
        }
    }
}
