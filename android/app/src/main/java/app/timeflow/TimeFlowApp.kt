package app.timeflow

import android.app.Application

class TimeFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        // Idempoten: memastikan jadwal ada setelah proses dimulai ulang (mis. setelah force-stop lalu dibuka lagi).
        runCatching { AlarmScheduler.rescheduleAll(this) }
        runCatching { WidgetUtil.scheduleMidnight(this) }
    }
}
