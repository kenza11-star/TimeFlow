package app.timeflow

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/** Satu isi layar utama peluncur: aplikasi, folder (berisi beberapa aplikasi), atau widget. cx/cy = sel, sx/sy = ukuran dalam sel. */
class HItem(
    var type: Int,
    var cx: Int = 0,
    var cy: Int = 0,
    var sx: Int = 2,
    var sy: Int = 2,
    var pkg: String = "",
    var name: String = "",
    var apps: MutableList<String> = ArrayList(),
    var wid: Int = -1
) {
    companion object {
        const val APP = 0
        const val FOLDER = 1
        const val WIDGET = 2
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("t", type); o.put("x", cx); o.put("y", cy); o.put("w", sx); o.put("h", sy)
        o.put("p", pkg); o.put("n", name); o.put("i", wid)
        val a = JSONArray()
        for (s in apps) a.put(s)
        o.put("a", a)
        return o
    }
}

fun hItemFrom(o: JSONObject): HItem {
    val a = o.optJSONArray("a") ?: JSONArray()
    val l = ArrayList<String>()
    for (i in 0 until a.length()) l.add(a.getString(i))
    return HItem(o.optInt("t"), o.optInt("x"), o.optInt("y"), o.optInt("w", 2), o.optInt("h", 2), o.optString("p"), o.optString("n"), l, o.optInt("i", -1))
}

/** Tata letak peluncur (halaman + dock), disimpan sebagai JSON di SharedPreferences. Hanya diakses dari thread utama. */
object HomeStore {
    /** Grid halus: tiap sel layar = 2x2 sub-sel. Ikon/folder menempati 2x2; widget bebas per sub-sel (setengah sel). */
    const val COLS = 8
    const val ROWS = 12
    const val DOCK = 4
    const val MAX_PAGES = 16
    private const val SP = "timeflow_home"

    val pages = ArrayList<ArrayList<HItem>>()
    val dock = ArrayList<HItem>()
    private var loaded = false
    private var idSeq = 0
    /** true bila halaman pengaturan folder mengubah tata letak (peluncur membangun ulang saat kembali). */
    @Volatile var dirty = false

    fun nextId(): Int { idSeq++; return (System.currentTimeMillis() % 1_000_000_000L).toInt() + idSeq }

    fun findFolder(id: Int): HItem? = all().firstOrNull { it.type == HItem.FOLDER && it.wid == id }

    private fun sp(c: Context) = c.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        pages.clear(); dock.clear()
        val raw = sp(c).getString("layout", null)
        var migrated = false
        if (raw != null) {
            runCatching {
                val o = JSONObject(raw)
                val pa = o.getJSONArray("pages")
                for (i in 0 until pa.length()) {
                    val arr = pa.getJSONArray(i)
                    val l = ArrayList<HItem>()
                    for (j in 0 until arr.length()) l.add(hItemFrom(arr.getJSONObject(j)))
                    pages.add(l)
                }
                val da = o.getJSONArray("dock")
                for (j in 0 until da.length()) dock.add(hItemFrom(da.getJSONObject(j)))
                if (o.optInt("v", 1) < 2) {
                    // tata letak lama (sel penuh) -> sub-sel
                    for (pg in pages) for (x in pg) { x.cx *= 2; x.cy *= 2; x.sx *= 2; x.sy *= 2 }
                    for (x in dock) { x.sx = 2; x.sy = 2 }
                    migrated = true
                }
            }
        } else firstRun(c)
        if (pages.isEmpty()) pages.add(ArrayList())
        for (x in all()) if (x.type == HItem.FOLDER && x.wid < 0) x.wid = nextId()
        if (migrated) save(c)
    }

    /** Pertama kali: dock diisi aplikasi telepon, pesan, browser, kamera bawaan HP (bila ada). */
    private fun firstRun(c: Context) {
        pages.add(ArrayList())
        val pm = c.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        for (i in intents) {
            if (dock.size >= DOCK) break
            val pkg = runCatching { pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull() ?: continue
            if (pkg == "android" || dock.any { it.pkg == pkg } || pm.getLaunchIntentForPackage(pkg) == null) continue
            dock.add(HItem(HItem.APP, cx = dock.size, pkg = pkg))
        }
        fillAll(c, 4)          // layar utama langsung terisi aplikasi (maks 4 halaman), sisanya ada di laci
        save(c)
    }

    /** Susun semua aplikasi yang belum ada di layar ke halaman-halaman (urut abjad), maksimal [maxPages] halaman. Mengembalikan jumlah yang ditambahkan. */
    fun fillAll(c: Context, maxPages: Int): Int {
        val placed = HashSet<String>()
        for (x in all()) {
            if (x.type == HItem.APP) placed.add(x.pkg)
            else if (x.type == HItem.FOLDER) placed.addAll(x.apps)
        }
        var added = 0
        for (e in AppRepo.visible(c)) {
            if (e.pkg in placed) continue
            val item = HItem(HItem.APP, pkg = e.pkg)
            val p = placeNear(item, 0, 0, 0)
            if (p < 0) break
            if (p >= maxPages) { pages[p].remove(item); break }
            added++
        }
        trim()
        return added
    }

    fun save(c: Context) {
        val o = JSONObject()
        val pa = JSONArray()
        for (p in pages) {
            val arr = JSONArray()
            for (x in p) arr.put(x.toJson())
            pa.put(arr)
        }
        o.put("v", 2)
        o.put("pages", pa)
        val da = JSONArray()
        for (x in dock) da.put(x.toJson())
        o.put("dock", da)
        sp(c).edit().putString("layout", o.toString()).apply()
    }

    fun all(): List<HItem> {
        val l = ArrayList<HItem>()
        for (p in pages) l.addAll(p)
        l.addAll(dock)
        return l
    }

    /** Ganti seluruh tata letak (pemulihan cadangan). */
    fun replace(np: List<List<HItem>>, nd: List<HItem>) {
        pages.clear(); dock.clear()
        for (p in np) pages.add(ArrayList<HItem>(p))
        dock.addAll(nd)
        if (pages.isEmpty()) pages.add(ArrayList())
        for (x in all()) if (x.type == HItem.FOLDER && x.wid < 0) x.wid = nextId()
    }

    /** Lepas item dari halaman/dock (bukan dari dalam folder). */
    fun remove(item: HItem) {
        for (p in pages) p.remove(item)
        dock.remove(item)
    }

    /** Buang halaman kosong di ujung (sisakan satu). */
    fun trim() {
        while (pages.size > 1 && pages[pages.size - 1].isEmpty()) pages.removeAt(pages.size - 1)
    }

    fun cellFree(list: List<HItem>, cx: Int, cy: Int, sx: Int, sy: Int, ignore: HItem?): Boolean {
        if (cx < 0 || cy < 0 || cx + sx > COLS || cy + sy > ROWS) return false
        for (o in list) {
            if (o === ignore) continue
            if (cx < o.cx + o.sx && cx + sx > o.cx && cy < o.cy + o.sy && cy + sy > o.cy) return false
        }
        return true
    }

    private fun stepOf(item: HItem) = if (item.type == HItem.WIDGET || item.sx > 2 || item.sy > 2) 1 else 2

    /** Cari tempat kosong terdekat dari (cx, cy) mulai halaman [p0]: [halaman, kolom, baris], atau null bila semua penuh. Tidak mengubah apa pun. */
    fun findSpot(item: HItem, p0: Int, cx: Int, cy: Int): IntArray? {
        val step = stepOf(item)
        var p = p0
        while (p < MAX_PAGES) {
            val list: List<HItem> = if (p < pages.size) pages[p] else emptyList()
            var best = -1
            var bd = Int.MAX_VALUE
            var y = 0
            while (y <= ROWS - item.sy) {
                var x = 0
                while (x <= COLS - item.sx) {
                    if (cellFree(list, x, y, item.sx, item.sy, item)) {
                        val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                        if (d < bd) { bd = d; best = y * COLS + x }
                    }
                    x += step
                }
                y += step
            }
            if (best >= 0) return intArrayOf(p, best % COLS, best / COLS)
            p++
        }
        return null
    }

    /** Taruh [item] di sel kosong terdekat dari (cx, cy) mulai halaman [p0]; bila penuh lanjut ke halaman berikutnya. Mengembalikan indeks halaman, atau -1 bila semua penuh. */
    fun placeNear(item: HItem, p0: Int, cx: Int, cy: Int): Int {
        val sp = findSpot(item, p0, cx, cy) ?: return -1
        while (pages.size <= sp[0]) pages.add(ArrayList())
        item.cx = sp[1]; item.cy = sp[2]
        pages[sp[0]].add(item)
        return sp[0]
    }

    /** Taruh di dock pada slot [slot] (atau slot kosong terdekat). false bila dock penuh atau item bukan aplikasi/folder. */
    fun placeDock(item: HItem, slot: Int): Boolean {
        if (item.type == HItem.WIDGET) return false
        val taken = HashSet<Int>()
        for (d in dock) taken.add(d.cx)
        var s = slot
        if (s in taken) {
            var best = -1
            for (i in 0 until DOCK) {
                if (i in taken) continue
                if (best < 0 || Math.abs(i - slot) < Math.abs(best - slot)) best = i
            }
            if (best < 0) return false
            s = best
        }
        item.cx = s; item.cy = 0; item.sx = 2; item.sy = 2
        dock.add(item)
        return true
    }

    /** Folder dengan 1 aplikasi jadi ikon aplikasi biasa; folder kosong dihapus. */
    fun normalize(f: HItem) {
        if (f.type != HItem.FOLDER || f.apps.size > 1) return
        if (f.apps.isEmpty()) { remove(f); return }
        f.type = HItem.APP; f.pkg = f.apps[0]; f.apps.clear(); f.name = ""; f.sx = 2; f.sy = 2
    }

    /** Buang semua jejak aplikasi [pkg] (mis. setelah dicopot). true bila ada yang berubah. */
    fun purgePkg(pkg: String): Boolean {
        var changed = false
        val lists = ArrayList<MutableList<HItem>>()
        lists.addAll(pages); lists.add(dock)
        for (l in lists) {
            for (x in ArrayList<HItem>(l)) {
                if (x.type == HItem.APP && x.pkg == pkg) { l.remove(x); changed = true }
                else if (x.type == HItem.FOLDER && x.apps.remove(pkg)) { changed = true; normalize(x) }
            }
        }
        return changed
    }
}

class AppEntry(val pkg: String, val label: String)

/** Daftar aplikasi yang bisa dibuka (cache; dipanaskan di thread latar). */
object AppRepo {
    @Volatile private var cache: List<AppEntry>? = null

    fun list(ctx: Context): List<AppEntry> {
        val c = cache
        if (c != null) return c
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val l = runCatching { pm.queryIntentActivities(q, 0) }.getOrDefault(emptyList())
            .distinctBy { it.activityInfo.packageName }
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
        cache = l
        return l
    }

    fun refresh() { cache = null }

    /** Daftar aplikasi tanpa yang disembunyikan di Layar Tersembunyi (dipakai laci dan penyusunan layar utama). */
    fun visible(ctx: Context): List<AppEntry> {
        val hid = HiddenStore.all(ctx)
        if (hid.isEmpty()) return list(ctx)
        val hs = hid.toHashSet()
        return list(ctx).filter { it.pkg !in hs }
    }
}

object HomeUtil {
    /** TimeFlow sedang menjadi peluncur bawaan HP? */
    fun isDefaultHome(ctx: Context): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = runCatching { ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    fun uriOfPkg(pkg: String): Uri = Uri.parse("package:$pkg")

    @Volatile private var defAt = 0L
    @Volatile private var defVal = false

    /** Seperti [isDefaultHome] tetapi di-cache 5 detik (dipakai di jalur yang sering dipanggil: pembaruan widget, peristiwa aksesibilitas). */
    fun isDefaultHomeCached(ctx: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - defAt > 5000L) { defVal = isDefaultHome(ctx); defAt = now }
        return defVal
    }

    private var asked = false

    /** Minta Android menjadikan Flow Launcher launcher utama: dialog sistem (RoleManager), lalu cadangan lewat layar pengaturan. */
    fun requestHome(act: Activity, reqCode: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = act.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try { act.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), reqCode); return } catch (e: Exception) { }
            }
        }
        val tries = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        )
        for (i in tries) { try { act.startActivity(i); return } catch (e: Exception) { } }
    }

    /** Dialog "Jadikan launcher utama?" (sekali per proses; bisa dimatikan permanen). true bila ditampilkan. */
    fun promptDefault(act: Activity, reqCode: Int, onSkip: () -> Unit = {}): Boolean {
        val sp = act.getSharedPreferences("timeflow_home", Context.MODE_PRIVATE)
        if (asked || act.isFinishing || sp.getBoolean("no_default_ask", false) || isDefaultHome(act)) return false
        asked = true
        var chose = false
        FlowUi.dialog(
            act, "Jadikan Flow Launcher launcher utama?",
            "Supaya tombol Home membuka Flow Launcher dengan dock, folder, dan widget kaca. Kamu bisa kembali ke launcher lama kapan saja lewat Pengaturan.",
            listOf(
                "Jadikan bawaan" to { chose = true; requestHome(act, reqCode) },
                "Nanti" to {},
                "Jangan tanya lagi" to { sp.edit().putBoolean("no_default_ask", true).apply() }
            ), 0, { if (!chose) onSkip() })
        return true
    }
}
