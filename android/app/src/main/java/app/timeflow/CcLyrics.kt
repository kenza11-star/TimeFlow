package app.timeflow

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Lirik berjalan (sinkron dengan lagu). */
class Lyrics(val times: LongArray, val lines: Array<String>) {
    /** Baris yang sedang dinyanyikan pada posisi ms (-1 bila belum mulai). */
    fun indexAt(ms: Long): Int {
        var lo = 0; var hi = times.size - 1; var ans = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (times[m] <= ms) { ans = m; lo = m + 1 } else hi = m - 1 }
        return ans
    }
}

/**
 * Mengambil lirik bersinkron dari LRCLIB (lrclib.net, layanan gratis tanpa kunci) berdasarkan judul + artis,
 * lalu menyimpannya di cache lokal supaya lagu yang sama tampil lagi tanpa internet.
 */
object CcLyrics {
    private val mem = HashMap<String, Lyrics?>()
    private val main = Handler(Looper.getMainLooper())

    private fun clean(s: String): String = s
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("(?i)\\s*-\\s*(topic|official.*|lyrics?.*|video.*)$"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun key(t: String, a: String): String =
        MessageDigest.getInstance("MD5").digest((t.lowercase() + "|" + a.lowercase()).toByteArray()).joinToString("") { "%02x".format(it) }

    fun parse(lrc: String): Lyrics? {
        val ts = ArrayList<Long>(); val ls = ArrayList<String>()
        val re = Regex("^\\[(\\d+):(\\d+(?:\\.\\d+)?)](.*)$")
        for (raw in lrc.lines()) {
            val m = re.find(raw.trim()) ?: continue
            val ms = (m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong())
            ts.add(ms); ls.add(m.groupValues[3].trim())
        }
        if (ts.isEmpty()) return null
        return Lyrics(ts.toLongArray(), ls.toTypedArray())
    }

    private fun http(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "TimeFlow/1.0 (Android)")
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }

    private val missAt = HashMap<String, Long>()   // kapan terakhir gagal / kosong (dicoba lagi setelah 40 detik)

    /** cb dipanggil di thread utama (null bila tidak ada lirik bersinkron). */
    fun request(ctx: Context, title: String, artist: String, durMs: Long, cb: (Lyrics?) -> Unit) {
        val t = clean(title); val a = clean(artist)
        if (t.isEmpty()) { cb(null); return }
        val k = key(t, a)
        mem[k]?.let { cb(it); return }
        val miss = missAt[k]
        if (miss != null && System.currentTimeMillis() - miss < 40_000) { cb(null); return }
        val dir = File(ctx.applicationContext.filesDir, "lrc")
        Thread {
            var out: Lyrics? = null
            var why = ""
            runCatching {
                val f = File(dir, "$k.lrc")
                if (f.exists()) out = parse(f.readText())
                if (out == null) {
                    val q = "track_name=" + URLEncoder.encode(t, "UTF-8") + "&artist_name=" + URLEncoder.encode(a, "UTF-8")
                    var synced: String? = null
                    http("https://lrclib.net/api/get?$q" + (if (durMs > 0) "&duration=${durMs / 1000}" else ""))?.let {
                        synced = JSONObject(it).optString("syncedLyrics", "").ifEmpty { null }
                    }
                    if (synced == null) http("https://lrclib.net/api/get?$q")?.let {
                        synced = JSONObject(it).optString("syncedLyrics", "").ifEmpty { null }
                    }
                    fun scan(body: String?) {
                        if (synced != null || body == null) return
                        val arr = JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val s = arr.getJSONObject(i).optString("syncedLyrics", "")
                            if (s.isNotEmpty()) { synced = s; break }
                        }
                    }
                    scan(http("https://lrclib.net/api/search?$q"))
                    scan(http("https://lrclib.net/api/search?q=" + URLEncoder.encode("$t $a".trim(), "UTF-8")))
                    val s = synced
                    if (s != null) { dir.mkdirs(); File(dir, "$k.lrc").writeText(s); out = parse(s) } else why = "tidak ada hasil"
                }
            }.onFailure { why = it.javaClass.simpleName }
            val res = out
            main.post {
                if (res != null) { mem[k] = res; missAt.remove(k) } else missAt[k] = System.currentTimeMillis()
                CcLog.add("lirik '$t': " + (if (res != null) "ok (${res.lines.size} baris)" else "kosong ($why)"))
                cb(res)
            }
        }.start()
    }
}
