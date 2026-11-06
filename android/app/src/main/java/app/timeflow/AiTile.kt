package app.timeflow

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Ubin "Flow AI" di Quick Settings: membangunkan atau menidurkan AI. Saat AI tidur, mikrofon mati total. */
class AiTileService : TileService() {
    override fun onStartListening() = refresh()

    private fun refresh() {
        val t = qsTile ?: return
        val on = AiService.running && AiService.active
        t.label = "Flow AI"
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Mendengarkan" else "Tidur"
        t.updateTile()
    }

    override fun onClick() {
        if (AiService.running && AiService.active) { AiService.sleep(this); refresh(); return }
        // layanan mikrofon harus dimulai dari activity yang tampil: lewat activity tanpa tampilan
        val i = Intent(this, AiLaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}

/** Activity tanpa tampilan: menyalakan AI lalu menutup diri. */
class AiLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            !AiPrefs.enabled(this) -> Toast.makeText(this, "Nyalakan asisten AI dulu di Asisten AI", Toast.LENGTH_LONG).show()
            !AiService.hasMic(this) -> Toast.makeText(this, "Izinkan mikrofon dulu di Asisten AI", Toast.LENGTH_LONG).show()
            else -> AiService.activate(this)
        }
        finish()
    }
}
