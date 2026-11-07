package app.timeflow

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

/** Membuka tujuan notifikasi dari layanan latar: PendingIntent dengan izin mulai activity (API 34+), lalu cadangan ke peluncur aplikasi asal. */
object NotifOpen {
    fun launch(ctx: Context, pi: PendingIntent?, pkg: String): Boolean {
        if (pi != null && send(ctx, pi)) return true
        return launchPackage(ctx, pkg)
    }

    private fun send(ctx: Context, pi: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            val o = ActivityOptions.makeBasic()
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(ctx, 0, null, null, null, null, o.toBundle())
        } else pi.send()
        true
    } catch (_: PendingIntent.CanceledException) { false }
    catch (_: Exception) { false }

    private fun launchPackage(ctx: Context, pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        return try {
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    fun failed(ctx: Context) { runCatching { Toast.makeText(ctx, "Tidak bisa membuka aplikasi", Toast.LENGTH_SHORT).show() } }
}
