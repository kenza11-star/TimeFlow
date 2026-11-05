package app.timeflow

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat

class TimeFlowApp : Application() {
    private val tickRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { GlassClock.refreshAll(c) }
    }

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        // Idempoten: memastikan jadwal ada setelah proses dimulai ulang (mis. setelah force-stop lalu dibuka lagi).
        runCatching { AlarmScheduler.rescheduleAll(this) }
        runCatching { WidgetUtil.scheduleMidnight(this) }
        runCatching { ScreenTimeWidget.watchAccess(this) }   // widget Waktu Layar terisi begitu Akses penggunaan dinyalakan
        AiIsland.registerEvents(this)                        // kapsul info di notch: mengisi daya, baterai lemah, senyap, jangan ganggu
        // Selama peluncur tampil: widget Jam Kaca digambar ulang tiap menit, dan Asisten AI siaga dinyalakan bila diaktifkan di Pengaturan.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var tickOn = false
            override fun onActivityStarted(activity: Activity) {
                if (activity !is HomeActivity) return
                runCatching { GlassClock.refreshAll(activity) }
                if (!tickOn) {
                    tickOn = runCatching {
                        ContextCompat.registerReceiver(this@TimeFlowApp, tickRx, IntentFilter(Intent.ACTION_TIME_TICK), ContextCompat.RECEIVER_NOT_EXPORTED)
                    }.isSuccess
                }
                AiService.ensure(activity)
            }
            override fun onActivityStopped(activity: Activity) {
                if (activity is HomeActivity && tickOn) {
                    runCatching { this@TimeFlowApp.unregisterReceiver(tickRx) }
                    tickOn = false
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
