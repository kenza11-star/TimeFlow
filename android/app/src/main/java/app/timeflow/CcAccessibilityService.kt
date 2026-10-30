package app.timeflow

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONObject

/**
 * Menampilkan Control Center di lapisan aksesibilitas, yang berada DI ATAS bar status. Inilah yang membuat geser dari kanan atas
 * bekerja seperti HP pada umumnya, walau sistem (HyperOS/MIUI) punya gestur sendiri di sana. Layanan ini tidak membaca isi layar
 * (canRetrieveWindowContent = false) dan tidak memakai event apa pun; satu-satunya tugasnya memasang jendela pemicu dan panel.
 */
class CcAccessibilityService : AccessibilityService(), CcHost {
    companion object {
        @Volatile private var inst: CcAccessibilityService? = null
        fun connected() = inst != null
        /** WindowManager layanan ini: jendela TYPE_ACCESSIBILITY_OVERLAY hanya boleh dipasang lewat sini. */
        fun winMgr(): WindowManager? = inst?.let { runCatching { it.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager }.getOrNull() }
        fun refresh() { inst?.let { i -> i.ui.post { i.apply() } } }
        fun setMedia(m: MediaInfo?) { val i = inst ?: return; i.ui.post { i.cc?.setMedia(m) } }

        fun open(delay: Long): Boolean {
            val i = inst ?: return false
            if (!CcPrefs.get(i).enabled) return false
            i.ui.postDelayed({ i.apply(); i.openedAt = SystemClock.elapsedRealtime(); i.cc?.show() }, delay)
            return true
        }

        fun diag(): JSONObject? {
            val c = inst?.cc ?: return if (inst != null) JSONObject().put("trigger", false).put("handle", false).put("err", "") else null
            return JSONObject().put("trigger", c.triggerAttached()).put("handle", c.handleAttached()).put("err", c.lastErr)
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var cc: ControlCenter? = null
    @Volatile private var openedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        apply()
        NotchListener.refresh()   // notch berhenti menampilkan Control Center sendiri
    }

    /** Aplikasi/launcher lain naik ke depan (mis. tombol Home ditekan): tutup panel supaya tidak menempel di layar. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // abaikan jendela kita sendiri dan SystemUI (muncul saat panel/bar notifikasi berubah); sisanya = aplikasi/launcher lain di depan
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        if (cc?.panelShown() == true && SystemClock.elapsedRealtime() - openedAt > 1500) { CcLog.add("tutup: jendela $pkg di depan"); cc?.hide(false) }
    }
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); ui.post { apply() } }

    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return super.onUnbind(intent) }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    private fun shutdown() {
        inst = null
        cc?.release(); cc = null
        NotchListener.refresh()   // layanan notch mengambil alih lagi
    }

    private fun apply() {
        if (CcPrefs.get(this).enabled) {
            val c = cc ?: ControlCenter(this, this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also { cc = it }
            c.sync()
        } else { cc?.release(); cc = null }
    }

    // ---- menekan tile sistem ----
    private fun norm(s: CharSequence?) = s?.toString()?.lowercase() ?: ""

    private fun matches(n: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val a = norm(n.text); val b = norm(n.contentDescription)
        return labels.any { (a.isNotEmpty() && a.contains(it)) || (b.isNotEmpty() && b.contains(it)) }
    }

    private fun search(n: AccessibilityNodeInfo?, labels: List<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 25) return null
        if (matches(n, labels)) return n
        for (i in 0 until n.childCount) { search(n.getChild(i), labels, depth + 1)?.let { return it } }
        return null
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var x: AccessibilityNodeInfo? = n
        var k = 0
        while (x != null && k < 6) { if (x.isClickable) return x; x = x.parent; k++ }
        return null
    }

    private fun findTile(labels: List<String>): AccessibilityNodeInfo? {
        val ws = runCatching { windows }.getOrNull().orEmpty()
        for (w in ws) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() == packageName) continue
            val hit = search(root, labels) ?: continue
            return clickable(hit) ?: hit
        }
        return null
    }

    private fun closeShade() {
        if (android.os.Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        else performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun doPress(labels: List<String>, onFail: () -> Unit) {
        cc?.hide(false)
        CcLog.add("tekan tile: $labels")
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        var tries = 0
        lateinit var step: Runnable
        step = Runnable {
            tries++
            val n = findTile(labels)
            if (n != null) {
                val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                CcLog.add("tile diklik=$ok")
                ui.postDelayed({ closeShade(); ui.postDelayed({ open(250) }, 350) }, 350)
            } else if (tries < 8) ui.postDelayed(step, 250)
            else {
                CcLog.add("tile tidak ditemukan")
                closeShade()
                ui.postDelayed({ onFail() }, 300)
            }
        }
        ui.postDelayed(step, 600)
    }

    // ---- CcHost ----
    override fun screenshot(cb: (Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 30) { cb(null); return }
        var done = false
        val finish = { b: Bitmap? -> if (!done) { done = true; cb(b) } }
        ui.postDelayed({ finish(null) }, 1800)
        fun shoot(retry: Boolean) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val b = runCatching { Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                        runCatching { r.hardwareBuffer.close() }
                        CcLog.add("tangkapan layar: " + (if (b != null) "ok" else "gagal"))
                        finish(b)
                    }
                    override fun onFailure(errorCode: Int) {
                        CcLog.add("tangkapan layar ditolak ($errorCode)" + (if (retry) ", coba lagi" else ""))
                        // kode 3 = jarak antar tangkapan terlalu dekat (sistem membatasi ~1 detik): tunggu lalu ulangi sekali
                        if (retry && !done) ui.postDelayed({ if (!done) shoot(false) }, 1050) else finish(null)
                    }
                })
            } catch (e: Throwable) { CcLog.add("tangkapan layar error: " + e.javaClass.simpleName); finish(null) }
        }
        shoot(true)
    }

    override fun pressTile(labels: List<String>, onFail: () -> Unit): Boolean { ui.post { doPress(labels, onFail) }; return true }
    override fun transport(cmd: Int) = NotchListener.hubTransport(cmd)
    override fun openMedia() = NotchListener.hubOpenMedia()
    override fun seek(frac: Float) = NotchListener.hubSeek(frac)
    override fun mediaNow(): MediaInfo? = NotchListener.hubMedia()
    override fun openApp() {
        runCatching { packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
