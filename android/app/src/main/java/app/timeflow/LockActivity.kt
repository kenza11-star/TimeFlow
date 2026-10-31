package app.timeflow

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Layar kunci TimeFlow (tampil di atas layar kunci sistem): wallpaper pilihan, jam besar, kartu musik bila ada lagu diputar.
 * Geser ke atas = minta sistem membuka kunci (PIN / pola / sidik jari / wajah sesuai pengaturan HP).
 */
class LockActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private lateinit var clockTv: TextView
    private lateinit var dateTv: TextView
    private var media: CcMedia? = null
    private var mediaSig = ""
    private val fmtT = SimpleDateFormat("HH:mm", Locale.ENGLISH)
    private val fmtD = SimpleDateFormat("EEE MMM d", Locale.ENGLISH)
    private var y0 = 0f
    private var swiping = false
    private var unlocking = false
    private var slop = 0

    private val host = object : CcHost {
        override fun transport(cmd: Int) = NotchListener.hubTransport(cmd)
        override fun openMedia() {}
        override fun seek(frac: Float) = NotchListener.hubSeek(frac)
        override fun openApp() {}
        override fun mediaNow(): MediaInfo? = NotchListener.hubMedia()
    }

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { finish() }   // sudah terbuka (USER_PRESENT) atau layar mati
    }

    private val loop = object : Runnable { override fun run() { update(); ui.postDelayed(this, 500) } }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        slop = ViewConfiguration.get(this).scaledTouchSlop
        LockScreen.clearNotification(this)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else @Suppress("DEPRECATION") window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(32, 28, 64), Color.rgb(10, 12, 24)))
        loadWallpaper()?.let { b ->
            root.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(b) }, FrameLayout.LayoutParams(-1, -1))
        }
        // gelap tipis di atas & bawah supaya teks terbaca di wallpaper terang
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(130, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, -1))

        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        dateTv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setTextColor(Color.argb(205, 255, 255, 255))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); gravity = Gravity.CENTER
        }
        content.addView(dateTv, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(60f) })
        clockTv = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 128f); setTextColor(Color.argb(190, 255, 255, 255))
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); gravity = Gravity.CENTER
            includeFontPadding = false
        }
        content.addView(clockTv, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(92f) })

        if (LockPrefs.music(this)) {
            val u = dp(78f).toFloat()
            val m = CcMedia(this, CcArt(), host, u, {}, {})
            m.visibility = View.GONE
            media = m
            content.addView(m, FrameLayout.LayoutParams(-1, (2f * u + dp(12f)).toInt(), Gravity.BOTTOM).apply {
                leftMargin = dp(18f); rightMargin = dp(18f); bottomMargin = dp(120f)
            })
        }
        content.addView(TextView(this).apply {
            text = "Geser ke atas untuk membuka"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.argb(190, 255, 255, 255)); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(52f) })
        content.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(Color.argb(200, 255, 255, 255)); cornerRadius = dp(3f).toFloat() }
        }, FrameLayout.LayoutParams(dp(120f), dp(5f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(22f) })

        setContentView(root)
        ContextCompat.registerReceiver(this, rcv, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF)
        }, ContextCompat.RECEIVER_EXPORTED)
        update()
    }

    private fun loadWallpaper(): Bitmap? = runCatching {
        val f = LockPrefs.wallFile(this)
        if (!f.exists()) return null
        val dm = resources.displayMetrics
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= dm.widthPixels && o.outHeight / (s * 2) >= dm.heightPixels) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    private fun update() {
        val now = Date()
        clockTv.text = fmtT.format(now)
        dateTv.text = fmtD.format(now)
        val cm = media ?: return
        val m = NotchListener.hubMedia()
        if (m == null) { cm.visibility = View.GONE; mediaSig = ""; return }
        cm.visibility = View.VISIBLE
        val sig = m.title + "|" + m.artist + "|" + m.playing + "|" + System.identityHashCode(m.art)
        if (sig != mediaSig) { mediaSig = sig; cm.setMedia(m) } else cm.tick()
    }

    override fun onStart() { super.onStart(); ui.removeCallbacks(loop); ui.post(loop) }
    override fun onStop() { ui.removeCallbacks(loop); super.onStop(); if (!isFinishing && !unlocking) finish() }
    override fun onDestroy() { runCatching { unregisterReceiver(rcv) }; super.onDestroy() }
    @Deprecated("Deprecated in Java") override fun onBackPressed() {}

    // ---- geser ke atas = buka kunci ----
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (unlocking) return true
        val h = resources.displayMetrics.heightPixels.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { y0 = e.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = y0 - e.rawY
                if (!swiping && dy > slop * 2) {
                    swiping = true
                    val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle()
                }
                if (swiping) {
                    val d = max(0f, dy)
                    content.translationY = -d; content.alpha = (1f - d / (h * 0.7f)).coerceIn(0.2f, 1f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                swiping = false
                if (y0 - e.rawY > h * 0.16f) unlock() else content.animate().translationY(0f).alpha(1f).setDuration(180).start()
                return true
            }
        }
        return super.dispatchTouchEvent(e)
    }

    private fun unlock() {
        unlocking = true
        val h = resources.displayMetrics.heightPixels.toFloat()
        content.animate().translationY(-h * 0.35f).alpha(0f).setDuration(160).withEndAction {
            val km = getSystemService(KeyguardManager::class.java)
            if (km == null || !km.isKeyguardLocked) { finish(); return@withEndAction }
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
                override fun onDismissCancelled() { reset() }
                override fun onDismissError() { reset() }
            })
        }.start()
    }

    private fun reset() { unlocking = false; content.animate().translationY(0f).alpha(1f).setDuration(200).start() }
}
