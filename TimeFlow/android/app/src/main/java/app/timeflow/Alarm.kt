package app.timeflow

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Satu alarm. Hari memakai indeks yang sama dengan Date.getDay() di JavaScript: 0 = Minggu ... 6 = Sabtu.
 * Daftar hari kosong = alarm sekali jalan. everyMin > 0 = berdering ulang tiap N menit dari waktu mulai sampai waktu selesai.
 */
data class Alarm(
    val id: String,
    val owner: String,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val sound: String = SoundLibrary.DEFAULT_ID,
    val vibrate: Boolean = true,
    val vibPattern: Int = 0,
    val snoozeMin: Int = 10,
    val everyMin: Int = 0,
    val untilHour: Int = 23,
    val untilMinute: Int = 59,
    val snoozeAt: Long = 0L,
    val lastFired: Long = 0L,
) {
    /** Waktu dering berikutnya (epoch ms) yang benar-benar setelah [after], atau null. */
    fun nextTrigger(after: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val today = java.time.Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        val start = LocalTime.of(hour, minute)
        val end = LocalTime.of(untilHour, untilMinute)
        for (off in 0..8) {
            val date = today.plusDays(off.toLong())
            // DayOfWeek: Senin=1..Minggu=7  ->  % 7 menghasilkan Minggu=0, Senin=1 ... Sabtu=6
            if (days.isNotEmpty() && (date.dayOfWeek.value % 7) !in days) continue
            var t = start
            while (true) {
                val cand = ZonedDateTime.of(date, t, zone).toInstant().toEpochMilli()
                if (cand > after) return cand
                if (everyMin <= 0) break
                val nt = t.plusMinutes(everyMin.toLong())
                if (nt.isBefore(t) || nt.isAfter(end)) break
                t = nt
            }
            if (days.isEmpty() && off >= 1) break
        }
        return null
    }

    fun toJson(next: Long = 0L, internal: Boolean = false): JSONObject = JSONObject().apply {
        put("id", id)
        put("time", hm(hour, minute))
        put("label", label)
        put("days", JSONArray(days.sorted()))
        put("on", enabled)
        put("sound", sound)
        put("vibrate", vibrate)
        put("vib", vibPattern)
        put("snooze", snoozeMin)
        put("every", everyMin)
        put("until", hm(untilHour, untilMinute))
        put("snoozeAt", snoozeAt)
        put("next", next)
        if (internal) { put("owner", owner); put("lastFired", lastFired) }
    }

    companion object {
        const val TEST_ID = "test"
        private val ID_RE = Regex("^[A-Za-z0-9_-]{1,40}$")
        private val TIME_RE = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")
        private val SOUND_RE = Regex("^(builtin|custom):[A-Za-z0-9_-]{1,40}$")
        val OWNER_RE = Regex("^[a-z0-9_]{1,20}$")

        fun test() = Alarm(TEST_ID, "_test", 0, 0, label = "Uji alarm", snoozeMin = 1)

        /** Validasi ketat. [internal] = baca dari penyimpanan native (boleh membawa snoozeAt/lastFired). */
        fun parse(o: JSONObject, owner: String, internal: Boolean = false): Alarm {
            require(OWNER_RE.matches(owner)) { "Pemilik alarm tidak valid" }
            val id = o.optString("id", "")
            require(ID_RE.matches(id)) { "ID alarm tidak valid" }
            val tm = TIME_RE.matchEntire(o.optString("time", "")) ?: throw IllegalArgumentException("Format waktu alarm tidak valid")
            val un = TIME_RE.matchEntire(o.optString("until", "23:59")) ?: throw IllegalArgumentException("Format waktu selesai tidak valid")
            val days = mutableSetOf<Int>()
            o.optJSONArray("days")?.let { a ->
                for (i in 0 until a.length()) {
                    val d = a.optInt(i, -1)
                    require(d in 0..6) { "Hari alarm tidak valid" }
                    days += d
                }
            }
            val every = o.optInt("every", 0)
            require(every in 0..720) { "Interval pengulangan tidak valid" }
            val a = Alarm(
                id = id, owner = owner,
                hour = tm.groupValues[1].toInt(), minute = tm.groupValues[2].toInt(),
                label = o.optString("label", "").trim().take(60),
                days = days,
                enabled = o.optBoolean("on", true),
                sound = o.optString("sound", SoundLibrary.DEFAULT_ID).takeIf { SOUND_RE.matches(it) } ?: SoundLibrary.DEFAULT_ID,
                vibrate = o.optBoolean("vibrate", true),
                vibPattern = o.optInt("vib", 0).coerceIn(0, 2),
                snoozeMin = o.optInt("snooze", 10).coerceIn(1, 60),
                everyMin = every,
                untilHour = un.groupValues[1].toInt(), untilMinute = un.groupValues[2].toInt(),
                snoozeAt = if (internal) o.optLong("snoozeAt", 0L) else 0L,
                lastFired = if (internal) o.optLong("lastFired", 0L) else 0L,
            )
            if (every > 0) require(hm(a.untilHour, a.untilMinute) > hm(a.hour, a.minute)) { "Waktu selesai harus setelah waktu mulai" }
            return a
        }
    }
}
