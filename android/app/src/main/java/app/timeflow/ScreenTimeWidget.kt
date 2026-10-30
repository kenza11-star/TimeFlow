package app.timeflow

import android.app.AppOpsManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Widget layar utama: Waktu Layar (gaya kaca beku, latar blur dari GlassBg/GlassSlots seperti widget lain).
 * Data dari ScreenTimeRepo (UsageStatsManager, dihitung di perangkat). Ketuk batang hari untuk melihat rincian hari itu,
 * ↻ untuk memperbarui, tombol "Buka pengaturan" bila Akses penggunaan belum dinyalakan.
 *
 * Hemat daya: tidak ada polling. Pembaruan hanya dari (1) jadwal sistem tiap 30 menit (updatePeriodMillis), (2) tengah malam lewat
 * WidgetUtil.scheduleMidnight, (3) tombol ↻, (4) aplikasi dibuka, (5) izin baru diberikan. Semuanya memakai cache dan baru menghitung ulang
 * bila data hari ini sudah lebih dari 5 menit.
 */
class ScreenTimeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        render(ctx, ids)
        WidgetUtil.scheduleMidnight(ctx)
        CcAccessibilityService.glassNudge()
        val pr = goAsync()
        refreshAsync(ctx, false) { pr?.finish() }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        render(ctx, intArrayOf(id)); CcAccessibilityService.glassNudge()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SELECT -> {
                val idx = intent.getIntExtra("idx", -1)
                val s = ScreenTimeRepo.load(ctx)
                val d = s?.days?.getOrNull(idx)
                // hari yang belum terjadi tidak bisa dipilih
                if (d != null && d.key <= ScreenTimeRepo.keyOf(System.currentTimeMillis())) { ScreenTimeRepo.select(ctx, d.key); render(ctx) }
            }
            ACTION_REFRESH -> {
                val pr = goAsync()
                render(ctx)                                    // segera tampilkan keadaan terbaru (mis. izin baru dinyalakan)
                refreshAsync(ctx, true) { pr?.finish() }
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    override fun onDisabled(ctx: Context) {
        WidgetUtil.scheduleMidnight(ctx); GlassSlots.invalidate()
        ScreenTimeRepo.clear(ctx)                              // widget terakhir dihapus: buang data penggunaan yang tersimpan
    }

    companion object {
        const val ACTION_SELECT = "app.timeflow.action.ST_SELECT"
        const val ACTION_REFRESH = "app.timeflow.action.ST_REFRESH"

        private val DAYS_SHORT = arrayOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")
        private val MONTHS_SHORT = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
        private val TARGETS = intArrayOf(R.id.st_d0, R.id.st_d1, R.id.st_d2, R.id.st_d3, R.id.st_d4, R.id.st_d5, R.id.st_d6)
        private val ROWS = intArrayOf(R.id.st_ar0, R.id.st_ar1, R.id.st_ar2, R.id.st_ar3, R.id.st_ar4)
        private val ICONS = intArrayOf(R.id.st_ai0, R.id.st_ai1, R.id.st_ai2, R.id.st_ai3, R.id.st_ai4)
        private val NAMES = intArrayOf(R.id.st_an0, R.id.st_an1, R.id.st_an2, R.id.st_an3, R.id.st_an4)
        private val TIMES = intArrayOf(R.id.st_at0, R.id.st_at1, R.id.st_at2, R.id.st_at3, R.id.st_at4)
        private val BARS = intArrayOf(R.id.st_ab0, R.id.st_ab1, R.id.st_ab2, R.id.st_ab3, R.id.st_ab4)
        private const val CHART_H_DP = 62

        private fun ids(ctx: Context): IntArray =
            runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ScreenTimeWidget::class.java)) }.getOrDefault(IntArray(0))

        fun has(ctx: Context) = ids(ctx).isNotEmpty()

        private fun vis(rv: RemoteViews, id: Int, on: Boolean) = rv.setViewVisibility(id, if (on) View.VISIBLE else View.GONE)

        private fun dims(mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
            val o: Bundle? = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val w = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val h = o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            return (if (w > 0) w else defW) to (if (h > 0) h else defH)
        }

        // ---------- pembaruan data ----------
        private val busy = AtomicBoolean(false)

        /**
         * Hitung ulang data di thread terpisah (aman dipanggil dari onUpdate/onReceive dengan goAsync), lalu gambar ulang widget.
         * force = false: lewati bila data masih segar. Tanpa izin: data tersimpan dibuang dan widget menampilkan ajakan menyalakan izin.
         */
        fun refreshAsync(ctx: Context, force: Boolean, done: () -> Unit = {}) {
            val app = ctx.applicationContext
            if (!has(app)) { done(); return }
            if (!ScreenTimeRepo.hasAccess(app)) {
                ScreenTimeRepo.clear(app); render(app); done(); return
            }
            val prev = ScreenTimeRepo.load(app)
            if (!force && !ScreenTimeRepo.isStale(prev)) { done(); return }
            if (!busy.compareAndSet(false, true)) { done(); return }
            Thread {
                try {
                    ScreenTimeRepo.compute(app, prev)
                    render(app)
                } catch (_: Exception) {
                } finally {
                    busy.set(false)
                    done()
                }
            }.start()
        }

        private var watcher: AppOpsManager.OnOpChangedListener? = null

        /** Pantau perubahan izin Akses penggunaan selama proses aplikasi hidup: begitu dinyalakan, widget langsung terisi. */
        fun watchAccess(ctx: Context) {
            if (watcher != null) return
            val app = ctx.applicationContext
            val ops = app.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return
            val l = AppOpsManager.OnOpChangedListener { _, _ -> if (has(app)) refreshAsync(app, true) }
            runCatching { ops.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, app.packageName, l); watcher = l }
        }

        // ---------- gambar ----------
        fun render(ctx: Context, only: IntArray? = null) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val all = only ?: ids(ctx)
            if (all.isEmpty()) return
            val snap = ScreenTimeRepo.load(ctx)
            val st = ScreenTimeRepo.state(ctx, snap)
            for (id in all) runCatching { mgr.updateAppWidget(id, build(ctx, mgr, id, snap, st)) }
        }

        private fun pi(ctx: Context, action: String, code: Int, idx: Int = -1): PendingIntent = PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ScreenTimeWidget::class.java).setAction(action).setData(Uri.parse("timeflow://screentime/$action/$idx")).putExtra("idx", idx),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun dayLabel(key: String, idx: Int, today: Boolean): String {
            if (today) return "Hari ini"
            val m = key.substring(5, 7).toIntOrNull() ?: 1
            val d = key.substring(8, 10).toIntOrNull() ?: 1
            return DAYS_SHORT[idx.coerceIn(0, 6)] + ", " + d + " " + MONTHS_SHORT[(m - 1).coerceIn(0, 11)]
        }

        private fun build(ctx: Context, mgr: AppWidgetManager, id: Int, snap: ScreenTimeRepo.Snapshot?, st: ScreenTimeRepo.State): RemoteViews {
            val rv = WidgetUtil.base(ctx, GlassSlots.SCREENTIME)
            val d = ctx.resources.displayMetrics.density
            val (w, h) = dims(mgr, id, 250, 250)
            val pad = if (h < 150) 10 else 14
            rv.setViewPadding(R.id.gc_screentime, (pad * d).toInt(), (pad * d).toInt(), (pad * d).toInt(), (pad * d).toInt())
            rv.setOnClickPendingIntent(R.id.st_refresh, pi(ctx, ACTION_REFRESH, 30))

            val ok = st == ScreenTimeRepo.State.OK && snap != null
            vis(rv, R.id.st_totalrow, ok); vis(rv, R.id.st_apps, ok)
            vis(rv, R.id.st_empty, !ok)
            if (!ok) {
                vis(rv, R.id.st_chartbox, false)
                rv.setTextViewText(R.id.st_date, "")
                rv.setTextViewText(R.id.st_msg, when (st) {
                    ScreenTimeRepo.State.NO_ACCESS -> "Nyalakan Akses penggunaan agar TimeFlow bisa menampilkan waktu layar. Data hanya diproses di perangkat ini."
                    ScreenTimeRepo.State.UNSUPPORTED -> "Data penggunaan tidak tersedia di perangkat ini."
                    ScreenTimeRepo.State.LOADING -> "Memuat data penggunaan…"
                    else -> "Belum ada data penggunaan."
                })
                val needGrant = st == ScreenTimeRepo.State.NO_ACCESS
                vis(rv, R.id.st_grant, needGrant)
                if (needGrant) rv.setOnClickPendingIntent(R.id.st_grant, PendingIntent.getActivity(
                    ctx, 31, ScreenTimeRepo.settingsIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                return rv
            }

            val days = snap!!.days
            val todayKey = ScreenTimeRepo.keyOf(System.currentTimeMillis())
            val todayIdx = days.indexOfFirst { it.key == todayKey }.let { if (it < 0) days.lastIndex else it }
            val selIdx = ScreenTimeRepo.selectedKey(ctx).let { k -> days.indexOfFirst { it.key == k } }.takeIf { it in 0..todayIdx } ?: todayIdx
            val sel = days[selIdx]

            rv.setTextViewText(R.id.st_date, dayLabel(sel.key, selIdx, selIdx == todayIdx))
            rv.setTextViewTextSize(R.id.st_total, TypedValue.COMPLEX_UNIT_SP, if (h < 150) 26f else 30f)
            rv.setTextViewText(R.id.st_total, ScreenTimeRepo.fmt(sel.total))
            rv.setTextViewText(R.id.st_today, if (selIdx != todayIdx) "Hari ini · " + ScreenTimeRepo.fmt(days[todayIdx].total) else "")

            // grafik 7 hari + area ketuk
            val showChart = h >= 118
            vis(rv, R.id.st_chartbox, showChart)
            if (showChart) {
                val cw = max(120, w - 2 * pad)
                rv.setImageViewBitmap(R.id.st_chart, chart(ctx, days, selIdx, todayIdx, cw, CHART_H_DP))
                for (i in 0..6) {
                    val on = i <= todayIdx
                    if (on) rv.setOnClickPendingIntent(TARGETS[i], pi(ctx, ACTION_SELECT, 40 + i, i))
                    rv.setCharSequence(TARGETS[i], "setContentDescription", DAYS_SHORT[i] + ": " + ScreenTimeRepo.fmt(days[i].total))
                }
            }

            // aplikasi teratas, jumlah baris menurut tinggi widget
            val fixed = 2 * pad + 18 + 34 + (if (showChart) CHART_H_DP + 6 else 0) + 8
            val n = if (!showChart) 0 else ((h - fixed) / 36).coerceIn(0, 5)
            val apps = sel.apps.take(n)
            val top = (apps.firstOrNull()?.ms ?: 1L).coerceAtLeast(1L)
            vis(rv, R.id.st_apps, apps.isNotEmpty())
            for (i in 0..4) {
                val a = apps.getOrNull(i)
                vis(rv, ROWS[i], a != null)
                if (a == null) continue
                rv.setImageViewBitmap(ICONS[i], icon(ctx, a.pkg))
                rv.setTextViewText(NAMES[i], label(ctx, a.pkg))
                rv.setTextViewText(TIMES[i], ScreenTimeRepo.fmt(a.ms))
                rv.setProgressBar(BARS[i], 1000, (a.ms * 1000 / top).toInt().coerceIn(0, 1000), false)
            }
            return rv
        }

        // ---------- ikon & nama aplikasi (cache kecil di memori) ----------
        private val labels = ConcurrentHashMap<String, String>()
        private val icons = object : LruCache<String, Bitmap>(1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        private fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
            runCatching { val pm = ctx.packageManager; pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
        }

        private fun icon(ctx: Context, pkg: String): Bitmap {
            icons.get(pkg)?.let { return it }
            val px = 96
            val dr = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: ctx.packageManager.defaultActivityIcon
            val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            runCatching { dr.setBounds(0, 0, px, px); dr.draw(Canvas(b)) }
            icons.put(pkg, b)
            return b
        }

        // ---------- grafik batang Minggu..Sabtu ----------
        private fun chart(ctx: Context, days: List<ScreenTimeRepo.Day>, sel: Int, today: Int, wDp: Int, hDp: Int): Bitmap {
            val s = min(min(ctx.resources.displayMetrics.density, 2.5f), 720f / wDp)       // batasi piksel supaya ringan bagi RemoteViews
            val bw = max(2, (wDp * s).toInt()); val bh = max(2, (hDp * s).toInt())
            val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val colW = bw / 7f
            val labelH = 16 * s
            val areaTop = 2 * s
            val areaBottom = bh - labelH - 2 * s
            val areaH = areaBottom - areaTop
            val barW = min(colW * 0.46f, 20 * s)
            val maxMs = max(days.maxOf { it.total }, 30 * 60_000L).toFloat()      // sumbu minimal 30 menit supaya hari sepi tidak tampak penuh

            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
            val bar = Paint(Paint.ANTI_ALIAS_FLAG)
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 10.5f * s }

            for (i in 0..6) {
                val cx = colW * i + colW / 2f
                val l = cx - barW / 2f; val r = cx + barW / 2f
                c.drawRoundRect(RectF(l, areaTop, r, areaBottom), barW / 2f, barW / 2f, track)        // jalur latar
                if (i <= today) {
                    val hh = max(barW.coerceAtMost(5 * s).coerceAtLeast(3 * s), areaH * (days[i].total / maxMs))
                    bar.color = when { i == sel -> 0xFFFFFFFF.toInt(); i == today -> 0xB3FFFFFF.toInt(); else -> 0x66FFFFFF }
                    val rad = min(barW / 2f, hh / 2f)
                    c.drawRoundRect(RectF(l, areaBottom - hh, r, areaBottom), rad, rad, bar)
                }
                txt.color = if (i == sel) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt()
                txt.typeface = if (i == sel || i == today) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
                c.drawText(DAYS_SHORT[i], cx, bh - 3 * s, txt)
            }
            return out
        }
    }
}
