package app.timeflow

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

/** Pengaturan layar kunci TimeFlow. */
object LockPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("lockscreen", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun music(c: Context) = sp(c).getBoolean("music", true)
    fun wallFile(c: Context) = File(c.filesDir, "lock_wall.jpg")

    fun toJson(c: Context): JSONObject =
        JSONObject().put("enabled", enabled(c)).put("music", music(c)).put("wall", wallFile(c).exists())

    fun set(c: Context, o: JSONObject) {
        val e = sp(c).edit()
        if (o.has("enabled")) e.putBoolean("enabled", o.optBoolean("enabled"))
        if (o.has("music")) e.putBoolean("music", o.optBoolean("music"))
        e.apply()
    }

    fun saveWall(c: Context, b64: String): Boolean = runCatching {
        val bytes = android.util.Base64.decode(b64.substringAfter(","), android.util.Base64.DEFAULT)
        if (bytes.size < 100 || bytes.size > 8_000_000) return false
        wallFile(c).writeBytes(bytes); true
    }.getOrDefault(false)

    fun clearWall(c: Context) { runCatching { wallFile(c).delete() } }
}

/**
 * Layar kunci buatan TimeFlow. Android tidak mengizinkan aplikasi biasa MENGGANTI layar kunci sistem, jadi yang dilakukan:
 * saat layar menyala dan HP terkunci, TimeFlow menampilkan LockActivity di ATAS layar kunci sistem (seperti layar alarm).
 * Geser ke atas memanggil requestDismissKeyguard: sistem lalu meminta PIN / pola / sidik jari / wajah seperti biasa.
 */
object LockScreen {
    private var registered = false
    private const val NID = 7731
    private const val CH = "lockscreen"

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != Intent.ACTION_SCREEN_ON) return
            if (!LockPrefs.enabled(c)) return
            if (AlarmService.ringing.isNotEmpty()) return            // jangan menutupi layar alarm
            val km = c.getSystemService(KeyguardManager::class.java)
            if (km == null || !km.isKeyguardLocked) return
            launch(c)
        }
    }

    @Synchronized fun register(ctx: Context) {
        if (registered) return
        val app = ctx.applicationContext
        runCatching {
            ContextCompat.registerReceiver(app, rcv, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
    }

    fun launch(ctx: Context) {
        val app = ctx.applicationContext
        val i = Intent(app, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { app.startActivity(i) }
        // Jalur cadangan: full-screen intent (cara yang sama dengan layar alarm), untuk HP yang memblokir start dari latar belakang.
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "Layar kunci TimeFlow", NotificationManager.IMPORTANCE_HIGH).apply { setShowBadge(false); setSound(null, null) })
            val pi = PendingIntent.getActivity(app, 7731, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, CH)
                .setSmallIcon(R.drawable.ic_stat_alarm).setContentTitle("TimeFlow").setContentText("Layar kunci")
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true).setAutoCancel(true).setTimeoutAfter(3000).setSilent(true).build()
            nm.notify(NID, n)
        }
    }

    fun clearNotification(ctx: Context) { runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NID) } }
}
