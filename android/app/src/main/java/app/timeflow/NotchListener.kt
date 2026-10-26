package app.timeflow

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat

/**
 * Inti fitur notch. Layanan ini di-bind sistem selama "Akses notifikasi" aktif, jadi tetap hidup walau aplikasi ditutup.
 * Ia membaca semua notifikasi, memantau sesi media (Spotify, YouTube Music, dll.) dan menggambar overlay NotchView.
 */
class NotchListener : NotificationListenerService(), NotchHost {
    companion object {
        @Volatile private var inst: NotchListener? = null
        fun connected() = inst != null
        fun accessGranted(ctx: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        fun overlayGranted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
        fun refresh() { inst?.let { i -> i.ui.post { i.apply() } } }
        fun demoNotif(): Boolean { val i = inst ?: return false; i.ui.post { i.demoN() }; return true }
        fun demoMedia(): Boolean { val i = inst ?: return false; i.ui.post { i.demoM() }; return true }
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var view: NotchView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var msm: MediaSessionManager? = null
    private var ctl: MediaController? = null
    private var demoOn = false
    private val seen = HashMap<String, String>()
    private val comp by lazy { ComponentName(this, NotchListener::class.java) }

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> ui.post { pick(list ?: emptyList()) } }
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { pushMedia() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { pushMedia() }
        override fun onSessionDestroyed() { ui.post { pick(runCatching { msm?.getActiveSessions(comp) }.getOrNull() ?: emptyList()) } }
    }

    // ---------- siklus hidup ----------
    override fun onListenerConnected() {
        inst = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ui.post {
            runCatching {
                msm = getSystemService(MediaSessionManager::class.java)
                msm?.addOnActiveSessionsChangedListener(sessions, comp, ui)
                pick(msm?.getActiveSessions(comp) ?: emptyList())
            }
            apply()
        }
    }

    override fun onListenerDisconnected() {
        inst = null
        ui.post { runCatching { msm?.removeOnActiveSessionsChangedListener(sessions) }; teardown() }
        if (Build.VERSION.SDK_INT >= 24) runCatching { requestRebind(comp) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); ui.post { apply() } }

    override fun onDestroy() { inst = null; teardown(); super.onDestroy() }

    // ---------- overlay ----------
    private fun makeLp(c: NotchPrefs.Cfg): WindowManager.LayoutParams {
        val d = resources.displayMetrics.density
        return WindowManager.LayoutParams(
            (c.width * d).toInt(), (c.height * d).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = (c.x * d).toInt(); y = (c.top * d).toInt()
            if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "TimeFlowNotch"
        }
    }

    private fun allowed(c: NotchPrefs.Cfg): Boolean {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return c.enabled && overlayGranted(this) && !(land && c.hideLandscape)
    }

    private fun apply() {
        val c = NotchPrefs.get(this)
        if (!allowed(c)) { teardown(); return }
        if (view == null) view = NotchView(this, this)
        val d = resources.displayMetrics.density
        lp?.let { it.x = (c.x * d).toInt(); it.y = (c.top * d).toInt() }
        view?.applyConfig()
    }

    private fun teardown() {
        runCatching { if (attached) wm.removeViewImmediate(view) }
        attached = false
        view?.release(); view = null; lp = null
    }

    // ---------- NotchHost ----------
    override fun cfg(): NotchPrefs.Cfg = NotchPrefs.get(this)

    override fun resize(w: Int, h: Int) {
        val l = lp ?: return
        l.width = w; l.height = h
        if (attached) runCatching { wm.updateViewLayout(view, l) }
    }

    override fun visible(show: Boolean) {
        val v = view ?: return
        if (show && !attached) {
            val c = cfg()
            val l = lp ?: makeLp(c).also { lp = it }
            runCatching { wm.addView(v, l); attached = true }
        } else if (!show && attached) {
            runCatching { wm.removeViewImmediate(v) }; attached = false
        }
    }

    override fun transport(cmd: Int) {
        if (demoOn) return
        val t = ctl?.transportControls ?: return
        when (cmd) {
            0 -> t.skipToPrevious()
            2 -> t.skipToNext()
            else -> if (ctl?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
        }
    }

    override fun seek(frac: Float) {
        val dur = ctl?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (dur > 0) ctl?.transportControls?.seekTo((dur * frac).toLong())
    }

    override fun openMedia() { runCatching { ctl?.sessionActivity?.send() } }

    // ---------- media ----------
    private fun pick(list: List<MediaController>) {
        if (demoOn) return
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.packageName == ctl?.packageName }
            ?: list.firstOrNull { it.metadata != null }
        if (best?.sessionToken == ctl?.sessionToken) { pushMedia(); return }
        runCatching { ctl?.unregisterCallback(cb) }
        ctl = best
        runCatching { best?.registerCallback(cb, ui) }
        pushMedia()
    }

    private fun pushMedia() {
        if (demoOn) return
        val c = ctl
        val v = view
        if (c == null) { v?.setMedia(null); return }
        val md = c.metadata
        val st = c.playbackState
        if (md == null || st == null || st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_STOPPED) { v?.setMedia(null); return }
        val art: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        v?.setMedia(MediaInfo(title, artist, art, st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING,
            st.position, md.getLong(MediaMetadata.METADATA_KEY_DURATION), st.playbackSpeed, st.lastPositionUpdateTime))
    }

    // ---------- notifikasi ----------
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val c = NotchPrefs.get(this)
        if (!c.enabled || !c.showNotif) return
        val n = sbn.notification ?: return
        val skip = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
        if ((n.flags and skip) != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val sig = "$title|$text"
        if (seen[sbn.key] == sig) return
        if (seen.size > 80) seen.clear()
        seen[sbn.key] = sig
        val pkg = sbn.packageName
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val icon = runCatching {
            val pc = createPackageContext(pkg, 0)
            (n.getLargeIcon() ?: n.smallIcon)?.loadDrawable(pc)
        }.getOrNull()
        val pi = n.contentIntent
        val open: (() -> Unit)? = if (pi != null) ({ runCatching { pi.send() }; Unit }) else null
        val info = NotifInfo(app, title.ifEmpty { app }, if (title.isEmpty()) "" else text, icon, n.color, open)
        ui.post {
            if (!allowed(NotchPrefs.get(this))) return@post
            if (view == null) apply()
            view?.showNotif(info)
        }
    }

    // ---------- uji coba ----------
    private fun demoN() {
        if (view == null) apply()
        view?.showNotif(NotifInfo("WhatsApp", "Budi", "Bro, jadi nongkrong malam ini?", null, 0xFF25D366.toInt(), null))
    }

    private fun demoM() {
        if (view == null) apply()
        demoOn = true
        view?.setMedia(MediaInfo("Entropy", "Beach Bunny", null, true, 50_000L, 141_000L, 1f, android.os.SystemClock.elapsedRealtime()))
        ui.postDelayed({ demoOn = false; pushMedia() }, 12_000)
    }
}
