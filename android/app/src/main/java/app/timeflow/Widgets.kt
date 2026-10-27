package app.timeflow

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Widget layar utama: Jam, Kalender, Cuaca, Agenda.
 * Tema (gelap/terang + warna aksen) dan data agenda dikirim dari tampilan web lewat bridge "widget.sync".
 * Widget tidak memakai internet sendiri kecuali Cuaca/Jam, yang memanggil WeatherRepo (koordinat tersimpan).
 */
object WidgetUtil {
    const val ACTION_TICK = "app.timeflow.action.WIDGET_TICK"
    const val ACTION_WX_REFRESH = "app.timeflow.action.WEATHER_REFRESH"
    private const val SP = "timeflow_widget"

    private val MONTHS = arrayOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")
    private val DAYS = arrayOf("Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu")
    private val ACC = intArrayOf(0xFF8E4B57.toInt(), 0xFFE8683E.toInt(), 0xFF8E91DE.toInt(), 0xFF86B070.toInt(), 0xFF5B8FC9.toInt())
    private val TODAY_BG = intArrayOf(R.drawable.widget_today_0, R.drawable.widget_today_1, R.drawable.widget_today_2, R.drawable.widget_today_3, R.drawable.widget_today_4)
    private val CELLS = intArrayOf(R.id.w_c0, R.id.w_c1, R.id.w_c2, R.id.w_c3, R.id.w_c4, R.id.w_c5, R.id.w_c6, R.id.w_c7, R.id.w_c8, R.id.w_c9, R.id.w_c10, R.id.w_c11, R.id.w_c12, R.id.w_c13, R.id.w_c14, R.id.w_c15, R.id.w_c16, R.id.w_c17, R.id.w_c18, R.id.w_c19, R.id.w_c20, R.id.w_c21, R.id.w_c22, R.id.w_c23, R.id.w_c24, R.id.w_c25, R.id.w_c26, R.id.w_c27, R.id.w_c28, R.id.w_c29, R.id.w_c30, R.id.w_c31, R.id.w_c32, R.id.w_c33, R.id.w_c34, R.id.w_c35, R.id.w_c36, R.id.w_c37, R.id.w_c38, R.id.w_c39, R.id.w_c40, R.id.w_c41)
    private val HDRS = intArrayOf(R.id.w_h0, R.id.w_h1, R.id.w_h2, R.id.w_h3, R.id.w_h4, R.id.w_h5, R.id.w_h6)
    private val EVS = intArrayOf(R.id.w_e1, R.id.w_e2, R.id.w_e3, R.id.w_e4)

    class Theme(val bg: Int, val ink: Int, val mut: Int, val acc: Int, val accIdx: Int)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ---------- data dari web ----------
    fun sync(ctx: Context): JSONObject? =
        sp(ctx).getString("sync", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun saveSync(ctx: Context, d: JSONObject?) {
        val e = sp(ctx).edit()
        if (d == null) e.remove("sync") else e.putString("sync", d.toString())
        e.apply()
    }

    fun theme(ctx: Context): Theme {
        val s = sync(ctx)
        val dark = s?.optBoolean("dark", false) ?: false
        val idx = (s?.optInt("acc", 0) ?: 0).coerceIn(0, ACC.size - 1)
        return if (dark) Theme(0xFF1F1E21.toInt(), 0xFFF2F0EC.toInt(), 0xFFA19DA5.toInt(), ACC[idx], idx)
        else Theme(0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt(), 0xFF77737A.toInt(), ACC[idx], idx)
    }

    // ---------- util ----------
    private fun dayKey(c: Calendar = Calendar.getInstance()): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    private fun deg(d: Double): String = "${Math.round(d)}°"

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 7,
        Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    private fun base(ctx: Context, layout: Int, t: Theme): RemoteViews {
        val rv = RemoteViews(ctx.packageName, layout)
        rv.setInt(R.id.w_bg, "setColorFilter", t.bg)
        rv.setOnClickPendingIntent(R.id.w_root, openApp(ctx))
        return rv
    }

    /** Pasang widget baru lewat dialog sistem (bila launcher mendukung). */
    fun pin(ctx: Context, what: String): Boolean {
        val cls: Class<*> = when (what) {
            "clock" -> ClockWidget::class.java
            "calendar" -> CalendarWidget::class.java
            "weather" -> WeatherWidget::class.java
            "agenda" -> AgendaWidget::class.java
            else -> throw IllegalArgumentException("Widget tidak dikenal")
        }
        val mgr = AppWidgetManager.getInstance(ctx)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return mgr.requestPinAppWidget(ComponentName(ctx, cls), null, null)
    }

    // ---------- pembaruan ----------
    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        ids(ctx, ClockWidget::class.java).let { if (it.isNotEmpty()) updateClock(ctx, mgr, it) }
        ids(ctx, CalendarWidget::class.java).let { if (it.isNotEmpty()) updateCalendar(ctx, mgr, it) }
        ids(ctx, WeatherWidget::class.java).let { if (it.isNotEmpty()) updateWeather(ctx, mgr, it) }
        ids(ctx, AgendaWidget::class.java).let { if (it.isNotEmpty()) updateAgenda(ctx, mgr, it) }
        scheduleMidnight(ctx)
    }

    private fun anyWidget(ctx: Context): Boolean =
        listOf(ClockWidget::class.java, CalendarWidget::class.java, WeatherWidget::class.java, AgendaWidget::class.java)
            .any { ids(ctx, it).isNotEmpty() }

    /** Segarkan tampilan tepat lewat tengah malam supaya kalender/agenda berganti hari. */
    fun scheduleMidnight(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 12, Intent(ctx, WidgetRefreshReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (!anyWidget(ctx)) { am.cancel(pi); return }
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 5); set(Calendar.MILLISECOND, 0)
        }
        am.set(AlarmManager.RTC, c.timeInMillis, pi)
    }

    /** Ambil cuaca di thread terpisah (aman dipanggil dari onUpdate dengan goAsync), lalu perbarui widget. */
    fun refreshWeather(ctx: Context, force: Boolean, done: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val changed = if (force) {
                    WeatherRepo.hasLocation(app) && runCatching { WeatherRepo.fetch(app); true }.getOrDefault(false)
                } else WeatherRepo.refreshIfStale(app)
                if (changed) updateAll(app)
            } catch (_: Exception) {
            } finally {
                done()
            }
        }.start()
    }

    fun weatherLine(ctx: Context): String {
        val c = WeatherRepo.cached(ctx) ?: return "Atur lokasi cuaca di aplikasi"
        val cur = c.optJSONObject("cur") ?: return ""
        val (txt, em) = WeatherRepo.describe(cur.optInt("code"), cur.optInt("day", 1) == 1)
        val city = c.optString("name")
        return "$em ${deg(cur.optDouble("t"))} · $txt" + if (city.isBlank()) "" else " · $city"
    }

    // ---------- Jam ----------
    fun updateClock(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val t = theme(ctx)
        val line = weatherLine(ctx)
        for (id in ids) {
            val rv = base(ctx, R.layout.widget_clock, t)
            rv.setTextColor(R.id.w_time, t.ink)
            rv.setTextColor(R.id.w_date, t.mut)
            rv.setTextColor(R.id.w_wx, t.acc)
            rv.setTextViewText(R.id.w_wx, line)
            mgr.updateAppWidget(id, rv)
        }
    }

    // ---------- Kalender ----------
    fun updateCalendar(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val t = theme(ctx)
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_MONTH)
        val first = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7      // Senin = 0
        val dim = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (id in ids) {
            val rv = base(ctx, R.layout.widget_calendar, t)
            rv.setTextViewText(R.id.w_month, MONTHS[now.get(Calendar.MONTH)] + " " + now.get(Calendar.YEAR))
            rv.setTextColor(R.id.w_month, t.ink)
            for (h in HDRS) rv.setTextColor(h, t.mut)
            for (i in CELLS.indices) {
                val d = i - offset + 1
                val cid = CELLS[i]
                if (d in 1..dim) {
                    rv.setTextViewText(cid, d.toString())
                    if (d == today) {
                        rv.setInt(cid, "setBackgroundResource", TODAY_BG[t.accIdx])
                        rv.setTextColor(cid, Color.WHITE)
                    } else {
                        rv.setInt(cid, "setBackgroundResource", 0)
                        rv.setTextColor(cid, t.ink)
                    }
                } else {
                    rv.setTextViewText(cid, "")
                    rv.setInt(cid, "setBackgroundResource", 0)
                }
            }
            mgr.updateAppWidget(id, rv)
        }
    }

    // ---------- Cuaca ----------
    fun updateWeather(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val t = theme(ctx)
        val c = WeatherRepo.cached(ctx)
        val cur = c?.optJSONObject("cur")
        val day = c?.optJSONObject("day")
        for (id in ids) {
            val rv = base(ctx, R.layout.widget_weather, t)
            rv.setTextColor(R.id.w_city, t.acc)
            rv.setTextColor(R.id.w_refresh, t.mut)
            rv.setTextColor(R.id.w_temp, t.ink)
            rv.setTextColor(R.id.w_cond, t.ink)
            rv.setTextColor(R.id.w_detail, t.mut)
            rv.setTextColor(R.id.w_hours, t.mut)
            rv.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(
                ctx, 11, Intent(ctx, WeatherWidget::class.java).setAction(ACTION_WX_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            if (c == null || cur == null || day == null) {
                rv.setTextViewText(R.id.w_city, "Cuaca")
                rv.setTextViewText(R.id.w_icon, "🌡️")
                rv.setTextViewText(R.id.w_temp, "--°")
                rv.setTextViewText(R.id.w_cond, "Belum ada data cuaca")
                rv.setTextViewText(R.id.w_detail, "Buka TimeFlow > Settings > Cuaca & widget")
                rv.setTextViewText(R.id.w_hours, "")
            } else {
                val (txt, em) = WeatherRepo.describe(cur.optInt("code"), cur.optInt("day", 1) == 1)
                val name = c.optString("name").ifBlank { "Lokasi saya" }
                rv.setTextViewText(R.id.w_city, name)
                rv.setTextViewText(R.id.w_icon, em)
                rv.setTextViewText(R.id.w_temp, deg(cur.optDouble("t")))
                rv.setTextViewText(R.id.w_cond, txt + " · terasa " + deg(cur.optDouble("feel")))
                rv.setTextViewText(R.id.w_detail, "↑" + deg(day.optDouble("max")) + "  ↓" + deg(day.optDouble("min")) + "  ·  Hujan " + day.optInt("pop") + "%")
                val hrs = c.optJSONArray("hours")
                val sb = StringBuilder()
                if (hrs != null) {
                    for (i in 1 until minOf(hrs.length(), 5)) {
                        val h = hrs.getJSONObject(i)
                        val hh = h.optString("h").take(2)
                        val night = (hh.toIntOrNull() ?: 12).let { it < 6 || it >= 18 }
                        if (sb.isNotEmpty()) sb.append("   ")
                        sb.append(hh).append(' ').append(WeatherRepo.describe(h.optInt("code"), !night).second).append(deg(h.optDouble("t")))
                    }
                }
                rv.setTextViewText(R.id.w_hours, sb.toString())
            }
            mgr.updateAppWidget(id, rv)
        }
    }

    // ---------- Agenda ----------
    fun updateAgenda(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val t = theme(ctx)
        val s = sync(ctx)
        val now = Calendar.getInstance()
        val title = "Hari ini · " + DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " + now.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[now.get(Calendar.MONTH)].take(3)
        val list = s?.optJSONObject("days")?.optJSONArray(dayKey(now))
        for (id in ids) {
            val rv = base(ctx, R.layout.widget_agenda, t)
            rv.setTextViewText(R.id.w_title, title)
            rv.setTextColor(R.id.w_title, t.acc)
            rv.setTextColor(R.id.w_more, t.mut)
            rv.setTextColor(R.id.w_tasks, t.mut)
            for (e in EVS) { rv.setViewVisibility(e, View.GONE); rv.setTextColor(e, t.ink) }
            rv.setViewVisibility(R.id.w_more, View.GONE)
            if (s == null || list == null) {
                rv.setViewVisibility(EVS[0], View.VISIBLE)
                rv.setTextViewText(EVS[0], "Buka TimeFlow untuk menyinkronkan agenda.")
                rv.setTextColor(EVS[0], t.mut)
            } else if (list.length() == 0) {
                rv.setViewVisibility(EVS[0], View.VISIBLE)
                rv.setTextViewText(EVS[0], "Tidak ada acara hari ini.")
                rv.setTextColor(EVS[0], t.mut)
            } else {
                val n = minOf(list.length(), EVS.size)
                for (i in 0 until n) {
                    val e = list.getJSONObject(i)
                    rv.setViewVisibility(EVS[i], View.VISIBLE)
                    rv.setTextViewText(EVS[i], e.optString("s") + "  " + e.optString("t"))
                }
                if (list.length() > n) {
                    rv.setViewVisibility(R.id.w_more, View.VISIBLE)
                    rv.setTextViewText(R.id.w_more, "+" + (list.length() - n) + " acara lagi")
                }
            }
            val open = s?.optInt("open", 0) ?: 0
            val al = s?.optInt("alarms", 0) ?: 0
            rv.setTextViewText(R.id.w_tasks, if (s == null) "" else "✓ $open tugas belum selesai  ·  $al alarm aktif")
            mgr.updateAppWidget(id, rv)
        }
    }
}

class ClockWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUtil.updateClock(ctx, mgr, ids)
        WidgetUtil.scheduleMidnight(ctx)
        val pr = goAsync()
        WidgetUtil.refreshWeather(ctx, false) { pr?.finish() }
    }
    override fun onDisabled(ctx: Context) { WidgetUtil.scheduleMidnight(ctx) }
}

class CalendarWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUtil.updateCalendar(ctx, mgr, ids)
        WidgetUtil.scheduleMidnight(ctx)
    }
    override fun onDisabled(ctx: Context) { WidgetUtil.scheduleMidnight(ctx) }
}

class AgendaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUtil.updateAgenda(ctx, mgr, ids)
        WidgetUtil.scheduleMidnight(ctx)
    }
    override fun onDisabled(ctx: Context) { WidgetUtil.scheduleMidnight(ctx) }
}

class WeatherWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUtil.updateWeather(ctx, mgr, ids)
        WidgetUtil.scheduleMidnight(ctx)
        val pr = goAsync()
        WidgetUtil.refreshWeather(ctx, false) { pr?.finish() }
    }
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == WidgetUtil.ACTION_WX_REFRESH) {
            val pr = goAsync()
            WidgetUtil.refreshWeather(ctx, true) { pr?.finish() }
        } else super.onReceive(ctx, intent)
    }
    override fun onDisabled(ctx: Context) { WidgetUtil.scheduleMidnight(ctx) }
}

/** Dipanggil sesaat setelah tengah malam: ganti tanggal di kalender/agenda dan segarkan cuaca bila sudah lama. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WidgetUtil.updateAll(ctx)
        val pr = goAsync()
        WidgetUtil.refreshWeather(ctx, false) { pr?.finish() }
    }
}
