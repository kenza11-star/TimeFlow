package app.timeflow

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Riwayat notifikasi yang tersimpan di perangkat (SQLite, tidak pernah dikirim ke mana pun).
 * Diisi oleh NotchListener untuk setiap notifikasi yang masuk; dibaca halaman "Riwayat notifikasi" lewat NativeBridge.
 */
object NotifHistory {
    private const val MAX_ROWS = 5000
    private const val DAY_MS = 86_400_000L

    private class Db(c: Context) : SQLiteOpenHelper(c.applicationContext, "notif_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE n (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, app TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, ts INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX n_ts ON n(ts)")
            db.execSQL("CREATE INDEX n_pkg ON n(pkg)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Volatile private var helper: Db? = null
    private var adds = 0

    private fun db(c: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) { helper ?: Db(c).also { helper = it } }
        return h.writableDatabase
    }

    private fun sp(c: Context) = c.getSharedPreferences("notif_history", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = sp(c).getBoolean("enabled", true)
    /** Lama simpan dalam hari; 0 = selamanya (tetap dibatasi 5000 notifikasi terbaru). */
    fun retention(c: Context): Int = sp(c).getInt("days", 90)

    fun count(c: Context): Long = DatabaseUtils.queryNumEntries(db(c), "n")

    fun settings(c: Context): JSONObject =
        JSONObject().put("enabled", enabled(c)).put("days", retention(c)).put("count", count(c))

    fun set(c: Context, o: JSONObject) {
        val e = sp(c).edit()
        if (o.has("enabled")) e.putBoolean("enabled", o.optBoolean("enabled"))
        if (o.has("days")) e.putInt("days", o.optInt("days").coerceIn(0, 3650))
        e.apply()
        prune(c)
    }

    @Synchronized
    fun add(c: Context, pkg: String, app: String, title: String, body: String, ts: Long) {
        if (!enabled(c)) return
        val d = db(c)
        val t = title.take(300)
        val b = body.take(4000)
        // buang duplikat beruntun (aplikasi yang memposting ulang notifikasi yang sama)
        d.rawQuery("SELECT pkg, title, body, ts FROM n ORDER BY id DESC LIMIT 1", null).use { cur ->
            if (cur.moveToFirst() && cur.getString(0) == pkg && cur.getString(1) == t && cur.getString(2) == b && ts - cur.getLong(3) < 3000L) return
        }
        val v = ContentValues()
        v.put("pkg", pkg); v.put("app", app); v.put("title", t); v.put("body", b); v.put("ts", ts)
        d.insert("n", null, v)
        adds++
        if (adds % 40 == 0) prune(c)
    }

    fun prune(c: Context) {
        val d = db(c)
        val days = retention(c)
        if (days > 0) d.delete("n", "ts < ?", arrayOf((System.currentTimeMillis() - days * DAY_MS).toString()))
        d.execSQL("DELETE FROM n WHERE id <= (SELECT id FROM n ORDER BY id DESC LIMIT 1 OFFSET $MAX_ROWS)")
    }

    /**
     * Daftar riwayat, terbaru dulu. q = kata kunci (semua kata harus cocok di nama aplikasi, judul, atau isi),
     * pkg = filter satu aplikasi, since = batas waktu awal (epoch ms), before = id terakhir halaman sebelumnya.
     */
    fun list(c: Context, q: String, pkg: String, since: Long, before: Long, limit: Int): JSONObject {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (pkg.isNotEmpty()) { where.append(" AND pkg = ?"); args.add(pkg) }
        if (since > 0L) { where.append(" AND ts >= ?"); args.add(since.toString()) }
        for (w in q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(6)) {
            val like = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where.append(" AND (app LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\')")
            args.add(like); args.add(like); args.add(like)
        }
        val d = db(c)
        val total = DatabaseUtils.longForQuery(d, "SELECT COUNT(*) FROM n WHERE $where", args.toTypedArray())
        val listArgs = ArrayList<String>(args)
        var listWhere = where.toString()
        if (before > 0L) { listWhere += " AND id < ?"; listArgs.add(before.toString()) }
        val items = JSONArray()
        var more = false
        d.rawQuery("SELECT id, pkg, app, title, body, ts FROM n WHERE $listWhere ORDER BY id DESC LIMIT ${limit + 1}", listArgs.toTypedArray()).use { cur ->
            while (cur.moveToNext()) {
                if (items.length() >= limit) { more = true; break }
                items.put(JSONObject().put("id", cur.getLong(0)).put("pkg", cur.getString(1)).put("app", cur.getString(2))
                    .put("title", cur.getString(3)).put("text", cur.getString(4)).put("ts", cur.getLong(5)))
            }
        }
        return JSONObject().put("items", items).put("more", more).put("total", total)
    }

    /** Aplikasi yang pernah mengirim notifikasi, yang terbaru dulu. */
    fun apps(c: Context): JSONArray {
        val arr = JSONArray()
        db(c).rawQuery("SELECT pkg, app, COUNT(*), MAX(ts) FROM n GROUP BY pkg ORDER BY MAX(ts) DESC", null).use { cur ->
            while (cur.moveToNext()) {
                arr.put(JSONObject().put("pkg", cur.getString(0)).put("app", cur.getString(1)).put("count", cur.getLong(2)).put("last", cur.getLong(3)))
            }
        }
        return arr
    }

    fun delete(c: Context, id: Long) { db(c).delete("n", "id = ?", arrayOf(id.toString())) }
    fun clear(c: Context) { db(c).delete("n", null, null) }
}
