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
    private val state = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var netErr = false

    /** Status pencarian lirik untuk ditampilkan di tempat lirik (kosong bila sudah ada lirik). */
    fun status(title: String, artist: String): String = state[key(clean(title), clean(artist))] ?: ""

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

    private fun http(u: String): String? = if (!Net.connectedNow()) { netErr = true; null } else try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "TimeFlow/1.0 (Android)")
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) { netErr = true; null }

    /** Sumber cadangan: NetEase (lirik bersinkron). Dicocokkan dengan artis dan durasi supaya tidak salah lagu. */
    private fun netease(t: String, a: String, durMs: Long): String? {
        val q = URLEncoder.encode(("$t $a").trim(), "UTF-8")
        val body = http("https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=$q&type=1&offset=0&total=true&limit=5") ?: return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
        val wa = a.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        for (i in 0 until songs.length()) {
            val o = songs.getJSONObject(i)
            val id = o.optLong("id")
            if (id <= 0) continue
            val an = (o.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: "").lowercase()
            if (wa.isNotEmpty() && an.isNotEmpty() && wa.none { an.contains(it) }) continue
            val dd = o.optLong("duration")
            if (durMs > 0 && dd > 0 && Math.abs(dd - durMs) > 8000) continue
            val lb = http("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1") ?: continue
            val lrc = JSONObject(lb).optJSONObject("lrc")?.optString("lyric", "") ?: ""
            if (Regex("\\[\\d+:\\d+").containsMatchIn(lrc)) return lrc
        }
        return null
    }

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
        state[k] = "Mencari lirik…"
        Thread {
            netErr = false
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
                    var plain: String? = null
                    fun scan(body: String?) {
                        if (synced != null || body == null) return
                        val arr = JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val s = o.optString("syncedLyrics", "")
                            if (s.isNotEmpty()) { synced = s; break }
                            if (plain == null) { val p = o.optString("plainLyrics", ""); if (p.isNotEmpty()) plain = p }
                        }
                    }
                    scan(http("https://lrclib.net/api/search?$q"))
                    scan(http("https://lrclib.net/api/search?q=" + URLEncoder.encode("$t $a".trim(), "UTF-8")))
                    scan(http("https://lrclib.net/api/search?track_name=" + URLEncoder.encode(t, "UTF-8")))
                    scan(http("https://lrclib.net/api/search?q=" + URLEncoder.encode(t, "UTF-8")))
                    if (synced == null) netease(t, a, durMs)?.let { synced = it }
                    val s = synced
                    val pl = plain
                    if (s != null) { dir.mkdirs(); File(dir, "$k.lrc").writeText(s); out = parse(s) }
                    else if (pl != null && durMs > 0) {
                        // hanya ada lirik tanpa waktu: baris dibagi rata sepanjang lagu (perkiraan kasar)
                        val ls = pl.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (ls.size >= 4) {
                            val sb = StringBuilder()
                            ls.forEachIndexed { i, l ->
                                val ms = (durMs * (0.06 + 0.86 * i / ls.size)).toLong()
                                sb.append(String.format(java.util.Locale.US, "[%02d:%05.2f]%s\n", ms / 60000, (ms % 60000) / 1000.0, l))
                            }
                            out = parse(sb.toString())
                        }
                        if (out == null) why = "tidak ada hasil"
                    } else why = "tidak ada hasil"
                }
            }.onFailure { why = it.javaClass.simpleName }
            val res = out
            val netBad = netErr
            main.post {
                state[k] = if (res != null) "" else if (netBad) "Tidak terhubung ke server lirik" else "Lirik tidak ditemukan"
                if (res != null) { mem[k] = res; missAt.remove(k) } else missAt[k] = System.currentTimeMillis()
                CcLog.add("lirik '$t': " + (if (res != null) "ok (${res.lines.size} baris)" else "kosong ($why)"))
                cb(res)
            }
        }.start()
    }
}
