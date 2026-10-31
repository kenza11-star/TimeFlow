package app.timeflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId

/** Menerima pemicu dari AlarmManager (fire / snooze), menjadwalkan kejadian berikutnya, lalu memulai service dering. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != K.ACTION_FIRE) return
        val id = intent.getStringExtra(K.EXTRA_ID) ?: return
        val kind = intent.getStringExtra(K.EXTRA_KIND) ?: "fire"
        val at = intent.getLongExtra(K.EXTRA_AT, System.currentTimeMillis())
        val a = AlarmStore.get(ctx, id) ?: return          // alarm sudah dihapus
        val isTest = id == Alarm.TEST_ID

        if (kind == "snooze") {
            if (!isTest) {
                if (a.snoozeAt == 0L) return                // event ganda -> abaikan
                AlarmStore.update(ctx, id) { it.copy(snoozeAt = 0L) }
            }
        } else if (!isTest) {
            if (!a.enabled) return                          // dimatikan sebelum sempat berbunyi
            if (at <= a.lastFired) return                   // event ganda / usang -> abaikan
            val now = System.currentTimeMillis()
            val next = a.nextTrigger(maxOf(at, now))
            var updated = a.copy(lastFired = at)
            if (a.days.isEmpty()) {
                // Sekali jalan: nonaktif setelah dering terakhir pada jendela hari itu.
                val zone = ZoneId.systemDefault()
                val sameDay = next != null &&
                    Instant.ofEpochMilli(next).atZone(zone).toLocalDate() == Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                if (!(a.everyMin > 0 && sameDay)) updated = updated.copy(enabled = false)
            }
            AlarmStore.update(ctx, id) { updated }
            AlarmScheduler.schedule(ctx, updated)
            AlarmEvents.fire()
        }

        try {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, AlarmService::class.java).setAction(K.ACTION_RING).putExtra(K.EXTRA_ID, id).putExtra(K.EXTRA_AT, at),
            )
        } catch (_: Exception) {
            Notifs.fallbackRing(ctx, a, at)
        }
    }
}

/** Boot, perubahan waktu/zona waktu, pembaruan aplikasi, perubahan izin alarm tepat waktu -> jadwalkan ulang semua. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIME_CHANGED) AlarmStore.resetLastFired(ctx)
        AlarmScheduler.rescheduleAll(ctx)
        runCatching { WidgetUtil.updateAll(ctx) }   // widget ikut menyesuaikan waktu/zona waktu baru
        AlarmEvents.fire()
    }
}
