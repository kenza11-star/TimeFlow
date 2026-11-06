package app.timeflow

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mode game otomatis. Saat game (atau aplikasi layar penuh landscape) ada di depan, semua yang digambar atau berjalan di latar
 * dihentikan sementara: notch, Control Center, pil AI, mikrofon AI (supaya voice chat game tetap bisa memakai mikrofon),
 * dan pembaruan widget per detik. Begitu keluar dari game semuanya dinyalakan lagi.
 * Pendeteksi: kategori aplikasi "game" dari Android, atau jendela depan berorientasi landscape.
 */
object GameMode {
    private const val SP = "timeflow_game"
    @Volatile var active = false
        private set
    @Volatile private var lastPkg = ""
    private val cache = ConcurrentHashMap<String, Boolean>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun enabled(c: Context): Boolean = c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("auto", true)

    fun setEnabled(c: Context, v: Boolean) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putBoolean("auto", v).apply()
        notifyAll2()
    }

    /** true = sedang dipakai game dan mode game menyala: jangan gambar overlay atau jalankan loop latar. */
    fun blocked(c: Context): Boolean = active && enabled(c)

    fun listen(l: () -> Unit) { if (!listeners.contains(l)) listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    private fun notifyAll2() { for (l in listeners) runCatching { l() } }

    /** Dipanggil dari layanan aksesibilitas tiap jendela depan berganti. */
    fun onWindow(c: Context, pkg: String?, cls: String? = null) {
        val p = pkg ?: return
        if (p == c.packageName) {
            // layar kita sendiri (launcher/pengaturan) di depan = bukan game. Abaikan jendela overlay (bukan Activity).
            if (cls != null && cls.endsWith("Activity")) { lastPkg = ""; recheck(c) }
            return
        }
        if (p == "com.android.systemui" || p == "android" || p.contains("inputmethod") || p.contains("keyboard")) return
        lastPkg = p
        recheck(c)
    }

    /** Hitung ulang (mis. layar diputar). */
    fun recheck(c: Context) {
        val p = lastPkg
        val now = p.isNotEmpty() && (isGameApp(c, p) || c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        if (now == active) return
        active = now
        notifyAll2()
    }

    @Suppress("DEPRECATION")
    private fun isGameApp(c: Context, pkg: String): Boolean = cache.getOrPut(pkg) {
        val ai = runCatching { c.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@getOrPut false
        (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }
}
