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
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Widget layar utama: Jam, Kalender, Cuaca, Agenda (gaya kaca beku; latar blur dari GlassBg, sama dengan widget Musik).
 * Warna aksen dan data agenda dikirim dari tampilan web lewat bridge "widget.sync". Teks selalu putih di atas kaca gelap,
 * jadi tema gelap/terang aplikasi tidak lagi mengubah latar widget (aksen tetap dipakai untuk titik acara).
 * Tampilan menyesuaikan ukuran widget (tinggi/lebar dari opsi widget) lewat visibilitas bagian layout, bukan layout berbeda,
 * supaya pembaruan sebagian (menyembunyikan widget saat tangkapan layar) selalu cocok dengan layout yang tampil.
 * Widget tidak memakai internet sendiri kecuali Cuaca/Jam, yang memanggil WeatherRepo (koordinat tersimpan).
 */
object WidgetUtil {
    const val ACTION_TICK = "app.timeflow.action.WIDGET_TICK"
    const val ACTION_WX_REFRESH = "app.timeflow.action.WEATHER_REFRESH"
    const val ACTION_CAL_MODE = "app.timeflow.action.CAL_MODE"
    const val ACTION_GOTO = "app.timeflow.action.GOTO"
    private const val SP = "timeflow_widget"

    private val MONTHS = arrayOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli", "Agustus", "September", "Oktober", "November", "Desember")
    private val DAYS = arrayOf("Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu")
    private val DAYS_SHORT = arrayOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")
    private val WEEK_LETTERS = arrayOf("S", "S", "R", "K", "J", "S", "M")      // Senin ... Minggu
    private val ACC = intArrayOf(0xFF8E4B57.toInt(), 0xFFE8683E.toInt(), 0xFF8E91DE.toInt(), 0xFF86B070.toInt(), 0xFF5B8FC9.toInt())
    private val CELLS = intArrayOf(R.id.w_c0, R.id.w_c1, R.id.w_c2, R.id.w_c3, R.id.w_c4, R.id.w_c5, R.id.w_c6, R.id.w_c7, R.id.w_c8, R.id.w_c9, R.id.w_c10, R.id.w_c11, R.id.w_c12, R.id.w_c13, R.id.w_c14, R.id.w_c15, R.id.w_c16, R.id.w_c17, R.id.w_c18, R.id.w_c19, R.id.w_c20, R.id.w_c21, R.id.w_c22, R.id.w_c23, R.id.w_c24, R.id.w_c25, R.id.w_c26, R.id.w_c27, R.id.w_c28, R.id.w_c29, R.id.w_c30, R.id.w_c31, R.id.w_c32, R.id.w_c33, R.id.w_c34, R.id.w_c35, R.id.w_c36, R.id.w_c37, R.id.w_c38, R.id.w_c39, R.id.w_c40, R.id.w_c41)
    private val HDRS = intArrayOf(R.id.w_h0, R.id.w_h1, R.id.w_h2, R.id.w_h3, R.id.w_h4, R.id.w_h5, R.id.w_h6)
    private val WH = intArrayOf(R.id.wh0, R.id.wh1, R.id.wh2, R.id.wh3, R.id.wh4, R.id.wh5, R.id.wh6)
    private val WD = intArrayOf(R.id.wd0, R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6)
    private val WP = intArrayOf(R.id.wp0, R.id.wp1, R.id.wp2, R.id.wp3, R.id.wp4, R.id.wp5, R.id.wp6)
    private val ROWS = intArrayOf(R.id.w_r1, R.id.w_r2, R.id.w_r3, R.id.w_r4)
    private val ETIME = intArrayOf(R.id.w_t1, R.id.w_t2, R.id.w_t3, R.id.w_t4)
    private val EVS = intArrayOf(R.id.w_e1, R.id.w_e2, R.id.w_e3, R.id.w_e4)
    private val FC = intArrayOf(R.id.wx_fc0, R.id.wx_fc1, R.id.wx_fc2, R.id.wx_fc3, R.id.wx_fc4, R.id.wx_fc5, R.id.wx_fc6)
    private val FL = intArrayOf(R.id.wx_fl0, R.id.wx_fl1, R.id.wx_fl2, R.id.wx_fl3, R.id.wx_fl4, R.id.wx_fl5, R.id.wx_fl6)
    private val FI = intArrayOf(R.id.wx_fi0, R.id.wx_fi1, R.id.wx_fi2, R.id.wx_fi3, R.id.wx_fi4, R.id.wx_fi5, R.id.wx_fi6)
    private val FH = intArrayOf(R.id.wx_fh0, R.id.wx_fh1, R.id.wx_fh2, R.id.wx_fh3, R.id.wx_fh4, R.id.wx_fh5, R.id.wx_fh6)
    private val FN = intArrayOf(R.id.wx_fn0, R.id.wx_fn1, R.id.wx_fn2, R.id.wx_fn3, R.id.wx_fn4, R.id.wx_fn5, R.id.wx_fn6)

    private val WHITE = 0xFFFFFFFF.toInt()
    private val MUTED = 0xB3FFFFFF.toInt()
    private val DARK = 0xFF1C1C1E.toInt()

    /** Aksen aplikasi (untuk titik acara). Latar dan teks widget tidak lagi bergantung pada tema gelap/terang. */
    class Theme(val acc: Int, val accIdx: Int)

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
        val idx = (sync(ctx)?.optInt("acc", 0) ?: 0).coerceIn(0, ACC.size - 1)
        return Theme(ACC[idx], idx)
    }

    /** Pilihan tampilan kalender dari tombol Mingguan/Bulanan: "" = otomatis menurut ukuran, "week", atau "month". */
    fun calMode(ctx: Context): String = sp(ctx).getString("cal_mode", "") ?: ""
    fun setCalMode(ctx: Context, m: String) { sp(ctx).edit().putString("cal_mode", m).apply() }

    // ---------- util ----------
    private fun dayKey(c: Calendar = Calendar.getInstance()): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    private fun deg(d: Double): String = "${Math.round(d)}°"

    private fun lift(c: Int): Int = ColorUtils.blendARGB(c, Color.WHITE, 0.45f)   // aksen dibuat lebih terang agar terbaca di atas kaca gelap

    private fun vis(rv: RemoteViews, id: Int, on: Boolean) = rv.setViewVisibility(id, if (on) View.VISIBLE else View.GONE)

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 7,
        Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Buka aplikasi langsung ke form Acara Baru ("event") atau Pengingat ("alarm"). Data URI membuat tiap PendingIntent unik. */
    private fun gotoPi(ctx: Context, what: String, code: Int): PendingIntent = PendingIntent.getActivity(
        ctx, code,
        Intent(ctx, MainActivity::class.java).setAction(ACTION_GOTO).setData(Uri.parse("timeflow://goto/$what")).putExtra("goto", what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun calModePi(ctx: Context, mode: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, code,
        Intent(ctx, CalendarWidget::class.java).setAction(ACTION_CAL_MODE).setData(Uri.parse("timeflow://calmode/$mode")).putExtra("mode", mode),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun clsOf(name: String): Class<*> = when (name) {
        "clock" -> ClockWidget::class.java
        "calendar" -> CalendarWidget::class.java
        "weather" -> WeatherWidget::class.java
        "agenda" -> AgendaWidget::class.java
        "screentime" -> ScreenTimeWidget::class.java
        else -> throw IllegalArgumentException("Widget tidak dikenal")
    }

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    /** Ada widget jenis ini ("clock" | "calendar" | "weather" | "agenda") di layar utama? Dipakai GlassSlots. */
    fun has(ctx: Context, name: String): Boolean = runCatching { ids(ctx, clsOf(name)).isNotEmpty() }.getOrDefault(false)

    /** Lebar x tinggi widget dalam dp (opsi dari launcher); nilai bawaan dipakai bila launcher belum melaporkan. */
    private fun dims(mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o: Bundle? = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
        // potret: lebar = MIN_WIDTH, tinggi = MAX_HEIGHT (MIN_HEIGHT adalah tinggi saat layar miring, lebih kecil dari yang tampak)
        val land = android.content.res.Resources.getSystem().configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = o?.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val h = o?.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        return (if (w > 0) w else defW) to (if (h > 0) h else defH)
    }

    /** Dasar tiap widget kaca: klik membuka app, latar blur terakhir (atau kaca bawaan), dan tetap tersembunyi bila sedang ada tangkapan layar. */
    internal fun base(ctx: Context, d: GlassSlots.Def): RemoteViews {
        val rv = RemoteViews(ctx.packageName, d.layout)
        WidgetTheme.apply(ctx, rv, d)       // gaya kaca terang: warna teks/ikon gelap (gaya gelap: tidak berubah)
        rv.setOnClickPendingIntent(d.rootId, openApp(ctx))
        GlassBg.get(ctx, d.name)?.let { rv.setImageViewBitmap(d.glassId, it) }
        if (GlassBg.hidden) { rv.setViewVisibility(d.glassId, View.INVISIBLE); rv.setViewVisibility(d.contentId, View.INVISIBLE) }
        return rv
    }

    /** Pasang widget baru lewat dialog sistem (bila launcher mendukung). */
    fun pin(ctx: Context, what: String): Boolean {
        val cls: Class<*> = when (what) {
            "clock" -> ClockWidget::class.java
            "calendar" -> CalendarWidget::class.java
            "weather" -> WeatherWidget::class.java
            "agenda" -> AgendaWidget::class.java
            "screentime" -> ScreenTimeWidget::class.java
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
        ScreenTimeWidget.refreshAsync(ctx, false)      // hitung ulang hanya bila data sudah basi (mis. hari berganti); jika tidak, tidak melakukan apa-apa
        scheduleMidnight(ctx)
    }

    private fun anyWidget(ctx: Context): Boolean =
        listOf(ClockWidget::class.java, CalendarWidget::class.java, WeatherWidget::class.java, AgendaWidget::class.java, ScreenTimeWidget::class.java)
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
        val line = weatherLine(ctx)
        for (id in ids) {
            val (_, h) = dims(mgr, id, 180, 90)
            val rv = base(ctx, GlassSlots.CLOCK)
            rv.setTextViewTextSize(R.id.w_time, TypedValue.COMPLEX_UNIT_SP, if (h < 100) 38f else 52f)
            rv.setTextViewText(R.id.w_wx, line)
            vis(rv, R.id.w_wx, h >= 95 && line.isNotEmpty())
            mgr.updateAppWidget(id, rv)
        }
    }

    // ---------- Kalender (mingguan atau bulanan, menurut ukuran atau pilihan di tombol) ----------
    fun updateCalendar(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val L = WidgetTheme.light(ctx, GlassSlots.CALENDAR)
        val t = theme(ctx)
        val s = sync(ctx)
        val dot = if (L) t.acc else lift(t.acc)
        val now = Calendar.getInstance()
        val today = now.get(Calendar.DAY_OF_MONTH)
        val todayKey = dayKey(now)
        val pref = calMode(ctx)
        val first = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7      // Senin = 0
        val dim = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val mon = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7)) }   // Senin minggu ini
        val evDays = s?.optJSONObject("days")
        for (id in ids) {
            val (_, h) = dims(mgr, id, 250, 250)
            val canMonth = h >= 240
            val month = canMonth && (pref == "month" || (pref.isEmpty() && h >= 240))
            val rv = base(ctx, GlassSlots.CALENDAR)

            // tombol Mingguan / Bulanan (hanya bila cukup tinggi untuk dua tampilan)
            vis(rv, R.id.cal_pills, canMonth)
            if (canMonth) {
                rv.setInt(R.id.cal_pw, "setBackgroundResource", WidgetTheme.dr(L, if (month) R.drawable.gw_pill_off else R.drawable.gw_pill_on))
                rv.setTextColor(R.id.cal_pw, if (month) WidgetTheme.mu(L) else WidgetTheme.inv(L))
                rv.setInt(R.id.cal_pm, "setBackgroundResource", WidgetTheme.dr(L, if (month) R.drawable.gw_pill_on else R.drawable.gw_pill_off))
                rv.setTextColor(R.id.cal_pm, if (month) WidgetTheme.inv(L) else WidgetTheme.mu(L))
                rv.setOnClickPendingIntent(R.id.cal_pw, calModePi(ctx, "week", 21))
                rv.setOnClickPendingIntent(R.id.cal_pm, calModePi(ctx, "month", 22))
            }

            // bulan besar + tanggal terpilih (hari ini)
            val m = now.get(Calendar.MONTH)
            rv.setTextViewText(R.id.cal_month, if (month) MONTHS[m] + " " + now.get(Calendar.YEAR) else MONTHS[m])
            rv.setTextViewText(R.id.cal_day, today.toString())
            val ts = if (month) 26f else if (h < 130) 24f else 34f
            rv.setTextViewTextSize(R.id.cal_month, TypedValue.COMPLEX_UNIT_SP, ts)
            rv.setTextViewTextSize(R.id.cal_day, TypedValue.COMPLEX_UNIT_SP, ts)

            vis(rv, R.id.cal_week, !month)
            vis(rv, R.id.cal_month_box, month)
            vis(rv, R.id.cal_sp, !month)

            if (!month) {
                // baris 7 hari: huruf hari, tanggal (hari ini = pil putih), titik bila ada acara (data agenda tersinkron 7 hari ke depan)
                for (i in 0..6) {
                    val c = (mon.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
                    val isToday = dayKey(c) == todayKey
                    rv.setTextViewText(WH[i], WEEK_LETTERS[i])
                    rv.setTextViewText(WD[i], c.get(Calendar.DAY_OF_MONTH).toString())
                    rv.setInt(WD[i], "setBackgroundResource", WidgetTheme.dr(L, if (isToday) R.drawable.gw_sel else 0))
                    rv.setTextColor(WD[i], if (isToday) WidgetTheme.inv(L) else WidgetTheme.fg(L))
                    val has = (evDays?.optJSONArray(dayKey(c))?.length() ?: 0) > 0
                    rv.setTextColor(WP[i], if (has) dot else Color.TRANSPARENT)
                }
            } else {
                for (hd in HDRS) rv.setTextColor(hd, WidgetTheme.mu(L))
                for (i in CELLS.indices) {
                    val d = i - offset + 1
                    val cid = CELLS[i]
                    if (d in 1..dim) {
                        rv.setTextViewText(cid, d.toString())
                        if (d == today) {
                            rv.setInt(cid, "setBackgroundResource", WidgetTheme.dr(L, R.drawable.gw_sel))
                            rv.setTextColor(cid, WidgetTheme.inv(L))
                        } else {
                            rv.setInt(cid, "setBackgroundResource", 0)
                            rv.setTextColor(cid, WidgetTheme.fg(L))
                        }
                    } else {
                        rv.setTextViewText(cid, "")
                        rv.setInt(cid, "setBackgroundResource", 0)
                    }
                }
            }

            // pintasan: Tambah Pengingat (form alarm) dan Acara Baru (form acara)
            val actions = if (month) h >= 300 else if (canMonth) h >= 230 else h >= 185
            vis(rv, R.id.cal_actions, actions)
            if (actions) {
                rv.setOnClickPendingIntent(R.id.cal_rem, gotoPi(ctx, "alarm", 23))
                rv.setOnClickPendingIntent(R.id.cal_new, gotoPi(ctx, "event", 24))
            }
            mgr.updateAppWidget(id, rv)
        }
    }

    // ---------- Cuaca ----------
    private fun dow(date: String): String = runCatching {
        val p = date.split("-")
        val c = Calendar.getInstance(); c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
        DAYS_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1]
    }.getOrDefault("")

    private fun minutes(hhmm: String): Int? = runCatching { val p = hhmm.split(":"); p[0].toInt() * 60 + p[1].toInt() }.getOrNull()

    fun updateWeather(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val L = WidgetTheme.light(ctx, GlassSlots.WEATHER)
        val c = WeatherRepo.cached(ctx)
        val cur = c?.optJSONObject("cur")
        val day = c?.optJSONObject("day")
        val days = c?.optJSONArray("days")
        for (id in ids) {
            val (w, h) = dims(mgr, id, 250, 110)
            // level isi menurut tinggi: 0 = ringkas (jam ke depan), 1 = + prakiraan harian, 2 = + lembap/angin, 3 = + matahari & hujan
            val lvl = when { h < 190 -> 0; h < 215 -> 1; h < 300 -> 2; else -> 3 }
            val n = when { w >= 300 -> 7; w >= 240 -> 5; w >= 170 -> 4; else -> 3 }
            val narrow = w < 170
            val rv = base(ctx, GlassSlots.WEATHER)
            if (narrow) { rv.setTextViewTextSize(R.id.w_temp, android.util.TypedValue.COMPLEX_UNIT_SP, 30f); rv.setTextViewTextSize(R.id.w_icon, android.util.TypedValue.COMPLEX_UNIT_SP, 30f) }
            vis(rv, R.id.w_cond, !narrow); vis(rv, R.id.w_detail, !narrow)
            rv.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(
                ctx, 11, Intent(ctx, WeatherWidget::class.java).setAction(ACTION_WX_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val hasDays = days != null && days.length() > 0
            if (c == null || cur == null || day == null) {
                rv.setTextViewText(R.id.w_city, "Cuaca")
                rv.setTextViewText(R.id.w_icon, "🌡️")
                rv.setTextViewText(R.id.w_temp, "--°")
                rv.setTextViewText(R.id.w_cond, "Belum ada data cuaca")
                rv.setTextViewText(R.id.w_detail, "Buka TimeFlow > Settings > Cuaca & widget")
                rv.setTextViewText(R.id.w_hours, "")
                vis(rv, R.id.w_hours, true)
                vis(rv, R.id.wx_sun, false); vis(rv, R.id.wx_rain, false); vis(rv, R.id.wx_hw, false); vis(rv, R.id.wx_fc, false)
            } else {
                val (txt, em) = WeatherRepo.describe(cur.optInt("code"), cur.optInt("day", 1) == 1)
                val name = c.optString("name").ifBlank { "Lokasi saya" }
                val pop = day.optInt("pop")
                rv.setTextViewText(R.id.w_city, name)
                rv.setTextViewText(R.id.w_icon, em)
                rv.setTextViewText(R.id.w_temp, deg(cur.optDouble("t")))
                rv.setTextViewText(R.id.w_cond, txt + if (w >= 300) " · terasa " + deg(cur.optDouble("feel")) else "")
                rv.setTextViewText(R.id.w_detail, "↑" + deg(day.optDouble("max")) + "  ↓" + deg(day.optDouble("min")) + if (lvl < 3) "  ·  Hujan $pop%" else "")

                // jam ke depan: hanya untuk tampilan ringkas (atau bila data harian belum ada)
                val sb = StringBuilder()
                val hrs = c.optJSONArray("hours")
                if (hrs != null) {
                    if (narrow) sb.append(txt).append("  ").append("↑").append(deg(day.optDouble("max"))).append(" ↓").append(deg(day.optDouble("min")))
                    else for (i in 1 until minOf(hrs.length(), 5)) {
                        val hr = hrs.getJSONObject(i)
                        val hh = hr.optString("h").take(2)
                        val night = (hh.toIntOrNull() ?: 12).let { it < 6 || it >= 18 }
                        if (sb.isNotEmpty()) sb.append("   ")
                        sb.append(hh).append(' ').append(WeatherRepo.describe(hr.optInt("code"), !night).second).append(deg(hr.optDouble("t")))
                    }
                }
                rv.setTextViewText(R.id.w_hours, sb.toString())
                vis(rv, R.id.w_hours, lvl == 0 || !hasDays || narrow)

                // matahari terbit/terbenam + lama siang, dan peluang hujan
                val sr = day.optString("sunrise"); val ss = day.optString("sunset")
                val a = minutes(sr); val b = minutes(ss)
                vis(rv, R.id.wx_sun, lvl == 3 && a != null && b != null)
                if (lvl == 3 && a != null && b != null) {
                    val len = (b - a).coerceAtLeast(0)
                    rv.setTextViewText(R.id.w_sr, sr); rv.setTextViewText(R.id.w_ss, ss)
                    rv.setTextViewText(R.id.w_dur, "${len / 60} j ${len % 60} m")
                }
                vis(rv, R.id.wx_rain, lvl == 3)
                if (lvl == 3) rv.setTextViewText(R.id.wx_rain, "\uD83C\uDF27\uFE0F  Hujan: $pop%")

                // kelembapan + angin
                vis(rv, R.id.wx_hw, lvl >= 2)
                if (lvl >= 2) {
                    rv.setTextViewText(R.id.w_hum, "Lembap: " + cur.optInt("hum") + "%")
                    rv.setTextViewText(R.id.w_wind, "Angin: " + Math.round(cur.optDouble("wind")) + " km/j")
                }

                // prakiraan beberapa hari (hari ini disorot)
                vis(rv, R.id.wx_fc, lvl >= 1 && hasDays)
                if (lvl >= 1 && hasDays && days != null) {
                    for (i in 0..6) {
                        val on = i < n && i < days.length()
                        vis(rv, FC[i], on)
                        if (!on) continue
                        val dd = days.getJSONObject(i)
                        rv.setTextViewText(FL[i], if (i == 0) "Kini" else dow(dd.optString("d")))
                        rv.setTextViewText(FI[i], WeatherRepo.describe(dd.optInt("code"), true).second)
                        rv.setTextViewText(FH[i], deg(dd.optDouble("max")))
                        rv.setTextViewText(FN[i], deg(dd.optDouble("min")))
                        rv.setInt(FC[i], "setBackgroundResource", WidgetTheme.dr(L, if (i == 0) R.drawable.gw_col_on else R.drawable.gw_col))
                    }
                }
            }
            mgr.updateAppWidget(id, rv)
        }
    }

    // ---------- Agenda ----------
    fun updateAgenda(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val L = WidgetTheme.light(ctx, GlassSlots.AGENDA)
        val s = sync(ctx)
        val now = Calendar.getInstance()
        val title = "Hari ini · " + DAYS[now.get(Calendar.DAY_OF_WEEK) - 1] + ", " + now.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[now.get(Calendar.MONTH)].take(3)
        val list = s?.optJSONObject("days")?.optJSONArray(dayKey(now))
        for (id in ids) {
            val (_, h) = dims(mgr, id, 250, 180)
            val maxRows = if (h < 120) 2 else if (h < 160) 3 else 4
            val rv = base(ctx, GlassSlots.AGENDA)
            rv.setTextViewText(R.id.w_title, title)
            for (r in ROWS) rv.setViewVisibility(r, View.GONE)
            vis(rv, R.id.w_more, false)
            if (s == null || list == null || list.length() == 0) {
                rv.setViewVisibility(ROWS[0], View.VISIBLE)
                vis(rv, ETIME[0], false)
                rv.setTextViewText(EVS[0], if (s == null || list == null) "Buka TimeFlow untuk menyinkronkan agenda." else "Tidak ada acara hari ini.")
                rv.setTextColor(EVS[0], WidgetTheme.mu(L))
            } else {
                val n = minOf(list.length(), maxRows)
                for (i in 0 until n) {
                    val e = list.getJSONObject(i)
                    rv.setViewVisibility(ROWS[i], View.VISIBLE)
                    vis(rv, ETIME[i], true)
                    rv.setTextViewText(ETIME[i], e.optString("s"))
                    rv.setTextViewText(EVS[i], e.optString("t"))
                    rv.setTextColor(EVS[i], WidgetTheme.fg(L))
                }
                if (list.length() > n) {
                    vis(rv, R.id.w_more, true)
                    rv.setTextViewText(R.id.w_more, "+" + (list.length() - n) + " acara lagi")
                }
            }
            val open = s?.optInt("open", 0) ?: 0
            val al = s?.optInt("alarms", 0) ?: 0
            rv.setTextViewText(R.id.w_tasks, if (s == null) "" else "✓ $open tugas belum selesai  ·  $al alarm aktif")
            vis(rv, R.id.w_tasks, s != null && h >= 100)
            mgr.updateAppWidget(id, rv)
        }
    }
}

class ClockWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUtil.updateClock(ctx, mgr, ids)
        WidgetUtil.scheduleMidnight(ctx)
        CcAccessibilityService.glassNudge()
        val pr = goAsync()
        WidgetUtil.refreshWeather(ctx, false) { pr?.finish() }
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        WidgetUtil.updateClock(ctx, mgr, intArrayOf(id)); CcAccessibilityService.glassNudge()
    }
    override fun onDisabled(ctx: Context) { WidgetUtil.scheduleMidnight(ctx); GlassSlots.invalidate() }
}

class CalendarWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUtil.updateCalendar(ctx, mgr, ids)
        WidgetUtil.scheduleMidnight(ctx)
        CcAccessibilityService.glassNudge()
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        WidgetUtil.updateCalendar(ctx, mgr, intArrayOf(id)); CcAccessibilityService.glassNudge()
    }
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == WidgetUtil.ACTION_CAL_MODE) {
            val m = intent.getStringExtra("mode")
            if (m == "week" || m == "month") WidgetUtil.setCalMode(ctx, m)
            val mgr = AppWidgetManager.getInstance(ctx)
            val all = mgr.getAppWidgetIds(ComponentName(ctx, CalendarWidget::class.java))
            if (all.isNotEmpty()) WidgetUtil.updateCalendar(ctx, mgr, all)
        } else super.onReceive(ctx, intent)
    }
    override fun onDisabled(ctx: Context) { WidgetUtil.scheduleMidnight(ctx); GlassSlots.invalidate() }
}

class AgendaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUtil.updateAgenda(ctx, mgr, ids)
        WidgetUtil.scheduleMidnight(ctx)
        CcAccessibilityService.glassNudge()
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        WidgetUtil.updateAgenda(ctx, mgr, intArrayOf(id)); CcAccessibilityService.glassNudge()
    }
    override fun onDisabled(ctx: Context) { WidgetUtil.scheduleMidnight(ctx); GlassSlots.invalidate() }
}

class WeatherWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        WidgetUtil.updateWeather(ctx, mgr, ids)
        WidgetUtil.scheduleMidnight(ctx)
        CcAccessibilityService.glassNudge()
        val pr = goAsync()
        WidgetUtil.refreshWeather(ctx, false) { pr?.finish() }
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(ctx, mgr, id, newOptions)
        WidgetUtil.updateWeather(ctx, mgr, intArrayOf(id)); CcAccessibilityService.glassNudge()
    }
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == WidgetUtil.ACTION_WX_REFRESH) {
            val pr = goAsync()
            WidgetUtil.refreshWeather(ctx, true) { pr?.finish() }
        } else super.onReceive(ctx, intent)
    }
    override fun onDisabled(ctx: Context) { WidgetUtil.scheduleMidnight(ctx); GlassSlots.invalidate() }
}

/** Dipanggil sesaat setelah tengah malam: ganti tanggal di kalender/agenda dan segarkan cuaca bila sudah lama. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WidgetUtil.updateAll(ctx)
        val pr = goAsync()
        WidgetUtil.refreshWeather(ctx, false) { pr?.finish() }
    }
}
