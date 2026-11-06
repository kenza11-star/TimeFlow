package app.timeflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Telinga asisten: pengenal suara Android (SpeechRecognizer) yang diulang terus-menerus.
 * Harus dibuat dan dipakai di thread utama. Hasil akhir lewat Callback.onFinal.
 */
class AiEars(private val ctx: Context, private val cb: Callback) {
    interface Callback {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onLevel(level: Float)
        fun onFatal(message: String)
        /** Masalah sementara pengenal suara (bukan "tidak ada ucapan"); untuk ditampilkan agar kelihatan kenapa tidak mendengar. */
        fun onProblem(message: String) {}
    }

    companion object {
        /** Catatan singkat untuk layar Asisten AI: layanan suara yang dipakai dan galat terakhir. */
        @Volatile var diag: String = ""
        private const val GOOGLE = "com.google.android.googlequicksearchbox"

        private fun googleService(ctx: Context): ComponentName? {
            val l = runCatching { ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0) }.getOrNull().orEmpty()
            val g = l.firstOrNull { it.serviceInfo.packageName == GOOGLE } ?: return null
            return ComponentName(g.serviceInfo.packageName, g.serviceInfo.name)
        }

        private fun errName(e: Int) = when (e) {
            1 -> "network timeout"; 2 -> "jaringan"; 3 -> "audio/mikrofon"; 4 -> "server"; 5 -> "client"; 6 -> "tidak ada ucapan"
            7 -> "tidak cocok"; 8 -> "sibuk"; 9 -> "izin"; 10 -> "terlalu sering"; 11 -> "server terputus"
            12 -> "bahasa tidak didukung"; 13 -> "bahasa belum terpasang"; else -> "lain"
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var useDefault = false      // true bila layanan Google gagal berulang: pakai pengenal suara bawaan HP
    private var fails = 0
    private var netFails = 0            // galat jaringan beruntun: jeda mulai ulang makin panjang agar hemat baterai saat offline
    private var svcName = ""
    private var errCount = 0
    private var sr: SpeechRecognizer? = null
    private var running = false
    private var paused = false
    private var muted = false

    fun start() {
        if (running) return
        running = true
        paused = false
        muteTones(true)
        ui.post { listen() }
    }

    fun stop() {
        running = false
        ui.removeCallbacksAndMessages(null)
        runCatching { sr?.destroy() }
        sr = null
        muteTones(false)
    }

    /** Jeda saat asisten berbicara supaya tidak mendengar suaranya sendiri. */
    fun pause() {
        paused = true
        ui.removeCallbacksAndMessages(null)
        runCatching { sr?.cancel() }
        muteTones(false)
    }

    fun resume() {
        if (!running || !paused) return
        paused = false
        muteTones(true)
        ui.postDelayed({ listen() }, 250)
    }

    private fun listen() {
        if (!running || paused) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { cb.onFatal("Pengenal suara tidak tersedia di HP ini (pasang / aktifkan layanan suara Google)"); return }
        val r = sr ?: run {
            // Utamakan layanan suara Google (di sebagian HP pengenal bawaan merek tidak mengembalikan teks sama sekali)
            val comp = if (useDefault) null else googleService(ctx)
            val g = if (comp != null) runCatching { SpeechRecognizer.createSpeechRecognizer(ctx, comp) }.getOrNull() else null
            svcName = if (g != null) "Google" else "bawaan HP"
            (g ?: SpeechRecognizer.createSpeechRecognizer(ctx)).also { sr = it; it.setRecognitionListener(listener) }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, AiPrefs.lang(ctx))
            if (!Net.good(ctx)) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)   // offline / sinyal jelek: pakai model suara di HP bila ada
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 1300L)
        }
        runCatching { r.startListening(i) }.onFailure { restart(800, true) }
    }

    private fun restart(delay: Long, recreate: Boolean = false) {
        if (!running || paused) return
        if (recreate) { runCatching { sr?.destroy() }; sr = null }
        ui.postDelayed({ listen() }, delay)
    }

    private fun best(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {
            val t = best(partialResults)
            if (t.isNotEmpty()) { fails = 0; netFails = 0; diag = "Pengenal suara ($svcName): terdengar \"" + t.take(40) + "\""; cb.onPartial(t) }
        }
        override fun onResults(results: Bundle?) {
            val t = best(results)
            if (t.isNotEmpty()) { fails = 0; netFails = 0; diag = "Pengenal suara ($svcName): terdengar \"" + t.take(40) + "\""; cb.onFinal(t) }
            restart(120)
        }
        override fun onError(error: Int) {
            errCount++
            diag = "Pengenal suara ($svcName): galat terakhir $error (${errName(error)}), total $errCount"
            if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && error != SpeechRecognizer.ERROR_NO_MATCH) {
                fails++
                if (fails >= 3 && !useDefault) { useDefault = true; fails = 0; runCatching { sr?.destroy() }; sr = null }
            }
            when (error) {
                SpeechRecognizer.ERROR_AUDIO -> cb.onProblem("Mikrofon tidak bisa direkam (kode 3): cek izin mikrofon atau aplikasi lain yang memakai mikrofon")
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> {}
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    cb.onProblem(if (!Net.connected(ctx)) "Tidak ada internet: pengenal suara butuh internet (atau unduh bahasa Indonesia offline di pengaturan Google > Suara)" else "Sinyal jelek: pengenal suara belum bisa terhubung (kode $error)")
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> cb.onProblem("Pengenal suara sedang dipakai aplikasi lain")
                12, 13 -> cb.onProblem("Bahasa ${AiPrefs.lang(ctx)} belum terpasang di layanan suara Google (kode $error)")
                else -> {}
            }
            when (error) {
                12, 13 -> restart(4000, true)
                SpeechRecognizer.ERROR_AUDIO -> restart(1500, true)
                10 -> restart(2500, true)           // terlalu sering memulai: beri jeda
                11 -> restart(800, true)            // layanan suara terputus: sambung ulang
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { running = false; cb.onFatal("Izin mikrofon belum diberikan") }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(900, true)
                SpeechRecognizer.ERROR_CLIENT -> restart(500, true)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    netFails++
                    restart(minOf(2000L shl minOf(netFails - 1, 4), 30000L), true)   // 2, 4, 8, 16, 30 detik
                }
                else -> restart(120)    // tidak ada ucapan / tidak cocok: ulangi
            }
        }
    }

    /** Opsional: redam bunyi "ting" pengenal suara (aliran notifikasi dan sistem) selama mendengarkan. */
    private fun muteTones(on: Boolean) {
        if (on && !AiPrefs.muteBeep(ctx)) return
        if (on == muted) return
        muted = on
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val flag = if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (s in intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)) {
            try { am.adjustStreamVolume(s, flag, 0) } catch (_: Exception) {}
        }
    }
}
