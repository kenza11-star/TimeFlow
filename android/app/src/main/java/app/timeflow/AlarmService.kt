package app.timeflow

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat

/**
 * Service foreground yang membunyikan audio + getar dan menampilkan notifikasi Tunda/Stop.
 * Beberapa alarm bersamaan: satu notifikasi/suara untuk alarm "utama" (yang pertama), sisanya antre dan dihitung.
 */
class AlarmService : Service() {
    private val active = LinkedHashMap<String, Long>()   // id -> waktu pemicu
    private val timeouts = HashMap<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: SoundPlayer
    private var playingId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        player = SoundPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(K.EXTRA_ID)
        when (intent?.action) {
            K.ACTION_RING -> {
                // Wajib memanggil startForeground secepatnya setelah startForegroundService.
                promote(Notifs.genericForeground(this))
                if (id != null) ring(id, intent.getLongExtra(K.EXTRA_AT, System.currentTimeMillis()))
            }
            K.ACTION_STOP -> if (id != null) finishAlarm(id, snooze = false)
            K.ACTION_SNOOZE -> if (id != null) finishAlarm(id, snooze = true)
        }
        if (active.isEmpty()) shutdown()
        return START_NOT_STICKY
    }

    private fun promote(n: android.app.Notification) =
        ServiceCompat.startForeground(this, Notifs.FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

    private fun ring(id: String, at: Long) {
        if (active.containsKey(id)) return                 // event ganda -> jangan putar dua kali
        if (AlarmStore.get(this, id) == null) return
        active[id] = at
        publish()
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TimeFlow:alarm")
                .apply { acquire(K.RING_TIMEOUT_MS + 60_000L) }
        }
        val t = Runnable { missed(id) }
        timeouts[id] = t
        handler.postDelayed(t, K.RING_TIMEOUT_MS)
        applyPrimary()
    }

    private fun missed(id: String) {
        AlarmStore.get(this, id)?.let { Notifs.missed(this, it) }
        remove(id)
        afterChange()
    }

    private fun finishAlarm(id: String, snooze: Boolean) {
        if (snooze) {
            AlarmStore.get(this, id)?.let { a ->
                val at = System.currentTimeMillis() + a.snoozeMin * 60_000L
                AlarmStore.update(this, id) { it.copy(snoozeAt = at) }
                AlarmScheduler.scheduleSnooze(this, id, at)
            }
        }
        remove(id)
        afterChange()
    }

    private fun remove(id: String) {
        active.remove(id)
        timeouts.remove(id)?.let { handler.removeCallbacks(it) }
        if (playingId == id) playingId = null
    }

    private fun afterChange() {
        publish()
        AlarmEvents.fire()
        if (active.isEmpty()) shutdown() else { publish(); applyPrimary() }
    }

    private fun publish() { ringing = active.keys.toSet(); NotchListener.alarmChanged(this) }

    /** Alarm utama = yang pertama di antrean; bunyi & getar mengikuti pengaturan alarm tersebut. */
    private fun applyPrimary() {
        val id = active.keys.firstOrNull() ?: return
        val a = AlarmStore.get(this, id)
        if (a == null) { remove(id); afterChange(); return }
        promote(Notifs.ringing(this, a, active[id] ?: System.currentTimeMillis(), active.size - 1))
        if (playingId != id) {
            playingId = id
            player.play(a.sound, loop = true)
            if (a.vibrate) Vibe.start(this, a.vibPattern) else Vibe.stop(this)
        }
    }

    private fun shutdown() {
        player.stop()
        Vibe.stop(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        playingId = null
        ringing = emptySet()
        NotchListener.alarmChanged(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.stop()
        Vibe.stop(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        ringing = emptySet()
        NotchListener.alarmChanged(this)
        super.onDestroy()
    }

    companion object {
        /** Id alarm yang sedang berbunyi (dibaca AlarmRingActivity / UI). */
        @Volatile var ringing: Set<String> = emptySet()
            private set

        fun stop(ctx: Context, id: String) = ctx.startService(
            Intent(ctx, AlarmService::class.java).setAction(K.ACTION_STOP).putExtra(K.EXTRA_ID, id))

        fun snooze(ctx: Context, id: String) = ctx.startService(
            Intent(ctx, AlarmService::class.java).setAction(K.ACTION_SNOOZE).putExtra(K.EXTRA_ID, id))
    }
}
