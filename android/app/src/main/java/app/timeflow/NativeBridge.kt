package app.timeflow

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.webkit.JavaScriptReplyProxy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Jembatan WebView <-> native. Dipasang lewat WebViewCompat.addWebMessageListener dengan aturan origin,
 * jadi hanya https://appassets.androidplatform.net (bundel aplikasi) yang dapat memanggilnya.
 * Protokol: {id, cmd, ...args} -> {id, ok, data|error}; event native -> {event}.
 */
class NativeBridge(private val act: MainActivity) {
    private val ctx: Context = act.applicationContext
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var reply: JavaScriptReplyProxy? = null
    private val pending = HashMap<String, Int>()
    private var backupJson: String? = null
    private val preview by lazy { SoundPlayer(ctx) }

    // ---------- pengiriman ke web ----------
    private fun send(o: JSONObject) {
        val s = o.toString()
        ui.post { try { reply?.postMessage(s) } catch (_: Exception) { } }
    }
    private fun ok(id: Int, data: Any?) = send(JSONObject().put("id", id).put("ok", true).put("data", data ?: JSONObject.NULL))
    private fun fail(id: Int, msg: String) = send(JSONObject().put("id", id).put("ok", false).put("error", msg))
    fun event(name: String) = send(JSONObject().put("event", name))

    // ---------- penerimaan dari web ----------
    fun handle(raw: String, proxy: JavaScriptReplyProxy) {
        reply = proxy
        if (raw.length > 14_000_000) return
        val o = try { JSONObject(raw) } catch (_: Exception) { return }
        val id = o.optInt("id", -1)
        if (id < 0) return
        try {
            dispatch(id, o.optString("cmd"), o)
        } catch (e: IllegalArgumentException) {
            fail(id, e.message ?: "Permintaan tidak valid")
        } catch (e: Exception) {
            fail(id, "Kesalahan native: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun owner(o: JSONObject): String {
        val s = o.optString("owner")
        require(Alarm.OWNER_RE.matches(s)) { "Nama profil tidak valid" }
        return s
    }

    private fun alarmsJson(owner: String): JSONArray {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        AlarmStore.forOwner(ctx, owner).sortedBy { it.hour * 60 + it.minute }
            .forEach { arr.put(it.toJson(if (it.enabled) it.nextTrigger(now) ?: 0L else 0L)) }
        return arr
    }

    private fun checkNotForeign(id: String, owner: String) {
        require(AlarmStore.all(ctx).none { it.id == id && it.owner != owner }) { "ID alarm bentrok dengan profil lain" }
    }

    private fun async(id: Int, block: () -> Any?) = io.execute {
        try { ok(id, block()) }
        catch (e: IllegalArgumentException) { fail(id, e.message ?: "Permintaan tidak valid") }
        catch (e: Exception) { fail(id, "Kesalahan native: " + (e.message ?: e.javaClass.simpleName)) }
    }

    private fun dispatch(id: Int, cmd: String, o: JSONObject) {
        when (cmd) {
            "hello" -> ok(id, JSONObject()
                .put("native", true)
                .put("version", ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("hasVibrator", Vibe.available(ctx))
                .put("alarmVolume", Prefs.volume(ctx)))

            // ----- alarm -----
            "alarms.list" -> ok(id, alarmsJson(owner(o)))
            "alarm.upsert" -> {
                val ow = owner(o)
                val a = Alarm.parse(o.getJSONObject("alarm"), ow)
                checkNotForeign(a.id, ow)
                AlarmScheduler.schedule(ctx, AlarmStore.upsert(ctx, a))
                ok(id, alarmsJson(ow))
            }
            "alarm.delete" -> {
                val ow = owner(o)
                val aid = o.optString("ref")
                val a = AlarmStore.all(ctx).firstOrNull { it.id == aid && it.owner == ow }
                if (a != null) {
                    runCatching { AlarmScheduler.cancel(ctx, aid) }
                    AlarmStore.delete(ctx, aid)
                    if (aid in AlarmService.ringing) runCatching { AlarmService.stop(ctx, aid) }
                }
                ok(id, alarmsJson(ow))
            }
            "alarm.setEnabled" -> {
                val ow = owner(o)
                val aid = o.optString("ref")
                val en = o.optBoolean("enabled")
                require(AlarmStore.all(ctx).any { it.id == aid && it.owner == ow }) { "Alarm tidak ditemukan" }
                val u = AlarmStore.update(ctx, aid) { it.copy(enabled = en, snoozeAt = 0L, lastFired = if (en) 0L else it.lastFired) }
                    ?: throw IllegalArgumentException("Alarm tidak ditemukan")
                AlarmScheduler.schedule(ctx, u)
                ok(id, alarmsJson(ow))
            }
            "alarms.replace" -> {
                val ow = owner(o)
                val arr = o.getJSONArray("alarms")
                require(arr.length() <= 200) { "Terlalu banyak alarm" }
                val items = (0 until arr.length()).map { Alarm.parse(arr.getJSONObject(it), ow) }
                require(items.map { it.id }.toSet().size == items.size) { "ID alarm ganda" }
                items.forEach { checkNotForeign(it.id, ow) }
                AlarmStore.replaceOwner(ctx, ow, items).forEach { AlarmScheduler.cancel(ctx, it.id) }
                items.forEach { AlarmScheduler.schedule(ctx, it) }
                ok(id, alarmsJson(ow))
            }
            "alarm.test" -> {
                val at = System.currentTimeMillis() + 10_000L
                AlarmScheduler.scheduleTest(ctx, at)
                ok(id, JSONObject().put("at", at))
            }

            // ----- suara -----
            "sounds.list" -> ok(id, JSONArray().also { a -> SoundLibrary.list(ctx).forEach { a.put(it.toJson()) } })
            "sounds.pick" -> { pending["sound"] = id; act.launchSound() }
            "sounds.delete" -> {
                val sid = o.optString("ref")
                require(sid.startsWith("custom:")) { "Nada bawaan tidak dapat dihapus" }
                SoundLibrary.delete(ctx, sid)
                ok(id, JSONArray().also { a -> SoundLibrary.list(ctx).forEach { a.put(it.toJson()) } })
            }
            "sounds.preview" -> { preview.stop(); preview.play(o.optString("ref", SoundLibrary.DEFAULT_ID), loop = false); ok(id, true) }
            "sounds.stopPreview" -> { preview.stop(); ok(id, true) }
            "settings.setVolume" -> { Prefs.setVolume(ctx, o.optInt("value", 80)); ok(id, Prefs.volume(ctx)) }

            // ----- izin & status -----
            "status.get" -> ok(id, Notifs.status(ctx))
            "perm.request" -> permRequest(id, o.optString("what"))

            // ----- foto profil -----
            "profile.info" -> ok(id, JSONObject().put("url", ProfilePhoto.url(ctx, owner(o)) ?: JSONObject.NULL))
            "profile.pick" -> { pending["photo"] = id; act.launchPhoto() }
            "profile.save" -> {
                val ow = owner(o); val img = o.optString("image")
                async(id) { JSONObject().put("url", ProfilePhoto.saveFromDataUrl(ctx, ow, img)) }
            }
            "profile.delete" -> { ProfilePhoto.delete(ctx, owner(o)); ok(id, JSONObject().put("url", JSONObject.NULL)) }

            // ----- backup -----
            "backup.save" -> {
                val json = o.optString("json")
                require(json.isNotEmpty() && json.length <= 12_000_000) { "Data backup tidak valid" }
                backupJson = json
                pending["bsave"] = id
                act.launchBackupSave(o.optString("name", "timeflow-backup.json").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80))
            }
            "backup.pick" -> { pending["bpick"] = id; act.launchBackupPick() }

            // ----- notch -----
            "notch.get" -> ok(id, notchState())
            "notch.set" -> { NotchPrefs.set(ctx, o.getJSONObject("cfg")); NotchListener.refresh(); ok(id, notchState()) }
            "notch.reset" -> { NotchPrefs.reset(ctx); NotchListener.refresh(); ok(id, notchState()) }
            "notch.test" -> ok(id, JSONObject().put("sent", when (o.optString("what")) {
                "media" -> NotchListener.demoMedia()
                "alarm" -> NotchListener.demoAlarm()
                "timer" -> NotchListener.demoTimer()
                else -> NotchListener.demoNotif()
            }))
            "notch.perm" -> when (o.optString("what")) {
                "overlay" -> open(id, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                "access" -> open(id, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                else -> fail(id, "Izin tidak dikenal")
            }

            // ----- control center -----
            "cc.get" -> ok(id, ccState())
            "cc.set" -> { CcPrefs.set(ctx, o.getJSONObject("cfg")); NotchListener.refresh(); CcAccessibilityService.refresh(); ok(id, ccState()) }
            "cc.reset" -> { CcPrefs.reset(ctx); NotchListener.refresh(); CcAccessibilityService.refresh(); ok(id, ccState()) }
            "cc.test" -> ok(id, JSONObject().put("sent", NotchListener.ccTest()))
            "cc.perm" -> when (o.optString("what")) {
                "write" -> open(id, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                "dnd" -> open(id, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                "a11y" -> open(id, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                else -> fail(id, "Izin tidak dikenal")
            }

            // ----- layar kunci -----
            "lock.get" -> ok(id, lockState())
            "lock.set" -> { LockPrefs.set(ctx, o.getJSONObject("cfg")); LockScreen.register(ctx); ok(id, lockState()) }
            "lock.wall" -> async(id) { if (!LockPrefs.saveWall(ctx, o.optString("data"))) throw IllegalStateException("Gambar tidak bisa disimpan"); lockState() }
            "lock.wallclear" -> { LockPrefs.clearWall(ctx); ok(id, lockState()) }
            "lock.test" -> { LockScreen.register(ctx); LockScreen.launch(ctx); ok(id, JSONObject().put("sent", true)) }
            "lock.perm" -> when (o.optString("what")) {
                "fsi" -> open(id, if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))
                                  else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                "overlay" -> open(id, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                "miui" -> {
                    val mi = Intent("miui.intent.action.APP_PERM_EDITOR").setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", ctx.packageName)
                    open(id, if (mi.resolveActivity(ctx.packageManager) != null) mi else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                }
                else -> fail(id, "Izin tidak dikenal")
            }

            // ----- riwayat notifikasi -----
            "history.get" -> async(id) { NotifHistory.settings(ctx).put("access", NotchListener.accessGranted(ctx)) }
            "history.set" -> async(id) { NotifHistory.set(ctx, o.getJSONObject("cfg")); NotifHistory.settings(ctx).put("access", NotchListener.accessGranted(ctx)) }
            "history.list" -> async(id) {
                NotifHistory.list(ctx, o.optString("q"), o.optString("pkg"), o.optLong("since"), o.optLong("before"), o.optInt("limit", 60).coerceIn(10, 200))
            }
            "history.apps" -> async(id) {
                val arr = NotifHistory.apps(ctx)
                for (i in 0 until arr.length()) { val a = arr.getJSONObject(i); a.put("icon", appIcon(a.getString("pkg"))) }
                arr
            }
            "history.delete" -> async(id) { NotifHistory.delete(ctx, o.optLong("hid", -1L)); true }
            "history.clear" -> async(id) { NotifHistory.clear(ctx); true }
            "history.openApp" -> {
                val li = ctx.packageManager.getLaunchIntentForPackage(o.optString("pkg"))
                if (li == null) fail(id, "Aplikasi ini tidak bisa dibuka (mungkin sudah dicopot)")
                else { li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(li); ok(id, true) }
            }
            "history.copy" -> {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("TimeFlow", o.optString("text").take(8000)))
                ok(id, true)
            }

            // ----- sinkron Focus Timer ke notch -----
            "focus.sync" -> {
                NotchListener.timerSync(o.optBoolean("running"), o.optLong("end"), o.optLong("left") * 1000L, o.optLong("total") * 1000L, o.optBoolean("clear"))
                ok(id, true)
            }

            // ----- cuaca -----
            "weather.get" -> async(id) { WeatherRepo.snapshot(ctx) }
            "weather.refresh" -> async(id) { refreshWeather() }
            "weather.search" -> async(id) { WeatherRepo.search(o.optString("q")) }
            "weather.setCity" -> {
                val lat = o.optDouble("lat"); val lon = o.optDouble("lon")
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Koordinat kota tidak valid" }
                val nm = o.optString("name").trim().ifBlank { "Lokasi" }
                async(id) {
                    WeatherRepo.setLocation(ctx, lat, lon, nm, "city")
                    refreshWeather()
                }
            }
            "weather.useGps" -> {
                pending["loc"] = id
                if (WeatherRepo.hasPermission(ctx)) onLocResult(true) else act.launchLocationPermission()
            }

            // ----- widget layar utama -----
            "widget.sync" -> {
                val clear = o.optBoolean("clear")
                val data = if (clear) null else o.getJSONObject("data")
                require(data == null || data.toString().length < 120_000) { "Data widget terlalu besar" }
                async(id) { WidgetUtil.saveSync(ctx, data); WidgetUtil.updateAll(ctx); true }
            }
            "widget.pin" -> ok(id, JSONObject().put("pinned", WidgetUtil.pin(ctx, o.optString("what"))))

            "ui.theme" -> { act.setBars(o.optBoolean("dark")); ok(id, true) }
            else -> fail(id, "Perintah tidak dikenal")
        }
    }

    /** Perbarui koordinat (bila mode GPS), ambil cuaca, perbarui widget, lalu kembalikan data tersimpan. */
    private fun refreshWeather(): JSONObject {
        if (WeatherRepo.mode(ctx) == "gps") WeatherRepo.updateFromDevice(ctx)   // gagal -> pakai koordinat terakhir
        require(WeatherRepo.hasLocation(ctx)) { "Lokasi belum diatur" }
        WeatherRepo.fetch(ctx)
        WidgetUtil.updateAll(ctx)
        return WeatherRepo.snapshot(ctx)
    }

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Ikon aplikasi (PNG 56px sebagai data URL) untuk daftar riwayat; kosong bila aplikasinya sudah dicopot. */
    private fun appIcon(pkg: String): String = iconCache.getOrPut(pkg) {
        runCatching {
            val dr = ctx.packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888)
            dr.setBounds(0, 0, 56, 56)
            dr.draw(Canvas(bmp))
            val bo = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bo)
            "data:image/png;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP)
        }.getOrDefault("")
    }

    private fun notchState(): JSONObject = JSONObject().put("cfg", NotchPrefs.toJson(ctx)).put("st", JSONObject()
        .put("overlay", NotchListener.overlayGranted(ctx)).put("access", NotchListener.accessGranted(ctx)).put("running", NotchListener.connected())
        .put("sdk", Build.VERSION.SDK_INT))

    private fun ccState(): JSONObject = JSONObject().put("cfg", CcPrefs.toJson(ctx)).put("st", JSONObject()
        .put("overlay", NotchListener.overlayGranted(ctx)).put("access", NotchListener.accessGranted(ctx)).put("running", NotchListener.connected())
        .put("write", CcSys.writeOk(ctx)).put("dnd", CcSys.dndAccess(ctx)).put("sdk", Build.VERSION.SDK_INT)
        .put("a11y", CcAccessibilityService.connected()).put("diag", NotchListener.ccDiag()))

    private fun permRequest(id: Int, what: String) {
        when (what) {
            "notifications" -> {
                val n = Notifs.status(ctx).getJSONObject("notif")
                when {
                    n.getBoolean("runtime") && !n.getBoolean("granted") && Prefs.notifAsked(ctx) < 2 -> {
                        Prefs.bumpNotifAsked(ctx); pending["notif"] = id; act.launchNotifPermission()
                    }
                    n.getBoolean("granted") && !n.getBoolean("enabled") ->
                        open(id, Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, Notifs.CH_ALARM))
                    n.getBoolean("granted") -> ok(id, JSONObject().put("granted", true))
                    else -> open(id, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
            }
            "exact" -> if (Build.VERSION.SDK_INT >= 31) open(id, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                       else ok(id, JSONObject().put("granted", true))
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) open(id, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)))
                            else ok(id, JSONObject().put("granted", true))
            "battery" -> open(id, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            "sound" -> open(id, Intent(Settings.ACTION_SOUND_SETTINGS))
            "app" -> open(id, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            else -> fail(id, "Izin tidak dikenal")
        }
    }

    private fun lockState(): JSONObject {
        val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val st = JSONObject()
            .put("secure", km?.isDeviceSecure ?: false)
            .put("fsi", if (Build.VERSION.SDK_INT >= 34) runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true) else true)
            .put("overlay", Settings.canDrawOverlays(ctx))
            .put("xiaomi", Build.MANUFACTURER.equals("xiaomi", true) || Build.BRAND.equals("poco", true) || Build.BRAND.equals("redmi", true))
        return JSONObject().put("cfg", LockPrefs.toJson(ctx)).put("st", st)
    }

    private fun open(id: Int, intent: Intent) {
        try { act.startActivity(intent) }
        catch (_: ActivityNotFoundException) {
            runCatching { act.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
        }
        ok(id, JSONObject().put("opened", true))
    }

    // ---------- hasil picker (dipanggil MainActivity) ----------
    fun onNotifResult(granted: Boolean) { pending.remove("notif")?.let { ok(it, JSONObject().put("granted", granted)) } }

    fun onLocResult(granted: Boolean) {
        val id = pending.remove("loc") ?: return
        if (!granted) { fail(id, "Izin lokasi ditolak. Kamu tetap bisa memilih kota secara manual."); return }
        async(id) {
            require(WeatherRepo.updateFromDevice(ctx)) {
                "Lokasi perangkat belum tersedia. Nyalakan Lokasi di pengaturan HP, atau pilih kota secara manual."
            }
            refreshWeather()
        }
    }

    fun onPhoto(uri: Uri?) {
        val id = pending.remove("photo") ?: return
        if (uri == null) { ok(id, JSONObject().put("cancelled", true)); return }
        async(id) { JSONObject().put("image", ProfilePhoto.decodeForCrop(ctx, uri)) }
    }

    fun onSound(uri: Uri?) {
        val id = pending.remove("sound") ?: return
        if (uri == null) { ok(id, JSONObject().put("cancelled", true)); return }
        async(id) {
            val s = SoundLibrary.import(ctx, uri)
            JSONObject().put("added", s.id).put("sounds", JSONArray().also { a -> SoundLibrary.list(ctx).forEach { a.put(it.toJson()) } })
        }
    }

    fun onBackupPicked(uri: Uri?) {
        val id = pending.remove("bpick") ?: return
        if (uri == null) { ok(id, JSONObject().put("cancelled", true)); return }
        async(id) {
            val max = 12_000_000
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    require(out.size() + n <= max) { "File backup terlalu besar" }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            JSONObject().put("text", String(bytes, Charsets.UTF_8))
        }
    }

    fun onBackupSaved(uri: Uri?) {
        val id = pending.remove("bsave") ?: return
        val json = backupJson
        backupJson = null
        if (uri == null || json == null) { ok(id, JSONObject().put("saved", false)); return }
        async(id) {
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            JSONObject().put("saved", true)
        }
    }

    fun shutdown() { preview.stop(); io.shutdown() }
}
