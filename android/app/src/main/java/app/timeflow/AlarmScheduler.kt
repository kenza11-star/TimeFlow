package app.timeflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Penjadwalan lewat AlarmManager.setAlarmClock (tepat waktu, tetap jalan saat Doze).
 * Satu PendingIntent per (alarm, jenis) -> menjadwalkan ulang otomatis menimpa yang lama, sehingga tidak ada alarm ganda.
 */
object AlarmScheduler {
    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun pending(ctx: Context, id: String, kind: String, at: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, AlarmReceiver::class.java).setAction(K.ACTION_FIRE).setData(K.uri(id, kind))
                .putExtra(K.EXTRA_ID, id).putExtra(K.EXTRA_KIND, kind).putExtra(K.EXTRA_AT, at),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun set(ctx: Context, id: String, kind: String, at: Long) {
        val pi = pending(ctx, id, kind, at)
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        try {
            if (canExact(ctx)) am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            else am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // tanpa izin: tidak tepat waktu, tetap menyala
        } catch (_: SecurityException) {
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: String) {
        for (kind in arrayOf("fire", "snooze")) am(ctx).cancel(pending(ctx, id, kind, 0))
    }

    /** Jadwalkan jadwal reguler (berikutnya) dan, jika ada, tunda (snooze) yang masih berlaku. */
    fun schedule(ctx: Context, a: Alarm) {
        cancel(ctx, a.id)
        if (!a.enabled) return
        val now = System.currentTimeMillis()
        a.nextTrigger(now)?.let { set(ctx, a.id, "fire", it) }
        if (a.snoozeAt > now) set(ctx, a.id, "snooze", a.snoozeAt)
    }

    fun scheduleSnooze(ctx: Context, id: String, at: Long) = set(ctx, id, "snooze", at)

    fun scheduleTest(ctx: Context, at: Long) = set(ctx, Alarm.TEST_ID, "fire", at)

    fun rescheduleAll(ctx: Context) {
        AlarmStore.all(ctx).forEach { schedule(ctx, it) }
    }
}
