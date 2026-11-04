package app.timeflow

import android.content.Context
import org.json.JSONArray

/**
 * Daftar aplikasi yang disembunyikan lewat Layar Tersembunyi (cubit melebar di layar utama).
 * Aplikasi di daftar ini tidak tampil di layar utama, folder, laci, dan pemilih folder; pratinjau notifikasinya di notch disamarkan
 * dan aplikasinya tidak muncul di widget Waktu Layar. Disimpan di SharedPreferences "timeflow_home".
 */
object HiddenStore {
    private const val SP = "timeflow_home"
    private const val KEY = "hidden_apps"
    private const val KEY_LOCK = "hidden_lock"
    private var loaded = false
    private val set = LinkedHashSet<String>()

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        val raw = sp(c).getString(KEY, null) ?: return
        runCatching {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) set.add(a.getString(i))
        }
    }

    private fun save(c: Context) {
        val a = JSONArray()
        for (p in set) a.put(p)
        sp(c).edit().putString(KEY, a.toString()).apply()
    }

    @Synchronized
    fun has(c: Context, pkg: String): Boolean { load(c); return pkg in set }

    @Synchronized
    fun all(c: Context): List<String> { load(c); return ArrayList(set) }

    @Synchronized
    fun add(c: Context, pkgs: Collection<String>) {
        load(c)
        if (set.addAll(pkgs)) save(c)
    }

    @Synchronized
    fun remove(c: Context, pkg: String): Boolean {
        load(c)
        val r = set.remove(pkg)
        if (r) save(c)
        return r
    }

    /** Layar Tersembunyi dikunci dengan PIN / pola / sidik jari HP (bila HP punya kunci layar). Bawaan: nyala. */
    fun lockOn(c: Context): Boolean = sp(c).getBoolean(KEY_LOCK, true)

    fun setLock(c: Context, on: Boolean) { sp(c).edit().putBoolean(KEY_LOCK, on).apply() }
}
