package app.timeflow

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Pemutar audio alarm native: AudioAttributes USAGE_ALARM (stream alarm), looping.
 * Volume = skala dalam aplikasi (0..1) x volume stream alarm sistem; volume sistem TIDAK diubah.
 */
class SoundPlayer(private val ctx: Context) {
    private var mp: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun play(soundId: String, loop: Boolean, onEnd: (() -> Unit)? = null) {
        stop()
        requestFocus()
        start(soundId, loop, onEnd, allowFallback = soundId != SoundLibrary.DEFAULT_ID)
    }

    private fun start(soundId: String, loop: Boolean, onEnd: (() -> Unit)?, allowFallback: Boolean) {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attrs)
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK)
            if (!SoundLibrary.setSource(ctx, p, soundId)) throw IllegalStateException("sumber audio tidak ada")
            p.isLooping = loop
            val v = Prefs.volume(ctx) / 100f
            p.setVolume(v, v)
            p.setOnErrorListener { bad, _, _ ->
                bad.release()
                if (mp === bad) mp = null
                if (allowFallback) start(SoundLibrary.DEFAULT_ID, loop, onEnd, false)
                true
            }
            p.setOnCompletionListener { onEnd?.invoke() }
            p.setOnPreparedListener { it.start() }
            mp = p
            p.prepareAsync()
        } catch (e: Exception) {
            runCatching { p.release() }
            if (mp === p) mp = null
            // Audio kustom hilang/rusak -> pakai nada bawaan agar alarm tetap berbunyi.
            if (allowFallback) start(SoundLibrary.DEFAULT_ID, loop, onEnd, false)
        }
    }

    fun stop() {
        mp?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mp = null
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun requestFocus() {
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(f)
        focus = f
    }
}

/** Getaran alarm dengan pola yang dapat dipilih. */
object Vibe {
    private val PATTERNS = arrayOf(
        longArrayOf(0, 700, 500),            // 0 standar
        longArrayOf(0, 200, 200, 200, 1000), // 1 pendek berulang
        longArrayOf(0, 1500, 500),           // 2 panjang
    )

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    fun available(ctx: Context): Boolean = vibrator(ctx)?.hasVibrator() == true

    @Suppress("DEPRECATION")
    fun start(ctx: Context, pattern: Int) {
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        val a = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        v.vibrate(VibrationEffect.createWaveform(PATTERNS[pattern.coerceIn(0, 2)], 0), a)
    }

    fun stop(ctx: Context) { vibrator(ctx)?.cancel() }
}
