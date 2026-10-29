package app.timeflow

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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

    // ---- CcHost ----
    override fun transport(cmd: Int) = NotchListener.hubTransport(cmd)
    override fun openMedia() = NotchListener.hubOpenMedia()
    override fun seek(frac: Float) = NotchListener.hubSeek(frac)
    override fun mediaNow(): MediaInfo? = NotchListener.hubMedia()
    override fun openApp() {
        runCatching { packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
