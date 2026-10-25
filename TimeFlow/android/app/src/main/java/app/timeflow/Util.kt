package app.timeflow

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** Konstanta intent/aksi yang dipakai bersama. */
object K {
    const val ACTION_FIRE = "app.timeflow.action.FIRE"
    const val ACTION_RING = "app.timeflow.action.RING"
    const val ACTION_STOP = "app.timeflow.action.STOP"
    const val ACTION_SNOOZE = "app.timeflow.action.SNOOZE"
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_AT = "trigger_at"
    const val EXTRA_KIND = "kind"          // "fire" | "snooze"
    const val RING_TIMEOUT_MS = 10 * 60_000L

    /** URI unik per alarm + aksi, supaya PendingIntent tidak saling menimpa (Intent.filterEquals mengabaikan extras). */
    fun uri(id: String, what: String): Uri = Uri.parse("timeflow://alarm/$id/$what")
}

fun hm(h: Int, m: Int): String = String.format(Locale.ROOT, "%02d:%02d", h, m)

fun writeAtomic(f: File, text: String) {
    val af = AtomicFile(f)
    val os = af.startWrite()
    try {
        os.write(text.toByteArray(Charsets.UTF_8))
        af.finishWrite(os)
    } catch (e: Exception) {
        af.failWrite(os)
        throw e
    }
}

fun readAtomic(f: File): String? =
    if (!f.exists()) null else String(AtomicFile(f).readFully(), Charsets.UTF_8)

/** Pengaturan native kecil (volume alarm, jumlah permintaan izin). */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("timeflow_native", Context.MODE_PRIVATE)
    fun volume(ctx: Context): Int = sp(ctx).getInt("alarm_volume", 80)
    fun setVolume(ctx: Context, v: Int) = sp(ctx).edit().putInt("alarm_volume", v.coerceIn(0, 100)).apply()
    fun notifAsked(ctx: Context): Int = sp(ctx).getInt("notif_asked", 0)
    fun bumpNotifAsked(ctx: Context) = sp(ctx).edit().putInt("notif_asked", notifAsked(ctx) + 1).apply()
}

/** Pemberitahuan internal (service/receiver -> UI) bahwa status alarm berubah. */
object AlarmEvents {
    private val ui = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    fun add(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }
    fun fire() { ui.post { listeners.forEach { runCatching { it() } } } }
}
