package app.timeflow

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Ubin "Control Center" di Quick Settings bawaan HP: cara paling andal membuka panel (tidak bergantung pada geser di bar status). */
class CcTileService : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.label = "Control Center"; it.state = Tile.STATE_ACTIVE; it.updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, CcLaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}

/** Activity tanpa tampilan: menutup bar notifikasi lalu meminta layanan membuka panel. */
class CcLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!NotchListener.ccOpen())
            Toast.makeText(this, "Aktifkan Control Center di TimeFlow > Settings dan beri dua izinnya dulu", Toast.LENGTH_LONG).show()
        finish()
    }
}
