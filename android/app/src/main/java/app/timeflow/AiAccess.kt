package app.timeflow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Tangan dan mata" Asisten AI: membaca isi layar, mengetuk, menggeser, mengetik, dan membuka aplikasi lewat layanan aksesibilitas TimeFlow.
 * Kolom kata sandi tidak pernah dibaca. Semua fungsi dipanggil dari thread latar (bukan thread utama).
 */
object AiAccess {
    @Volatile private var last: List<AccessibilityNodeInfo> = emptyList()

    private fun svc(): AccessibilityService? = AiAccessibilityService.instance()
    fun ready(): Boolean = svc() != null

    fun canGesture(): Boolean {
        val i = svc()?.serviceInfo ?: return false
        return (i.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
    }

    // ---------- membaca layar ----------
    fun dump(): String {
        val s = svc() ?: return "(layanan aksesibilitas belum aktif, layar tidak bisa dibaca)"
        val root = s.rootInActiveWindow ?: return "(layar tidak bisa dibaca saat ini)"
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        sb.append("Aplikasi di depan: ").append(root.packageName).append('\n')
        walk(root, nodes, sb, 0)
        last = nodes
        if (nodes.isEmpty()) sb.append("(tidak ada elemen yang terbaca)\n")
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 40 || out.size >= 90 || !n.isVisibleToUser) return
        val txt = label(n)
        val interactive = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || n.isLongClickable
        if (txt.isNotEmpty() || interactive) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                val i = out.size
                out.add(n)
                sb.append('[').append(i).append("] ").append((n.className?.toString() ?: "View").substringAfterLast('.'))
                if (txt.isNotEmpty()) sb.append(" \"").append(txt).append('"')
                if (n.isEditable) sb.append(" (kolom input)") else if (n.isClickable) sb.append(" (bisa diketuk)")
                if (n.isScrollable) sb.append(" (bisa digulir)")
                if (n.isCheckable && n.isChecked) sb.append(" (dicentang)")
                sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
            }
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), out, sb, depth + 1)
    }

    private fun label(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return "(kata sandi disembunyikan)"
        var t = n.text?.toString() ?: ""
        if (t.isEmpty()) t = n.contentDescription?.toString() ?: ""
        if (t.isEmpty()) t = n.hintText?.toString() ?: ""
        return t.replace('\n', ' ').trim().take(80)
    }

    // ---------- aksi ----------
    fun tap(index: Int): String {
        val n = last.getOrNull(index) ?: return "gagal: elemen [$index] tidak ada, minta baca layar lagi"
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && !c.isClickable && d < 6) { c = c.parent; d++ }
        if (c != null && c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok"
        val r = Rect()
        n.getBoundsInScreen(r)
        return tapXY(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun longPress(index: Int): String {
        val n = last.getOrNull(index) ?: return "gagal: elemen [$index] tidak ada"
        if (n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return "ok"
        val r = Rect()
        n.getBoundsInScreen(r)
        val p = Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }
        return gesture(p, 700)
    }

    fun tapXY(x: Float, y: Float): String {
        if (x.isNaN() || y.isNaN()) return "gagal: koordinat tidak valid"
        return gesture(Path().apply { moveTo(x, y) }, 60)
    }

    fun swipe(dir: String): String {
        val s = svc() ?: return "gagal: layanan aksesibilitas tidak aktif"
        val dm = s.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir.lowercase()) {
            "up" -> { p.moveTo(w / 2, h * 0.72f); p.lineTo(w / 2, h * 0.28f) }       // jari ke atas = isi layar naik (gulir ke bawah)
            "down" -> { p.moveTo(w / 2, h * 0.28f); p.lineTo(w / 2, h * 0.72f) }
            "left" -> { p.moveTo(w * 0.82f, h / 2); p.lineTo(w * 0.18f, h / 2) }
            "right" -> { p.moveTo(w * 0.18f, h / 2); p.lineTo(w * 0.82f, h / 2) }
            else -> return "gagal: arah harus up, down, left, atau right"
        }
        return gesture(p, 350)
    }

    private fun gesture(p: Path, dur: Long): String {
        val s = svc() ?: return "gagal: layanan aksesibilitas tidak aktif"
        if (!canGesture()) return "gagal: izin gestur belum aktif (matikan lalu nyalakan lagi layanan aksesibilitas Flow AI)"
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        val latch = CountDownLatch(1)
        var ok = false
        val sent = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!sent) return "gagal: gestur ditolak sistem"
        latch.await(2500, TimeUnit.MILLISECONDS)
        return if (ok) "ok" else "gagal: gestur dibatalkan"
    }

    fun type(text: String, index: Int?): String {
        val s = svc() ?: return "gagal: layanan aksesibilitas tidak aktif"
        val n = (if (index != null) last.getOrNull(index) else null)
            ?: s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: last.firstOrNull { it.isEditable }
            ?: return "gagal: tidak ada kolom input"
        if (n.isPassword) return "gagal: kolom kata sandi tidak boleh diisi oleh asisten"
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "ok" else "gagal: tidak bisa mengisi teks"
    }

    /** Tekan tombol Enter / Cari di keyboard (Android 11+). */
    fun submit(): String {
        val s = svc() ?: return "gagal: layanan aksesibilitas tidak aktif"
        if (Build.VERSION.SDK_INT < 30) return "gagal: ketuk tombol cari / kirim di layar"
        val n = s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return "gagal: tidak ada kolom input yang aktif"
        return if (n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) "ok" else "gagal: Enter ditolak, ketuk tombol cari di layar"
    }

    fun global(name: String): String {
        val s = svc() ?: return "gagal: layanan aksesibilitas tidak aktif"
        val a = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications_panel" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "gagal: aksi tidak dikenal"
        }
        return if (s.performGlobalAction(a)) "ok" else "gagal: ditolak sistem"
    }

    // ---------- membuka aplikasi dan tautan ----------
    fun openApp(ctx: Context, name: String): String {
        val q = name.trim().lowercase()
        if (q.isEmpty()) return "gagal: nama aplikasi kosong"
        val apps = AppRepo.list(ctx)
        val hit = apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(q) }
            ?: apps.firstOrNull { it.label.lowercase().contains(q) }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(q.replace(" ", "")) }
            ?: return "gagal: aplikasi \"$name\" tidak ditemukan"
        val i = ctx.packageManager.getLaunchIntentForPackage(hit.pkg) ?: return "gagal: ${hit.label} tidak bisa dibuka"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try { ctx.startActivity(i); "ok, membuka ${hit.label}" } catch (e: Exception) { "gagal membuka ${hit.label}: " + (e.message ?: "ditolak sistem") }
    }

    /** Hanya http, https, geo, dan mailto (tidak ada tel/sms agar asisten tidak bisa menelepon sendiri). */
    fun openUrl(ctx: Context, url: String): String {
        val u = url.trim()
        val ok = u.startsWith("https://") || u.startsWith("http://") || u.startsWith("geo:") || u.startsWith("mailto:")
        if (!ok) return "gagal: hanya tautan http, https, geo, atau mailto"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); "ok" } catch (e: Exception) { "gagal: " + (e.message ?: "tidak ada aplikasi pembuka") }
    }

    // ---------- notifikasi ----------
    /** 12 notifikasi terakhir dari riwayat notifikasi di perangkat. */
    fun notifications(ctx: Context): String {
        if (!NotchListener.accessGranted(ctx)) return "gagal: akses notifikasi belum diberikan ke TimeFlow"
        val items = NotifHistory.list(ctx, "", "", 0L, 0L, 12).optJSONArray("items") ?: return "(tidak ada notifikasi)"
        if (items.length() == 0) return "(tidak ada notifikasi)"
        val sb = StringBuilder()
        val now = System.currentTimeMillis()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val mins = ((now - o.optLong("ts")) / 60000L).coerceAtLeast(0)
            sb.append("- ").append(o.optString("app")).append(" (").append(mins).append(" mnt lalu): ")
                .append(o.optString("title").take(60)).append(" | ").append(o.optString("text").take(120).replace('\n', ' ')).append('\n')
        }
        return sb.toString()
    }

    // ---------- tangkapan layar (opsional, untuk model vision) ----------
    fun screenshotB64(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val s = svc() ?: return null
        val latch = CountDownLatch(1)
        var out: String? = null
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = screenshot.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        if (bmp != null) {
                            val sc = 768f / maxOf(bmp.width, bmp.height).toFloat()
                            val small = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
                            val bo = ByteArrayOutputStream()
                            small.compress(Bitmap.CompressFormat.JPEG, 60, bo)
                            out = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        latch.countDown()
                    }
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
        } catch (_: Throwable) {
            return null
        }
        latch.await(3, TimeUnit.SECONDS)
        return out
    }
}
