package app.timeflow

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Layanan aksesibilitas khusus Asisten AI ("Flow AI (kendali layar)"). Terpisah dari layanan Control Center: baru perlu dinyalakan bila
 * kamu ingin AI melihat layar dan menyentuh. Tidak memantau event apa pun; hanya dipakai saat AI menjalankan perintah.
 */
class AiAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var inst: AiAccessibilityService? = null
        fun connected(): Boolean = inst != null
        fun instance(): AccessibilityService? = inst
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    // Hanya untuk mengetahui aplikasi di depan (Mode game): tidak membaca isi layar.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            GameMode.onWindow(this, event.packageName?.toString(), event.className?.toString())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        GameMode.recheck(this)
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        inst = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        inst = null
        super.onDestroy()
    }
}
