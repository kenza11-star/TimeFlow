package app.timeflow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

object Notifs {
    const val CH_ALARM = "alarm_ring_v1"
    const val CH_MISSED = "alarm_missed_v1"
    const val FG_ID = 4242
    const val MISSED_ID = 4243

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Suara & getar ditangani MediaPlayer/Vibrator (stream alarm), jadi channel dibuat senyap agar tidak dobel.
        val ring = NotificationChannel(CH_ALARM, "Alarm berbunyi", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notifikasi saat alarm berbunyi, dengan tombol Tunda dan Stop"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(CH_MISSED, "Alarm terlewat", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Pemberitahuan jika alarm dibiarkan berbunyi tanpa dihentikan"
        }
        nm.createNotificationChannels(listOf(ring, missed))
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun serviceAction(ctx: Context, action: String, id: String): PendingIntent =
        PendingIntent.getService(
            ctx, 0,
            Intent(ctx, AlarmService::class.java).setAction(action).setData(K.uri(id, action)).putExtra(K.EXTRA_ID, id),
            flags(),
        )

    fun ringScreen(ctx: Context, id: String): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, AlarmRingActivity::class.java).setData(K.uri(id, "ring")).putExtra(K.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags(),
        )

    fun ringing(ctx: Context, a: Alarm, at: Long, others: Int): Notification {
        val title = a.label.ifBlank { "Alarm" }
        val text = "Pukul " + hm(a.hour, a.minute) + if (others > 0) " - dan $others alarm lain" else ""
        val screen = ringScreen(ctx, a.id)
        return NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setWhen(at)
            .setShowWhen(true)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(0, "Tunda ${a.snoozeMin} mnt", serviceAction(ctx, K.ACTION_SNOOZE, a.id))
            .addAction(0, "Stop", serviceAction(ctx, K.ACTION_STOP, a.id))
            .build()
    }

    fun genericForeground(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

    fun missed(ctx: Context, a: Alarm) {
        val n = NotificationCompat.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Alarm terlewat")
            .setContentText((a.label.ifBlank { "Alarm" }) + " pukul " + hm(a.hour, a.minute))
            .setAutoCancel(true)
            .build()
        notifySafe(ctx, MISSED_ID, n)
    }

    /** Cadangan bila service foreground gagal dimulai: notifikasi layar penuh agar pengguna tetap diberi tahu. */
    fun fallbackRing(ctx: Context, a: Alarm, at: Long) = notifySafe(ctx, FG_ID + 1, ringing(ctx, a, at, 0))

    fun notifySafe(ctx: Context, id: Int, n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) { }
    }

    /** Status izin & kondisi sistem yang memengaruhi alarm (dipakai layar "Izin & notifikasi" di web). */
    fun status(ctx: Context): JSONObject {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val compat = NotificationManagerCompat.from(ctx)
        val runtimeNeeded = Build.VERSION.SDK_INT >= 33
        val granted = !runtimeNeeded || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelOn = nm.getNotificationChannel(CH_ALARM)?.importance != NotificationManager.IMPORTANCE_NONE
        val am = ctx.getSystemService(AudioManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val filter = nm.currentInterruptionFilter
        return JSONObject()
            .put("sdk", Build.VERSION.SDK_INT)
            .put("notif", JSONObject().put("runtime", runtimeNeeded).put("granted", granted)
                .put("enabled", compat.areNotificationsEnabled() && channelOn))
            .put("exact", JSONObject().put("needed", Build.VERSION.SDK_INT >= 31).put("granted", AlarmScheduler.canExact(ctx)))
            .put("fsi", JSONObject().put("needed", Build.VERSION.SDK_INT >= 34)
                .put("granted", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()))
            .put("battery", JSONObject().put("unrestricted", pm.isIgnoringBatteryOptimizations(ctx.packageName)))
            .put("vol", JSONObject().put("cur", am.getStreamVolume(AudioManager.STREAM_ALARM)).put("max", am.getStreamMaxVolume(AudioManager.STREAM_ALARM)))
            .put("dnd", filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
            .put("vibrator", Vibe.available(ctx))
    }
}
