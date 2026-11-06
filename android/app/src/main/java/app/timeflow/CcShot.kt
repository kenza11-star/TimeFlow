package app.timeflow

import android.graphics.Bitmap
import android.os.SystemClock

/** Tangkapan layar terakhir yang berhasil (sudah dikecilkan) untuk latar blur Control Center saat tangkapan baru ditolak (layar aman/game, batas jarak, dll). */
object CcShot {
    private const val MAX_AGE = 3 * 60_000L
    @Volatile private var bmp: Bitmap? = null
    @Volatile private var at = 0L

    fun put(b: Bitmap) { bmp = b; at = SystemClock.elapsedRealtime() }

    fun recent(): Bitmap? {
        val b = bmp ?: return null
        if (b.isRecycled || SystemClock.elapsedRealtime() - at > MAX_AGE) return null
        return b
    }
}
