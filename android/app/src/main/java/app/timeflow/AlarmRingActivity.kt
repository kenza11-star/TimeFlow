package app.timeflow

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Layar alarm di atas kunci layar. Hanya tampil lewat full-screen intent dari notifikasi alarm. */
class AlarmRingActivity : Activity() {
    private var alarmId = ""
    private val listener: () -> Unit = { if (alarmId !in AlarmService.ringing) finish() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(i: Intent) {
        alarmId = i.getStringExtra(K.EXTRA_ID) ?: run { finish(); return }
        val a = AlarmStore.get(this, alarmId) ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(dp(24), dp(96), dp(24), dp(48))
        }
        root.addView(TextView(this).apply {
            text = hm(a.hour, a.minute); setTextColor(Color.WHITE); textSize = 72f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        root.addView(TextView(this).apply {
            text = a.label.ifBlank { "Alarm" }; setTextColor(Color.parseColor("#E8683E")); textSize = 22f
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        root.addView(android.view.View(this), LinearLayout.LayoutParams(0, 0, 1f))

        fun btn(label: String, bg: String, fg: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 18f; setTextColor(Color.parseColor(fg))
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(32).toFloat(); setColor(Color.parseColor(bg)) }
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(12) }
        }
        root.addView(btn("Tunda ${a.snoozeMin} menit", "#3A383D", "#FFFFFF") { AlarmService.snooze(this, alarmId) })
        root.addView(btn("Stop", "#E8683E", "#FFFFFF") { AlarmService.stop(this, alarmId) })
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        AlarmEvents.add(listener)
        listener()
    }

    override fun onStop() {
        AlarmEvents.remove(listener)
        super.onStop()
    }
}
