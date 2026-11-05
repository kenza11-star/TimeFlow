package app.timeflow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Asisten AI: layanan latar depan (mikrofon). Dua keadaan:
 *  - siaga: hanya mendengarkan kata aktivasi ("ai aktif", "kyn aktif", "wake up")
 *  - aktif: setiap ucapan jadi perintah; notch menampilkan animasi AI; "ai nonaktif" / "sleep" mengembalikan ke siaga.
 */
class AiService : Service() {
    companion object {
        const val ACTION_START = "app.timeflow.ai.START"
        const val ACTION_ACTIVATE = "app.timeflow.ai.ACTIVATE"
        const val ACTION_SLEEP = "app.timeflow.ai.SLEEP"
        const val ACTION_STOP = "app.timeflow.ai.STOP"
        private const val CH = "flow_ai"
        private const val NID = 4107

        @Volatile var running = false
        @Volatile var active = false
        @Volatile var lastError = ""

        fun hasMic(c: Context): Boolean = ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Dipanggil saat peluncur tampil: nyalakan mode siaga bila pengguna mengaktifkannya (mikrofon latar depan tidak bisa dimulai dari boot). */
        fun ensure(c: Context) {
            if (running || !AiPrefs.enabled(c) || !AiPrefs.wake(c)) return
            start(c, ACTION_START)
        }

        fun start(c: Context, action: String) {
            if (!hasMic(c)) return
            runCatching { ContextCompat.startForegroundService(c, Intent(c, AiService::class.java).setAction(action)) }
        }

        fun activate(c: Context) = start(c, ACTION_ACTIVATE)
        fun sleep(c: Context) { if (running) start(c, ACTION_SLEEP) }
        fun stop(c: Context) { runCatching { c.stopService(Intent(c, AiService::class.java)) } }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var ears: AiEars? = null
    private var voice: AiVoice? = null
    private var busy = false
    @Volatile private var runId = 0      // tiap perintah baru / pembatalan menaikkan angka ini; hasil perintah lama diabaikan
    private val history = ArrayList<AiLlm.Msg>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP || !hasMic(this) || !AiPrefs.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        running = true
        if (ears == null) {
            voice = AiVoice(this)
            ears = AiEars(this, earsCb)
            ears?.start()
        }
        when (action) {
            ACTION_ACTIVATE -> activateAi(true)
            ACTION_SLEEP -> sleepAi(true)
            else -> if (!active && !AiPrefs.wake(this)) { stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        active = false
        runId++
        ui.removeCallbacksAndMessages(null)
        ears?.stop(); ears = null
        voice?.shutdown(); voice = null
        AiIsland.state(applicationContext, 0, "")
        super.onDestroy()
    }

    // ---------- notifikasi latar depan ----------
    private fun goForeground(): Boolean {
        return try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Asisten AI", NotificationManager.IMPORTANCE_LOW))
            val n = buildNotif()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(NID, n)
            true
        } catch (e: Exception) {
            lastError = "Tidak bisa memulai layanan mikrofon: " + (e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun buildNotif(): Notification {
        val stopPi = PendingIntent.getService(this, 31, Intent(this, AiService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openPi = PendingIntent.getActivity(this, 32, Intent(this, AiSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(if (active) "Asisten AI aktif" else "Asisten AI siaga")
            .setContentText(if (active) "Mendengarkan perintah kamu" else "Menunggu kata aktivasi")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_alarm), "Matikan", stopPi).build())
            .build()
    }

    private fun refreshNotif() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NID, buildNotif()) }
    }

    // ---------- telinga ----------
    private val earsCb = object : AiEars.Callback {
        override fun onPartial(text: String) { if (active && !busy) AiIsland.state(applicationContext, 1, text) }
        override fun onFinal(text: String) { handle(text) }
        override fun onLevel(level: Float) { if (active) AiIsland.level(level) }
        override fun onFatal(message: String) { lastError = message; stopSelf() }
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun handle(raw: String) {
        val t = norm(raw)
        if (t.isEmpty()) return
        val tokens = t.split(' ')
        val names = AiPrefs.names(this)
        if (!active) {
            if (isWake(t, tokens, names)) {
                activateAi(true)
                // "kyn aktif tolong buka youtube": sisa kalimat setelah kata aktivasi langsung jadi perintah
                val rest = afterWake(raw, names)
                if (rest.length >= 3) ui.postDelayed({ if (active && !busy) runCommand(rest) }, 900)
            }
            return
        }
        if (isSleep(t, tokens)) { sleepAi(true); return }
        if (tokens.size <= 3 && tokens.any { it == "stop" || it == "berhenti" || it == "batal" || it == "cancel" || it == "diam" }) {
            if (busy) { runId++; busy = false; voice?.stop(); island(1, ""); ears?.resume() }
            return
        }
        if (busy) return
        val cmd = stripNames(raw, names)
        if (cmd.length >= 2) runCommand(cmd)
    }

    private val WAKE = setOf("aktif", "aktifkan", "bangun", "nyala", "hidup", "on")
    private val HEY = setOf("hey", "hei", "hai", "halo", "oke", "ok", "okay", "eh")

    private fun isWake(t: String, tokens: List<String>, names: List<String>): Boolean {
        if (t.contains("wake up")) return true
        return tokens.any { it in names } && tokens.any { it in WAKE }
    }

    private fun afterWake(raw: String, names: List<String>): String {
        val words = raw.trim().split(Regex("\\s+"))
        var i = 0
        while (i < words.size) {
            val w = norm(words[i])
            if (w.isEmpty() || w in names || w in WAKE || w in HEY || w == "wake" || w == "up") i++ else break
        }
        return words.drop(i).joinToString(" ").trim()
    }

    private fun isSleep(t: String, tokens: List<String>): Boolean {
        if (tokens.size > 4) return false
        return t.contains("non aktif") || tokens.any { it == "nonaktif" || it == "nonaktifkan" || it == "sleep" || it == "tidur" } ||
            (t.contains("matikan") && tokens.size <= 3)
    }

    private fun stripNames(raw: String, names: List<String>): String {
        val words = raw.trim().split(Regex("\\s+"))
        var i = 0
        while (i < words.size) {
            val w = norm(words[i])
            if (w.isEmpty() || w in names || w in HEY) i++ else break
        }
        return words.drop(i).joinToString(" ").trim()
    }

    // ---------- keadaan ----------
    private fun island(state: Int, text: String) {
        if (active) AiIsland.state(applicationContext, state, text)
    }

    private fun speakThen(text: String, then: () -> Unit) {
        if (!AiPrefs.speak(this)) { ui.postDelayed({ then() }, 1800); return }
        ears?.pause()
        voice?.speak(text, onError = { msg -> lastError = msg }) { ears?.resume(); then() }
    }

    private fun activateAi(announce: Boolean) {
        if (active) return
        active = true
        busy = false
        runId++
        history.clear()
        island(1, "")
        refreshNotif()
        if (announce) speakThen("Siap.") { island(1, "") }
    }

    private fun sleepAi(announce: Boolean) {
        if (!active) return
        runId++
        voice?.stop()
        val finish = {
            active = false
            busy = false
            AiIsland.state(applicationContext, 0, "")
            refreshNotif()
            if (!AiPrefs.wake(this)) stopSelf()
        }
        if (announce) {
            island(3, "Oke, saya tidur")
            speakThen("Oke, saya tidur.") { finish() }
        } else finish()
    }

    private fun runCommand(cmd: String) {
        busy = true
        val id = ++runId
        island(2, cmd)
        val io = object : AiAgent.Io {
            override fun status(text: String) { ui.post { if (active && busy) island(if (text == "Berpikir") 2 else 4, text) } }
            override fun cancelled(): Boolean = !active || id != runId
        }
        val ctx = applicationContext
        Thread {
            val reply = try {
                AiAgent.run(ctx, history, cmd, io)
            } catch (e: Exception) {
                lastError = AiLlm.friendly(e)
                "Maaf, ada masalah: " + AiLlm.friendly(e)
            }
            ui.post { finishRun(reply, id) }
        }.start()
    }

    private fun finishRun(reply: String, id: Int) {
        if (id != runId) return
        if (!active) { busy = false; return }
        island(3, reply)
        speakThen(reply) { busy = false; island(1, "") }
    }
}
