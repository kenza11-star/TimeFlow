package app.timeflow

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Penyimpanan alarm native (file JSON atomik di penyimpanan privat aplikasi). Sumber kebenaran untuk penjadwalan. */
object AlarmStore {
    private val lock = Any()
    private var cache: MutableList<Alarm>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "alarms.json")

    private fun load(ctx: Context): MutableList<Alarm> {
        cache?.let { return it }
        val list = mutableListOf<Alarm>()
        try {
            val txt = readAtomic(file(ctx))
            if (txt != null) {
                val arr = JSONArray(txt)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    runCatching { list += Alarm.parse(o, o.getString("owner"), internal = true) }
                }
            }
        } catch (_: Exception) {
            // File rusak: simpan salinan untuk pemeriksaan, mulai dari daftar kosong.
            runCatching { file(ctx).copyTo(File(ctx.filesDir, "alarms.corrupt.json"), overwrite = true) }
        }
        cache = list
        return list
    }

    private fun persist(ctx: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson(internal = true)) }
        writeAtomic(file(ctx), arr.toString())
    }

    fun all(ctx: Context): List<Alarm> = synchronized(lock) { load(ctx).toList() }

    fun forOwner(ctx: Context, owner: String): List<Alarm> = all(ctx).filter { it.owner == owner }

    fun get(ctx: Context, id: String): Alarm? =
        if (id == Alarm.TEST_ID) Alarm.test() else synchronized(lock) { load(ctx).firstOrNull { it.id == id } }

    /** Simpan konfigurasi dari web. snoozeAt/lastFired lama dipertahankan kecuali jadwal berubah. */
    fun upsert(ctx: Context, a: Alarm): Alarm = synchronized(lock) {
        val l = load(ctx)
        val i = l.indexOfFirst { it.id == a.id }
        val merged = if (i >= 0) {
            val old = l[i]
            val scheduleChanged = old.hour != a.hour || old.minute != a.minute || old.days != a.days ||
                old.everyMin != a.everyMin || old.untilHour != a.untilHour || old.untilMinute != a.untilMinute ||
                (!old.enabled && a.enabled)
            a.copy(snoozeAt = if (scheduleChanged || !a.enabled) 0L else old.snoozeAt, lastFired = if (scheduleChanged) 0L else old.lastFired)
        } else a
        if (i >= 0) l[i] = merged else l += merged
        persist(ctx, l)
        merged
    }

    /** Ganti seluruh alarm milik [owner]. Mengembalikan alarm lama (untuk membatalkan jadwalnya). */
    fun replaceOwner(ctx: Context, owner: String, items: List<Alarm>): List<Alarm> = synchronized(lock) {
        val l = load(ctx)
        val old = l.filter { it.owner == owner }
        l.removeAll { it.owner == owner }
        l += items
        persist(ctx, l)
        old
    }

    fun delete(ctx: Context, id: String): Alarm? = synchronized(lock) {
        val l = load(ctx)
        val a = l.firstOrNull { it.id == id } ?: return null
        l.remove(a)
        persist(ctx, l)
        a
    }

    fun update(ctx: Context, id: String, f: (Alarm) -> Alarm): Alarm? = synchronized(lock) {
        if (id == Alarm.TEST_ID) return Alarm.test()
        val l = load(ctx)
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return null
        l[i] = f(l[i])
        persist(ctx, l)
        l[i]
    }

    fun resetLastFired(ctx: Context) = synchronized(lock) {
        val l = load(ctx)
        for (i in l.indices) l[i] = l[i].copy(lastFired = 0L)
        persist(ctx, l)
    }
}
