package app.timeflow

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Menampilkan frontend TimeFlow (dibundel di assets) lewat WebViewAssetLoader pada origin HTTPS virtual.
 * Tidak ada koneksi internet yang dibutuhkan; navigasi ke luar origin diblokir.
 */
class MainActivity : ComponentActivity() {
    companion object {
        val REMOTE: String = BuildConfig.REMOTE_URL            // kosong = UI dibundel di APK
        val HOST: String = if (REMOTE.isNotEmpty()) Uri.parse(REMOTE).host ?: "" else "appassets.androidplatform.net"
        val ORIGIN: String = "https://$HOST"
        val START: String = if (REMOTE.isNotEmpty()) "$ORIGIN/" else "$ORIGIN/assets/index.html"
        private val PHOTO_RE = Regex("^[a-z0-9_]{1,20}\\.jpg$")
        /** Pintasan widget kalender ("event" = Acara Baru, "alarm" = Pengingat). Diambil sekali oleh UI web lewat perintah bridge "app.goto". */
        @Volatile var pendingGoto: String? = null
    }

    private lateinit var web: WebView
    private lateinit var bridge: NativeBridge
    private val alarmListener: () -> Unit = { if (::bridge.isInitialized) bridge.event("alarms") }

    private fun readGoto(i: Intent?) {
        if (i == null || i.action != WidgetUtil.ACTION_GOTO) return
        val g = i.getStringExtra("goto")
        if (g == "event" || g == "alarm") pendingGoto = g
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readGoto(intent)
    }

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { bridge.onPhoto(it) }
    private val soundPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { bridge.onSound(it) }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { bridge.onBackupPicked(it) }
    private val backupSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { bridge.onBackupSaved(it) }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { bridge.onNotifResult(it) }
    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { bridge.onLocResult(it) }

    // ---------- alur minta semua izin ----------
    private var flowStep = -1
    private var waitingSettings = false
    private val multiPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permFlow(1) }

    // Cadangan untuk <input type=file> di WebView (bila bridge native tidak dipakai).
    private var filePathCb: ValueCallback<Array<Uri>>? = null
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        filePathCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        filePathCb = null
    }

    private fun runtimePerms(): List<String> =
        if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_AUDIO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun openSettings(i: Intent): Boolean = try { startActivity(i); waitingSettings = true; true } catch (_: Exception) { false }

    /** Urutan: izin runtime (notifikasi, galeri, audio) -> alarm tepat waktu -> layar penuh -> abaikan hemat baterai. */
    private fun permFlow(from: Int) {
        var s = from
        while (true) {
            when (s) {
                0 -> {
                    val need = runtimePerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (need.isNotEmpty()) { flowStep = 0; multiPerm.launch(need.toTypedArray()); return }
                }
                1 -> if (Build.VERSION.SDK_INT >= 31 && !AlarmScheduler.canExact(this)) {
                    flowStep = 1
                    if (openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))) return
                }
                2 -> if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
                    flowStep = 2
                    if (openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))) return
                }
                3 -> if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
                    flowStep = 3
                    if (openSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))) return
                }
                else -> { flowStep = -1; return }
            }
            s++
        }
    }

    private fun startPermFlow() {
        if (Prefs.permRuns(this) >= 3) return
        Prefs.bumpPermRuns(this)
        permFlow(0)
    }

    fun launchPhoto() = photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    fun launchSound() = soundPicker.launch(arrayOf("audio/*"))
    fun launchBackupPick() = backupPicker.launch(arrayOf("*/*"))
    fun launchBackupSave(name: String) = backupSaver.launch(name)
    fun launchLocationPermission() = locPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun launchNotifPermission() { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    fun setBars(dark: Boolean) {
        val c = if (dark) 0xFF151416.toInt() else 0xFFF4F2EE.toInt()
        @Suppress("DEPRECATION") run { window.statusBarColor = c; window.navigationBarColor = c }
        if (::web.isInitialized) web.setBackgroundColor(c)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun isTrusted(u: Uri) = u.scheme == "https" && u.host == HOST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readGoto(intent)
        if (REMOTE.isNotEmpty() && (!REMOTE.startsWith("https://") || HOST.isEmpty())) {
            setContentView(TextView(this).apply { text = "URL Vercel tidak valid. Bangun ulang dengan -PtimeflowUrl=https://nama.vercel.app"; setPadding(48, 96, 48, 48) })
            return
        }
        bridge = NativeBridge(this)
        web = WebView(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(web)

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            setContentView(TextView(this).apply {
                text = "Android System WebView di perangkat ini terlalu lama.\nPerbarui lewat Play Store, lalu buka TimeFlow lagi."
                setPadding(48, 96, 48, 48)
            })
            return
        }

        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/profile/", WebViewAssetLoader.InternalStoragePathHandler(this, ProfilePhoto.dir(this)))
            .build()

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage untuk data web
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false   // audio alarm versi web boleh berbunyi tanpa sentuhan
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
                filePathCb?.onReceiveValue(null)
                filePathCb = cb
                return try { chooser.launch(params.createIntent()); true } catch (_: Exception) { filePathCb = null; false }
            }
        }
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != HOST) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                if (REMOTE.isNotEmpty()) {
                    // Mode Vercel: halaman dari jaringan; hanya foto profil dilayani dari file lokal.
                    val u = request.url
                    val name = u.lastPathSegment ?: ""
                    if (u.path?.startsWith("/profile/") == true) {
                        val f = java.io.File(ProfilePhoto.dir(this@MainActivity), name)
                        return if (PHOTO_RE.matches(name) && f.exists())
                            WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "no-store"), f.inputStream())
                        else WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }
                return loader.shouldInterceptRequest(request.url)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isTrusted(request.url)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                runOnUiThread { recreate() }
                return true
            }
        }

        // Hanya origin bundel aplikasi yang boleh mengirim perintah native.
        WebViewCompat.addWebMessageListener(web, "TimeFlowNative", setOf(ORIGIN)) { _, message: WebMessageCompat, origin, isMainFrame, proxy ->
            if (isMainFrame && origin.toString() == ORIGIN) {
                message.data?.let { bridge.handle(it, proxy) }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(function(){return !!(window.tfBack&&window.tfBack())})()") { r -> if (r != "true") finish() }
            }
        })

        web.loadUrl(START)
        web.post { startPermFlow() }
    }

    override fun onStart() {
        super.onStart()
        AlarmEvents.add(alarmListener)
    }

    override fun onResume() {
        super.onResume()
        runCatching { AlarmScheduler.rescheduleAll(this) }   // mis. izin alarm tepat waktu baru diberikan
        runCatching { ScreenTimeWidget.refreshAsync(this, false) }   // widget Waktu Layar: segarkan bila data sudah basi / izin baru dinyalakan
        if (waitingSettings) { waitingSettings = false; permFlow(flowStep + 1) }
        if (::bridge.isInitialized) bridge.event("resume")
    }

    override fun onStop() {
        AlarmEvents.remove(alarmListener)
        super.onStop()
    }

    override fun onDestroy() {
        if (::bridge.isInitialized) bridge.shutdown()
        if (::web.isInitialized) { web.removeAllViews(); web.destroy() }
        super.onDestroy()
    }
}
