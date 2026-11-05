package app.timeflow

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Suara asisten. Utama: ElevenLabs (API key milikmu). Cadangan otomatis: suara bawaan HP bila ElevenLabs belum diisi atau gagal
 * (termasuk bila pemutaran mp3 gagal). Semua callback dipanggil di thread utama.
 */
class AiVoice(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var ttsDone: (() -> Unit)? = null
    private var token = 0
    private var watchdog: Runnable? = null
    private var focus: AudioFocusRequest? = null
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Aliran media (volume media HP), bukan aliran "asisten" yang di sebagian HP volumenya terpisah atau 0.
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun grabFocus() {
        runCatching {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
            focus = r
            am.requestAudioFocus(r)
        }
    }

    private fun dropFocus() {
        runCatching { focus?.let { am.abandonAudioFocusRequest(it) } }
        focus = null
    }

    fun speak(text: String, onError: ((String) -> Unit)? = null, onDone: () -> Unit) {
        stop()
        val t = text.trim()
        if (t.isEmpty()) { ui.post { onDone() }; return }
        val my = ++token
        val key = AiPrefs.elKey(ctx).trim()
        if (key.isEmpty()) { fallback(t, onDone); return }
        Thread {
            try {
                val f = fetch(t, key)
                ui.post { if (my == token) play(f, t, my, onError, onDone) else f.delete() }
            } catch (e: Exception) {
                val msg = AiLlm.friendly(e)
                ui.post {
                    if (my == token) {
                        onError?.invoke(msg)
                        fallback(t, onDone)
                    }
                }
            }
        }.start()
    }

    fun stop() {
        token++
        ttsDone = null
        pendingText = null
        cancelWatch()
        mp?.let { p -> mp = null; runCatching { p.stop() }; runCatching { p.release() } }
        runCatching { tts?.stop() }
        dropFocus()
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private fun fetch(text: String, key: String): File {
        val voice = AiPrefs.elVoice(ctx).trim().ifEmpty { "21m00Tcm4TlvDq8ikWAM" }
        val model = AiPrefs.elModel(ctx).trim().ifEmpty { "eleven_flash_v2_5" }
        val u = URL("https://api.elevenlabs.io/v1/text-to-speech/" + URLEncoder.encode(voice, "UTF-8") + "?output_format=mp3_44100_64")
        val c = u.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 12000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("xi-api-key", key)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "audio/mpeg")
            val body = JSONObject().put("text", text).put("model_id", model)
                .put("voice_settings", JSONObject().put("stability", 0.45).put("similarity_boost", 0.8)).toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw AiLlm.AiError("ElevenLabs " + AiLlm.errMsg(code, err))
            }
            val f = File(ctx.cacheDir, "ai_tts_" + System.nanoTime() + ".mp3")
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (f.length() < 800) { f.delete(); throw AiLlm.AiError("ElevenLabs mengirim audio kosong") }
            return f
        } finally {
            c.disconnect()
        }
    }

    private fun play(f: File, text: String, my: Int, onError: ((String) -> Unit)?, onDone: () -> Unit) {
        val p = MediaPlayer()
        mp = p
        var ended = false
        val finish = { ok: Boolean, why: String ->
            if (!ended) {
                ended = true
                if (mp === p) mp = null
                runCatching { p.release() }
                f.delete()
                if (my == token) {
                    if (ok) { dropFocus(); onDone() }
                    else { onError?.invoke(why); fallback(text, onDone) }   // mp3 gagal diputar: pakai suara bawaan HP
                } else dropFocus()
            }
        }
        try {
            grabFocus()
            p.setAudioAttributes(attrs)
            p.setVolume(1f, 1f)
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { finish(true, "") }
            p.setOnErrorListener { _, w, e -> finish(false, "pemutar audio gagal ($w/$e)"); true }
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
        } catch (e: Exception) {
            finish(false, "pemutar audio gagal: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---- cadangan: suara bawaan HP ----
    private fun fallback(text: String, onDone: () -> Unit) {
        ttsDone = onDone
        pendingText = text
        cancelWatch()
        // jaga-jaga: bila mesin suara HP tidak pernah membalas, jangan biarkan asisten macet
        val w = Runnable { finishTts() }
        watchdog = w
        ui.postDelayed(w, 6000L + text.length * 130L)
        grabFocus()
        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { st -> ui.post { onTtsInit(st == TextToSpeech.SUCCESS) } }
        } else if (ttsReady) flushTts()
    }

    private fun onTtsInit(ok: Boolean) {
        ttsReady = ok
        val t = tts ?: return
        if (!ok) { AiService.lastError = "Suara bawaan HP tidak tersedia (pasang mesin suara Google)"; finishTts(); return }
        runCatching { t.setAudioAttributes(attrs) }
        val lang = AiPrefs.lang(ctx)
        val r = runCatching { t.setLanguage(Locale.forLanguageTag(lang)) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            runCatching { t.language = Locale.getDefault() }
            AiService.lastError = "Suara bawaan HP belum punya bahasa $lang"
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { ui.post { finishTts() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { AiService.lastError = "Suara bawaan HP gagal berbicara"; ui.post { finishTts() } }
        })
        flushTts()
    }

    private fun flushTts() {
        val t = tts ?: return
        val s = pendingText
        if (s == null) { finishTts(); return }
        pendingText = null
        val rc = t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "ai")
        if (rc != TextToSpeech.SUCCESS) { AiService.lastError = "Suara bawaan HP menolak berbicara"; finishTts() }
    }

    private fun cancelWatch() {
        watchdog?.let { ui.removeCallbacks(it) }
        watchdog = null
    }

    private fun finishTts() {
        cancelWatch()
        dropFocus()
        val d = ttsDone
        ttsDone = null
        d?.invoke()
    }
}
