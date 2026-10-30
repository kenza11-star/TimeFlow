package app.timeflow

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.app.RemoteInput
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Inti fitur notch. Layanan ini di-bind sistem selama "Akses notifikasi" aktif, jadi tetap hidup walau aplikasi ditutup.
 * Ia membaca semua notifikasi, memantau sesi media (Spotify, YouTube Music, dll.) dan menggambar overlay NotchView.
 */
class NotchListener : NotificationListenerService(), NotchHost, CcHost {
    companion object {
        @Volatile private var inst: NotchListener? = null
        fun connected() = inst != null
        fun accessGranted(ctx: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        fun overlayGranted(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
        fun refresh() { inst?.let { i -> i.ui.post { i.apply() } } }
        fun demoNotif(): Boolean { val i = inst ?: return false; i.ui.post { i.demoN() }; return true }
        fun demoMedia(): Boolean { val i = inst ?: return false; i.ui.post { i.demoM() }; return true }
        fun demoAlarm(): Boolean { val i = inst ?: return false; i.ui.post { i.demoA() }; return true }
        fun demoTimer(): Boolean { val i = inst ?: return false; i.ui.post { i.demoT() }; return true }

        /** Dari ubin Quick Settings: buka panel (dengan jeda supaya bar notifikasi sempat menutup). */
        fun ccOpen(): Boolean = openCc(450)

        /** Uji dari pengaturan: false bila belum ada layanan yang siap atau fitur belum aktif. */
        fun ccTest(): Boolean = openCc(350)

        private fun openCc(delay: Long): Boolean {
            if (CcAccessibilityService.open(delay)) return true
            val i = inst ?: return false
            if (!CcPrefs.get(i).enabled || !overlayGranted(i)) return false
            i.ui.postDelayed({ i.applyCc(); i.cc?.show() }, delay)
            return true
        }

        fun ccDiag(): org.json.JSONObject {
            val a = CcAccessibilityService.diag()
            if (a != null) return a.put("mode", "a11y").put("log", CcLog.json())
            val c = inst?.cc
            return org.json.JSONObject().put("mode", "overlay").put("trigger", c?.triggerAttached() ?: false)
                .put("handle", c?.handleAttached() ?: false).put("err", c?.lastErr ?: "").put("log", CcLog.json())
        }

        // dipakai layanan aksesibilitas (yang ikut menampilkan Control Center) untuk kontrol musik
        fun hubTransport(cmd: Int) { inst?.transport(cmd) }
        fun hubMedia(): MediaInfo? = inst?.mediaNow()
        fun hubOpenMedia() { inst?.openMedia() }
        fun hubSeek(f: Float) { inst?.seek(f) }

        @Volatile private var alarmInfo: AlarmInfo? = null
        @Volatile private var timerInfo: TimerInfo? = null

        /** Dipanggil AlarmService tiap daftar alarm yang berbunyi berubah: alarm utama tampil di notch dengan tombol Tunda / Matikan. */
        fun alarmChanged(ctx: Context) {
            val ids = AlarmService.ringing
            val a = ids.firstOrNull()?.let { AlarmStore.get(ctx, it) }
            alarmInfo = if (a != null) AlarmInfo(a.id, a.label, hm(a.hour, a.minute), a.snoozeMin, ids.size - 1) else null
            val i = inst ?: return
            i.ui.post { i.pushAlarm() }
        }

        /** Dari Focus Timer di halaman web: tampilkan hitung mundur di notch. Nilai waktu dalam milidetik. */
        fun timerSync(running: Boolean, endAt: Long, leftMs: Long, totalMs: Long, clear: Boolean) {
            timerInfo = if (clear || (!running && (leftMs <= 0L || leftMs >= totalMs))) null else TimerInfo(running, endAt, leftMs)
            val i = inst ?: return
            i.ui.post { i.pushTimer() }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var view: NotchView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var attachedWm: WindowManager? = null   // WindowManager yang dipakai memasang notch (layanan aksesibilitas atau notch sendiri)
    private var attachedA11y = false
    private var cc: ControlCenter? = null
    private var msm: MediaSessionManager? = null
    private var ctl: MediaController? = null
    private val ctls = ArrayList<MediaController>()
    private val artCache = HashMap<String, Pair<String, Bitmap>>()   // paket -> (judul lagu, cover dari notifikasi)
    private var demoOn = false
    private val seen = HashMap<String, String>()
    private val labels = HashMap<String, String>()
    private val shownOngoing = HashSet<String>()
    private val repliedAt = HashMap<String, Long>()
    private val hseen = HashMap<String, String>()
    private val hOngoing = HashSet<String>()
    private val comp by lazy { ComponentName(this, NotchListener::class.java) }

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> ui.post { pick(list ?: emptyList()) } }
    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { reselect() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { reselect() }
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
        ui.post {
            runCatching { msm?.removeOnActiveSessionsChangedListener(sessions) }
            for (c in ctls) runCatching { c.unregisterCallback(cb) }
            ctls.clear(); ctl = null
            teardown()
            cc?.release(); cc = null
        }
        if (Build.VERSION.SDK_INT >= 24) runCatching { requestRebind(comp) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); ui.post { apply() } }

    override fun onDestroy() { inst = null; teardown(); cc?.release(); cc = null; super.onDestroy() }

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
        return c.enabled && (overlayGranted(this) || CcAccessibilityService.connected()) && !(land && c.hideLandscape)
    }

    /** Control Center berjalan terpisah dari notch: tetap aktif walau notch dimatikan. */
    private fun applyCc() {
        if (CcAccessibilityService.connected()) { cc?.release(); cc = null; return }   // layanan aksesibilitas yang menampilkan panel
        if (CcPrefs.get(this).enabled && overlayGranted(this)) {
            val c = cc ?: ControlCenter(this, this).also { cc = it }
            c.sync()
        } else { cc?.release(); cc = null }
    }

    private fun apply() {
        applyCc()
        // layanan aksesibilitas baru tersambung / terputus: pasang ulang notch lewat jalur yang benar
        if (attached && attachedA11y != CcAccessibilityService.connected()) { view?.let { runCatching { (attachedWm ?: wm).removeViewImmediate(it) } }; attached = false; lp = null }
        val c = NotchPrefs.get(this)
        if (!allowed(c)) { teardown(); return }
        if (view == null) { view = NotchView(this, this); view?.setTimer(timerInfo); view?.setAlarm(alarmInfo) }
        val d = resources.displayMetrics.density
        lp?.let { it.x = (c.x * d).toInt(); it.y = (c.top * d).toInt() }
        view?.applyConfig()
    }

    private fun teardown() {
        runCatching { if (attached) (attachedWm ?: wm).removeViewImmediate(view) }
        attached = false
        view?.release(); view = null; lp = null
    }

    // ---------- NotchHost ----------
    override fun cfg(): NotchPrefs.Cfg = NotchPrefs.get(this)

    override fun resize(w: Int, h: Int) {
        val l = lp ?: return
        l.width = w; l.height = h
        if (attached) runCatching { (attachedWm ?: wm).updateViewLayout(view, l) }
    }

    override fun visible(show: Boolean) {
        val v = view ?: return
        if (show && !attached) {
            val c = cfg()
            val l = lp ?: makeLp(c).also { lp = it }
            // Bila layanan aksesibilitas aktif, notch dipasang lewat sana (tidak butuh izin "tampil di atas aplikasi lain",
            // jadi notifikasi sistem "TimeFlow ditampilkan di atas aplikasi lain" tidak muncul).
            val aw = CcAccessibilityService.winMgr()
            l.type = if (aw != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val target = aw ?: wm
            runCatching { target.addView(v, l); attached = true; attachedWm = target; attachedA11y = aw != null }
        } else if (!show && attached) {
            runCatching { (attachedWm ?: wm).removeViewImmediate(v) }; attached = false
        }
    }

    override fun focusable(on: Boolean) {
        val l = lp ?: return
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val wo = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (on) {
            l.flags = (l.flags and nf.inv()) or wo
            l.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            l.flags = (l.flags or nf) and wo.inv()
            l.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
        }
        if (attached) runCatching { (attachedWm ?: wm).updateViewLayout(view, l) }
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

    override fun openApp() {
        runCatching { packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    override fun alarmSnooze(id: String) {
        if (id == "demo") { alarmInfo = null; pushAlarm(); return }
        runCatching { AlarmService.snooze(this, id) }
    }

    override fun alarmStop(id: String) {
        if (id == "demo") { alarmInfo = null; pushAlarm(); return }
        runCatching { AlarmService.stop(this, id) }
    }

    private fun pushAlarm() {
        val a = alarmInfo
        if (a != null && view == null && allowed(NotchPrefs.get(this))) apply()
        view?.setAlarm(a)
    }

    private fun pushTimer() {
        val t = timerInfo
        if (t != null && view == null && allowed(NotchPrefs.get(this))) apply()
        view?.setTimer(t)
    }

    // ---------- media ----------
    /** Dipanggil saat daftar sesi media berubah: pasang callback ke SEMUA sesi (bukan hanya satu), lalu pilih ulang. */
    private fun pick(list: List<MediaController>) {
        if (demoOn) return
        ctls.removeAll { c ->
            val gone = list.none { it.sessionToken == c.sessionToken }
            if (gone) runCatching { c.unregisterCallback(cb) }
            gone
        }
        for (c in list) {
            if (ctls.none { it.sessionToken == c.sessionToken }) {
                ctls.add(c)
                runCatching { c.registerCallback(cb, ui) }
            }
        }
        reselect()
    }

    /**
     * Pilih sesi yang ditampilkan: yang sedang memutar menang atas yang jeda; di antara yang memutar, yang paling baru berubah;
     * di antara yang jeda, tetap pakai sesi sekarang supaya tidak berpindah-pindah.
     */
    private fun reselect() {
        if (demoOn) return
        var best: MediaController? = null
        var bestScore = Long.MIN_VALUE
        for (c in ctls) {
            val st = c.playbackState ?: continue
            if (c.metadata == null || st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_STOPPED) continue
            val playing = st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING
            var score = st.lastPositionUpdateTime
            if (playing) score += 4_000_000_000_000_000L
            else if (c.sessionToken == ctl?.sessionToken) score += 2_000_000_000_000_000L
            if (score > bestScore) { bestScore = score; best = c }
        }
        ctl = best
        pushMedia()
    }

    private fun bmp(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = d.intrinsicWidth.coerceIn(1, 256); val h = d.intrinsicHeight.coerceIn(1, 256)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); d.setBounds(0, 0, w, h); d.draw(cv)
        return b
    }

    override fun mediaNow(): MediaInfo? = if (demoOn) null else buildMedia()

    private fun pushMedia() {
        if (demoOn) return
        val m = buildMedia()
        view?.setMedia(m)
        cc?.setMedia(m)
        CcAccessibilityService.setMedia(m)
    }

    private fun buildMedia(): MediaInfo? {
        val c = ctl ?: return null
        val md = c.metadata
        val st = c.playbackState
        if (md == null || st == null || st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_STOPPED) return null
        val art: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val cover = art ?: artCache[c.packageName]?.let { if (it.first == title) it.second else null }
        return MediaInfo(title, artist, cover, st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING,
            st.position, md.getLong(MediaMetadata.METADATA_KEY_DURATION), st.playbackSpeed, st.lastPositionUpdateTime)
    }

    // ---------- notifikasi ----------
    /** Membuat fungsi pengirim balasan: mengisi RemoteInput lalu memicu PendingIntent aksi "Balas" milik aplikasi asal. */
    private fun makeReply(rAct: Notification.Action, key: String): (String) -> Boolean = { txt ->
        val ok = runCatching {
            val inputs = rAct.remoteInputs
            val results = Bundle()
            for (r in inputs) if (r.allowFreeFormInput) results.putCharSequence(r.resultKey, txt)
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            rAct.actionIntent.send(this@NotchListener, 0, intent)
            true
        }.getOrDefault(false)
        if (ok) repliedAt[key] = SystemClock.elapsedRealtime()
        ok
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        seen.remove(sbn.key); shownOngoing.remove(sbn.key); hseen.remove(sbn.key); hOngoing.remove(sbn.key)
        ui.post { view?.removeNotif(sbn.key) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        if (pkg == packageName) return
        // Ringkasan grup dan notifikasi musik dilewati (musik sudah tampil sebagai kontrol media). Selain itu semua masuk ke notch.
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            // Spotify dll. sering mengirim cover hanya lewat notifikasi: simpan sebagai cadangan, tampilkan bila judulnya cocok
            runCatching {
                val li = n.getLargeIcon()
                val tt = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val dr = li?.loadDrawable(this)
                if (dr != null && tt.isNotEmpty()) {
                    if (artCache.size > 12) artCache.clear()
                    artCache[pkg] = Pair(tt, bmp(dr))
                    if (ctl?.packageName == pkg) ui.post { pushMedia() }
                }
            }
            return
        }
        val key = sbn.key
        val last = repliedAt[key]
        if (last != null && SystemClock.elapsedRealtime() - last < 6000L) return   // jangan tampilkan balasan kita sendiri

        val ex = n.extras
        var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        // Chat (WhatsApp, Telegram, dll.): ambil pesan terakhir beserta nama pengirim/grup
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val msg = style?.messages?.lastOrNull()
        if (style != null && msg != null) {
            val who = msg.person
            val me = style.user.key
            if (who != null && me != null && who.key == me) return
            val convo = style.conversationTitle?.toString()
            val sender = who?.name?.toString()
            val body = msg.text?.toString().orEmpty()
            if (body.isNotEmpty()) text = if (!convo.isNullOrEmpty() && !sender.isNullOrEmpty()) "$sender: $body" else body
            if (!convo.isNullOrEmpty()) title = convo else if (title.isEmpty() && !sender.isNullOrEmpty()) title = sender
        }
        if (title.isEmpty() && text.isEmpty()) return
        val app = labels.getOrPut(pkg) {
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        // Simpan ke riwayat (terpisah dari pengaturan notch). Notifikasi menetap (unduhan, layanan) dicatat sekali saja.
        if (NotifHistory.enabled(this)) {
            val hsig = "$title|$text"
            val hPersist = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
            if (hseen[key] != hsig && !(hPersist && hOngoing.contains(key))) {
                if (hseen.size > 120) hseen.clear()
                hseen[key] = hsig
                if (hPersist) { if (hOngoing.size > 120) hOngoing.clear(); hOngoing.add(key) }
                val at = if (sbn.postTime > 0L) sbn.postTime else System.currentTimeMillis()
                runCatching { NotifHistory.add(this, pkg, app, title.ifEmpty { app }, text, at) }
            }
        }
        val c = NotchPrefs.get(this)
        if (!c.enabled || !c.showNotif) return
        val sig = "$title|$text"
        if (seen[key] == sig) return
        if (seen.size > 80) seen.clear()
        seen[key] = sig
        // Notifikasi menetap (unduhan, layanan latar) hanya tampil sekali per kunci supaya tidak membanjiri notch
        val persistent = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
        if (persistent) {
            if (shownOngoing.contains(key)) return
            if (shownOngoing.size > 80) shownOngoing.clear()
            shownOngoing.add(key)
        }

        val icon = runCatching {
            val pc = createPackageContext(pkg, 0)
            (n.getLargeIcon() ?: n.smallIcon)?.loadDrawable(pc)
        }.getOrNull()
        val pi = n.contentIntent
        val open: (() -> Unit)? = if (pi != null) ({ runCatching { pi.send() }; Unit }) else null

        // Aksi balas (RemoteInput) dan aksi tombol lain dari notifikasi
        val acts: Array<Notification.Action> = n.actions ?: emptyArray()
        var found: Notification.Action? = null
        for (a in acts) {
            val ri = a.remoteInputs ?: continue
            if (a.actionIntent != null && ri.any { it.allowFreeFormInput }) { found = a; break }
        }
        val rAct = found
        val reply: ((String) -> Boolean)? = if (rAct != null) makeReply(rAct, key) else null
        val hint = rAct?.remoteInputs?.firstOrNull { it.allowFreeFormInput }?.label?.toString().orEmpty()
        val extra = ArrayList<NotifAct>()
        for (a in acts) {
            if (a === rAct || a.remoteInputs != null || a.actionIntent == null) continue
            val label = a.title?.toString().orEmpty()
            if (label.isEmpty()) continue
            val ai = a.actionIntent
            extra.add(NotifAct(label) { runCatching { ai.send() } })
            if (extra.size >= 2) break
        }

        val info = NotifInfo(key, app, title.ifEmpty { app }, if (title.isEmpty()) "" else text, icon, n.color, open, reply, hint, extra)
        ui.post {
            if (!allowed(NotchPrefs.get(this))) return@post
            if (view == null) apply()
            view?.showNotif(info)
        }
    }

    // ---------- uji coba ----------
    private fun demoA() {
        alarmInfo = AlarmInfo("demo", "Uji alarm", "06:30", 10, 0)
        pushAlarm()
        ui.postDelayed({ if (alarmInfo?.id == "demo") { alarmInfo = null; pushAlarm() } }, 12_000)
    }

    private fun demoT() {
        timerInfo = TimerInfo(true, System.currentTimeMillis() + 25_000L, 0L)
        pushTimer()
    }

    private fun demoN() {
        if (view == null) apply()
        view?.showNotif(NotifInfo("demo", "WhatsApp", "Budi", "Bro, jadi nongkrong malam ini?", null, 0xFF25D366.toInt(), null,
            reply = { true }, replyHint = "Balas Budi…", actions = listOf(NotifAct("Tandai dibaca") {})))
    }

    private fun demoM() {
        if (view == null) apply()
        demoOn = true
        view?.setMedia(MediaInfo("Entropy", "Beach Bunny", null, true, 50_000L, 141_000L, 1f, android.os.SystemClock.elapsedRealtime()))
        ui.postDelayed({ demoOn = false; pushMedia() }, 12_000)
    }
}
