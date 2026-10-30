package app.timeflow

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Data Waktu Layar untuk widget (ScreenTimeWidget). Sumber: UsageStatsManager (izin "Akses penggunaan" yang diberikan pengguna sendiri).
 * Semua dihitung di perangkat dan disimpan hanya di SharedPreferences aplikasi ini: tidak ada yang dikirim ke jaringan, tidak ada data lain yang dibaca.
 *
 * Cara hitung: durasi tiap aplikasi di layar depan dijumlahkan dari event ACTIVITY_RESUMED/PAUSED (seperti Digital Wellbeing), bukan dari
 * queryUsageStats() yang sudah digabung per "bucket" dan bisa meleset di batas hari. Satu kali query event untuk seluruh minggu (Minggu sampai Sabtu),
 * lalu dipecah per hari di memori. Hari yang sudah lewat disimpan final (tidak dihitung ulang), jadi pembaruan berikutnya hanya memproses hari yang masih berjalan.
 */
object ScreenTimeRepo {
    private const val SP = "timeflow_screentime"
    private const val K_SNAP = "snap"
    private const val K_SEL = "sel"
    private const val TOP_APPS = 8                     // aplikasi teratas yang disimpan per hari
    const val STALE_MS = 5 * 60_000L                   // data hari ini dianggap basi setelah 5 menit (pembaruan otomatis tidak lebih sering)

    // event UsageEvents (konstanta ditulis angka agar jalan di minSdk 26 tanpa peringatan deprecated)
    private const val EV_RESUMED = 1                   // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
    private const val EV_PAUSED = 2                    // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
    private const val EV_SCREEN_OFF = 16               // SCREEN_NON_INTERACTIVE
    private const val EV_SHUTDOWN = 26                 // DEVICE_SHUTDOWN
    private const val EV_STOPPED = 23                  // ACTIVITY_STOPPED

    class App(val pkg: String, val ms: Long)
    class Day(val key: String, val total: Long, val apps: List<App>, val final: Boolean)
    class Snapshot(val at: Long, val weekKey: String, val days: List<Day>)

    /** Status yang ditampilkan widget. */
    enum class State { NO_ACCESS, UNSUPPORTED, LOADING, EMPTY, OK }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ---------- izin ----------
    /** Izin Akses penggunaan aktif? (PACKAGE_USAGE_STATS tidak bisa diminta lewat dialog; pengguna harus menyalakannya sendiri di Pengaturan.) */
    fun hasAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (mode == AppOpsManager.MODE_DEFAULT) ctx.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS") == PackageManager.PERMISSION_GRANTED
        else mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun supported(ctx: Context): Boolean = ctx.getSystemService(Context.USAGE_STATS_SERVICE) != null

    /** Intent ke halaman Akses penggunaan: langsung ke halaman TimeFlow bila bisa (Android 10+), kalau tidak daftar umum, terakhir Pengaturan biasa. */
    fun settingsIntent(ctx: Context): Intent {
        val pm = ctx.packageManager
        val specific = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))
        val general = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        val i = when {
            specific.resolveActivity(pm) != null -> specific
            general.resolveActivity(pm) != null -> general
            else -> Intent(android.provider.Settings.ACTION_SETTINGS)
        }
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---------- tanggal ----------
    fun dayKey(c: Calendar): String =
        String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    private fun midnight(c: Calendar) {
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    }

    /** 8 batas hari (Minggu 00:00 ... Minggu berikutnya 00:00) untuk minggu yang memuat [now]. Dihitung dengan Calendar supaya aman terhadap perubahan jam (DST). */
    private fun weekBounds(now: Long): LongArray {
        val c = Calendar.getInstance().apply { timeInMillis = now; midnight(this) }
        c.add(Calendar.DAY_OF_YEAR, -(c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY))     // mundur ke Minggu
        val out = LongArray(8)
        for (i in 0..7) { out[i] = c.timeInMillis; c.add(Calendar.DAY_OF_YEAR, 1) }
        return out
    }

    fun keyOf(ms: Long): String = dayKey(Calendar.getInstance().apply { timeInMillis = ms })

    /** Kunci minggu = tanggal hari Minggu-nya. */
    fun currentWeekKey(now: Long = System.currentTimeMillis()): String = keyOf(weekBounds(now)[0])

    // ---------- simpan / baca ----------
    fun load(ctx: Context): Snapshot? = runCatching {
        val s = sp(ctx).getString(K_SNAP, null) ?: return null
        val o = JSONObject(s)
        val arr = o.getJSONArray("days")
        val days = (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val a = d.getJSONArray("apps")
            Day(d.getString("k"), d.getLong("t"), (0 until a.length()).map { j -> a.getJSONArray(j).let { App(it.getString(0), it.getLong(1)) } }, d.optBoolean("f"))
        }
        Snapshot(o.getLong("at"), o.getString("wk"), days)
    }.getOrNull()

    private fun save(ctx: Context, s: Snapshot) {
        val arr = JSONArray()
        for (d in s.days) arr.put(JSONObject().put("k", d.key).put("t", d.total).put("f", d.final)
            .put("apps", JSONArray().also { a -> d.apps.forEach { a.put(JSONArray().put(it.pkg).put(it.ms)) } }))
        sp(ctx).edit().putString(K_SNAP, JSONObject().put("at", s.at).put("wk", s.weekKey).put("days", arr).toString()).apply()
    }

    /** Hapus semua data tersimpan (dipanggil saat widget terakhir dihapus). */
    fun clear(ctx: Context) { sp(ctx).edit().clear().apply() }

    fun selectedKey(ctx: Context): String = sp(ctx).getString(K_SEL, "") ?: ""
    fun select(ctx: Context, key: String) { sp(ctx).edit().putString(K_SEL, key).apply() }

    /** Perlu dihitung ulang? Ya bila belum ada data, minggunya sudah ganti, hari berganti, atau data hari ini sudah lewat [STALE_MS]. */
    fun isStale(s: Snapshot?, now: Long = System.currentTimeMillis()): Boolean =
        s == null || s.weekKey != currentWeekKey(now) || now - s.at > STALE_MS || keyOf(s.at) != keyOf(now)

    // ---------- perhitungan ----------
    private class Open(var start: Long, val classes: HashSet<String> = HashSet())

    /**
     * Hitung ulang minggu ini. Hari yang sudah final di [prev] dipakai ulang; hanya hari yang belum final yang diproses.
     * Panggil dari thread latar. Mengembalikan null bila gagal (mis. izin dicabut).
     */
    fun compute(ctx: Context, prev: Snapshot?): Snapshot? = runCatching {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val b = weekBounds(now)
        val wk = keyOf(b[0])
        val reuse = HashMap<Int, Day>()
        if (prev != null && prev.weekKey == wk) prev.days.forEachIndexed { i, d -> if (d.final && i < 7 && d.key == keyOf(b[i])) reuse[i] = d }
        // hari pertama yang perlu diproses (di antara hari yang sudah dimulai)
        val need = (0..6).filter { b[it] <= now && !reuse.containsKey(it) }
        val per = Array(7) { HashMap<String, Long>() }
        if (need.isNotEmpty()) {
            val from = b[need.first()]
            val pm = ctx.packageManager
            val ok = HashMap<String, Boolean>()
            val home = runCatching {
                pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }.getOrNull()
            // hanya aplikasi yang punya ikon peluncur (bukan layanan sistem / launcher), seperti daftar di Waktu pemakaian sistem
            fun countable(p: String): Boolean = ok.getOrPut(p) {
                p != home && p != "android" && p != "com.android.systemui" && runCatching { pm.getLaunchIntentForPackage(p) != null }.getOrDefault(false)
            }

            fun add(p: String, s0: Long, e0: Long) {
                val s = maxOf(s0, from); val e = minOf(e0, now)
                if (e <= s || !countable(p)) return
                for (i in 0..6) {
                    val lo = maxOf(s, b[i]); val hi = minOf(e, b[i + 1])
                    if (hi > lo && i in need) per[i][p] = (per[i][p] ?: 0L) + (hi - lo)
                }
            }

            val open = HashMap<String, Open>()
            val seen = HashSet<String>()
            val ev = UsageEvents.Event()
            val events = usm.queryEvents(from, now)
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val t = ev.timeStamp
                val p = ev.packageName ?: continue
                val cls = ev.className ?: ""
                when (ev.eventType) {
                    EV_RESUMED -> {
                        seen.add("$p/$cls")
                        val o = open.getOrPut(p) { Open(t) }
                        if (o.classes.isEmpty()) o.start = t
                        o.classes.add(cls)
                    }
                    EV_PAUSED, EV_STOPPED -> {
                        val o = open[p]
                        if (o != null && o.classes.remove(cls)) {
                            if (o.classes.isEmpty()) { add(p, o.start, t); open.remove(p) }
                        } else if (ev.eventType == EV_PAUSED && seen.add("$p/$cls")) {
                            // PAUSED tanpa RESUMED di jendela ini: aplikasi sudah terbuka sejak sebelum awal rentang
                            add(p, from, t)
                        }
                    }
                    EV_SCREEN_OFF, EV_SHUTDOWN -> {
                        for ((pk, o) in open) add(pk, o.start, t)
                        open.clear()
                    }
                }
            }
            for ((pk, o) in open) add(pk, o.start, now)     // masih terbuka sekarang
        }

        val days = (0..6).map { i ->
            reuse[i] ?: run {
                val started = b[i] <= now
                val list = per[i].entries.filter { it.value >= 1000L }.sortedByDescending { it.value }
                val total = list.sumOf { it.value }
                Day(keyOf(b[i]), if (started) total else 0L, if (started) list.take(TOP_APPS).map { App(it.key, it.value) } else emptyList(), now >= b[i + 1])
            }
        }
        Snapshot(now, wk, days).also { save(ctx, it) }
    }.getOrNull()

    // ---------- status ----------
    fun state(ctx: Context, s: Snapshot?): State = when {
        !supported(ctx) -> State.UNSUPPORTED
        !hasAccess(ctx) -> State.NO_ACCESS
        s == null || s.weekKey != currentWeekKey() -> State.LOADING
        s.days.all { it.total == 0L } -> State.EMPTY
        else -> State.OK
    }

    // ---------- format ----------
    fun fmt(ms: Long): String {
        val m = ms / 60_000
        return when {
            m >= 60 -> if (m % 60 == 0L) "${m / 60} j" else "${m / 60} j ${m % 60} mnt"
            m >= 1 -> "$m mnt"
            ms >= 1000 -> "< 1 mnt"
            else -> "0 mnt"
        }
    }
}
