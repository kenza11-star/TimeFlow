package app.timeflow

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Cek koneksi murah (tanpa permintaan jaringan): dipakai agar fitur online langsung beralih ke cadangan saat offline, bukan menunggu timeout. */
object Net {
    @Volatile private var app: Context? = null

    fun init(c: Context) { app = c.applicationContext }

    private fun caps(c: Context): NetworkCapabilities? {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val n = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(n)
    }

    /** Ada jaringan aktif yang bisa dipakai internet (belum tentu lancar). Bila status tidak bisa dibaca, dianggap tersambung. */
    fun connected(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        if (cm.activeNetwork == null) return false
        return caps(c)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
    }

    /** Tersambung dan sudah dicek sistem benar-benar bisa ke internet (false saat sinyal jelek, portal login, atau offline). */
    fun good(c: Context): Boolean = caps(c)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false

    fun connectedNow(): Boolean = app?.let { connected(it) } ?: true
}
