package app.timeflow

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Pengaturan Control Center (SharedPreferences, dibaca native tanpa membuka UI). */
object CcPrefs {
    /** Kontrol cepat yang bisa dipilih untuk baris bawah (urutan di sini = urutan tampil). */
    val ALL: List<String> = CcCatalog.ALL.map { it.id }
    private val DEFAULT = ALL      // semua kontrol aktif secara bawaan
    private const val KEY = "tiles2"

    data class Cfg(val enabled: Boolean, val zone: Int, val haptic: Boolean, val handle: Boolean, val tiles: List<String>)

    private fun sp(c: Context) = c.getSharedPreferences("controlcenter", Context.MODE_PRIVATE)

    fun get(c: Context): Cfg = sp(c).let {
        val raw = it.getString(KEY, null)
        val chosen = if (raw == null) DEFAULT else raw.split(",").filter { s -> s.isNotEmpty() }
        Cfg(
            enabled = it.getBoolean("enabled", false),
            zone = it.getInt("zone", 35),
            haptic = it.getBoolean("haptic", true),
            handle = it.getBoolean("handle", false),
            tiles = ALL.filter { t -> t in chosen },
        )
    }

    fun toJson(c: Context): JSONObject = get(c).let {
        JSONObject().put("enabled", it.enabled).put("zone", it.zone).put("haptic", it.haptic).put("handle", it.handle).put("tiles", JSONArray(it.tiles))
    }

    /** Terima sebagian/seluruh kunci; nilai dibatasi ke rentang aman. */
    fun set(c: Context, o: JSONObject) {
        val e = sp(c).edit()
        if (o.has("enabled")) e.putBoolean("enabled", o.optBoolean("enabled"))
        if (o.has("haptic")) e.putBoolean("haptic", o.optBoolean("haptic"))
        if (o.has("handle")) e.putBoolean("handle", o.optBoolean("handle"))
        if (o.has("zone")) e.putInt("zone", o.optInt("zone").coerceIn(20, 60))
        val a = o.optJSONArray("tiles")
        if (a != null) e.putString(KEY,(0 until a.length()).map { a.optString(it) }.filter { it in ALL }.joinToString(","))
        e.apply()
    }

    fun reset(c: Context) {
        val on = get(c).enabled
        sp(c).edit().clear().putBoolean("enabled", on).apply()
    }
}

/** Yang dibutuhkan panel dari layanan notch: kontrol musik dan membuka aplikasi. */
interface CcHost {
    fun transport(cmd: Int)          // 0 = sebelumnya, 1 = putar/jeda, 2 = berikutnya
    fun openMedia()
    fun seek(frac: Float)
    fun openApp()
    fun mediaNow(): MediaInfo?
    /** Menekan tile bawaan sistem lewat layanan aksesibilitas (satu-satunya cara aplikasi biasa menyalakan Wi-Fi, Bluetooth, dll.). false = tidak tersedia, onFail dipanggil bila tile tidak ditemukan. */
    fun pressTile(labels: List<String>, onFail: () -> Unit): Boolean = false
    /** Tangkapan layar sebelum panel muncul (untuk latar blur). null bila tidak tersedia. */
    fun screenshot(cb: (Bitmap?) -> Unit) { cb(null) }
}

/** Membaca dan mengubah status sistem. Semua pemanggilan dibungkus supaya HP tertentu yang menolak tidak membuat layanan crash. */
object CcSys {
    private fun cr(c: Context) = c.contentResolver

    fun airplane(c: Context): Boolean = runCatching { Settings.Global.getInt(cr(c), Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }.getOrDefault(false)

    fun wifi(c: Context): Boolean = runCatching {
        (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
    }.getOrDefault(false)

    fun bluetooth(c: Context): Boolean = runCatching {
        c.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(false)

    fun data(c: Context): Boolean = !airplane(c) && runCatching { Settings.Global.getInt(cr(c), "mobile_data", 1) == 1 }.getOrDefault(false)

    fun rotate(c: Context): Boolean = runCatching { Settings.System.getInt(cr(c), Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }.getOrDefault(false)

    fun writeOk(c: Context): Boolean = Settings.System.canWrite(c)

    /** Mengubah auto-rotate; false bila izin "Ubah pengaturan sistem" belum diberikan. */
    fun setRotate(c: Context, on: Boolean): Boolean {
        if (!writeOk(c)) return false
        return runCatching { Settings.System.putInt(cr(c), Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0) }.getOrDefault(false)
    }

    fun dnd(c: Context): Boolean = runCatching {
        val f = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }.getOrDefault(false)

    fun dndAccess(c: Context): Boolean = runCatching { c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted }.getOrDefault(false)

    fun setDnd(c: Context, on: Boolean): Boolean {
        if (!dndAccess(c)) return false
        return runCatching {
            c.getSystemService(NotificationManager::class.java).setInterruptionFilter(
                if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        }.getOrDefault(false)
    }

    fun brightness(c: Context): Float =
        runCatching { Settings.System.getInt(cr(c), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f }.getOrDefault(0.5f).coerceIn(0f, 1f)

    fun setBrightness(c: Context, f: Float): Boolean {
        if (!writeOk(c)) return false
        return runCatching {
            Settings.System.putInt(cr(c), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr(c), Settings.System.SCREEN_BRIGHTNESS, (f * 255f).roundToInt().coerceIn(1, 255))
        }.getOrDefault(false)
    }

    fun volume(c: Context): Float = runCatching {
        val am = c.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) 0f else am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }.getOrDefault(0f)

    fun setVolume(c: Context, f: Float) {
        runCatching {
            val am = c.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (f * max).roundToInt().coerceIn(0, max), 0)
        }
    }

    fun location(c: Context): Boolean = runCatching {
        val lm = c.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else Settings.Secure.getInt(cr(c), Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF
    }.getOrDefault(false)

    fun batterySaver(c: Context): Boolean = runCatching { c.getSystemService(PowerManager::class.java).isPowerSaveMode }.getOrDefault(false)

    private fun secInt(c: Context, k: String): Int = runCatching { Settings.Secure.getInt(cr(c), k, 0) }.getOrDefault(0)
    fun nightLight(c: Context) = secInt(c, "night_display_activated") == 1
    fun dim(c: Context) = secInt(c, "reduce_bright_colors_activated") == 1
    fun invert(c: Context) = secInt(c, "accessibility_display_inversion_enabled") == 1
    fun daltonizer(c: Context) = secInt(c, "accessibility_display_daltonizer_enabled") == 1
    fun bigFont(c: Context): Boolean = runCatching { Settings.System.getFloat(cr(c), Settings.System.FONT_SCALE, 1f) > 1.01f }.getOrDefault(false)
    fun dataSaver(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        cm.restrictBackgroundStatus != android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
    }.getOrDefault(false)

    fun dark(c: Context): Boolean = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/** Senter lewat CameraManager.setTorchMode (tidak butuh izin kamera). */
object Torch {
    @Volatile var on = false
    private var cam: String? = null
    private var mgr: CameraManager? = null
    private var registered = false
    private val cb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == cam) on = enabled }
        override fun onTorchModeUnavailable(cameraId: String) {}
    }

    fun init(c: Context) {
        if (registered) return
        val m = c.getSystemService(CameraManager::class.java) ?: return
        mgr = m
        cam = runCatching {
            m.cameraIdList.firstOrNull { id ->
                val ch = m.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull()
        if (cam == null) return
        runCatching { m.registerTorchCallback(cb, null); registered = true }
    }

    fun available(): Boolean = cam != null

    fun toggle(): Boolean {
        val m = mgr ?: return false
        val id = cam ?: return false
        return try { m.setTorchMode(id, !on); on = !on; true } catch (_: Exception) { false }
    }

    fun release() {
        if (registered) runCatching { mgr?.unregisterTorchCallback(cb) }
        registered = false
    }
}

/** Catatan singkat kejadian Control Center (14 terakhir), ditampilkan di Settings > Control Center untuk membantu mencari penyebab bila panel tidak muncul. */
object CcLog {
    private val lines = java.util.ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
    @Synchronized fun add(s: String) {
        lines.addLast(fmt.format(java.util.Date()) + " " + s)
        while (lines.size > 14) lines.removeFirst()
    }
    @Synchronized fun json(): JSONArray = JSONArray(lines.toList())
}
