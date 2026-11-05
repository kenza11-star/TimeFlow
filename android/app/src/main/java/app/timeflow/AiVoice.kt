package app.timeflow

import android.content.Context
import android.media.AudioAttributes
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
 * Suara asisten. Utama: ElevenLabs (API key milikmu). Cadangan otomatis: suara bawaan HP bila ElevenLabs belum diisi atau gagal.
 * Semua callback dipanggil di thread utama.
 */
class AiVoice(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var ttsDone: (() -> Unit)? = null
    private var token = 0

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
                ui.post { if (my == token) play(f, my, onDone) else f.delete() }
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
        mp?.let { p -> mp = null; runCatching { p.stop() }; runCatching { p.release() } }
        runCatching { tts?.stop() }
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
            val body = JSONObject().put("text", text).put("model_id", model).toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw AiLlm.AiError("ElevenLabs " + AiLlm.errMsg(code, err))
            }
            val f = File(ctx.cacheDir, "ai_tts_" + System.nanoTime() + ".mp3")
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            return f
        } finally {
            c.disconnect()
        }
    }

    private fun play(f: File, my: Int, onDone: () -> Unit) {
        val p = MediaPlayer()
        mp = p
        val finish = {
            if (mp === p) mp = null
            runCatching { p.release() }
            f.delete()
            if (my == token) onDone()
        }
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { finish() }
            p.setOnErrorListener { _, _, _ -> finish(); true }
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
        } catch (e: Exception) {
            finish()
        }
    }

    // ---- cadangan: suara bawaan HP ----
    private fun fallback(text: String, onDone: () -> Unit) {
        ttsDone = onDone
        pendingText = text
        if (tts == null) {
            tts = TextToSpeech(ctx.applicationContext) { st -> ui.post { onTtsInit(st == TextToSpeech.SUCCESS) } }
        } else if (ttsReady) flushTts()
    }

    private fun onTtsInit(ok: Boolean) {
        ttsReady = ok
        val t = tts ?: return
        if (!ok) { finishTts(); return }
        runCatching { t.language = Locale.forLanguageTag(AiPrefs.lang(ctx)) }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { ui.post { finishTts() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { ui.post { finishTts() } }
        })
        flushTts()
    }

    private fun flushTts() {
        val t = tts ?: return
        val s = pendingText
        if (s == null) { finishTts(); return }
        pendingText = null
        t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "ai")
    }

    private fun finishTts() {
        val d = ttsDone
        ttsDone = null
        d?.invoke()
    }
}
