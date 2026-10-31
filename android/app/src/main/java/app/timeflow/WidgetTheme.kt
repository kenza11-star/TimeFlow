package app.timeflow

import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews

/**
* Warna widget untuk gaya kaca TERANG (teks gelap di atas kaca putih susu). DIHASILKAN dari daftar view di res/layout/widget_*.xml.
* apply() dipanggil tepat setelah RemoteViews dibuat; bila latar widget bergaya terang, semua teks/ikon/latar kecil diganti ke versi gelap.
* Warna dinamis di kode (hari terpilih di kalender, dll.) memakai fg()/mu()/inv()/dr() di bawah. Gaya gelap tidak berubah apa pun.
*/
object WidgetTheme {
    const val INK = 0xFF1C1C1E.toInt()
    const val INK_MUTED = 0xA61C1C1E.toInt()
    private const val TRACK = 0x26000000

    class Spec(val fg: IntArray, val mu: IntArray, val inv: IntArray, val tint: IntArray, val pb: IntArray, val bgId: IntArray, val bgRes: IntArray)

    private val SPECS: Map<Int, Spec> = mapOf(
        R.layout.widget_music to Spec(intArrayOf(R.id.mw_title, R.id.mw_lyric), intArrayOf(R.id.mw_artist, R.id.mw_pos, R.id.mw_left), intArrayOf(), intArrayOf(R.id.mw_prev, R.id.mw_play, R.id.mw_next), intArrayOf(R.id.mw_bar),
            intArrayOf(), intArrayOf()),
        R.layout.widget_clock to Spec(intArrayOf(R.id.w_time, R.id.w_wx), intArrayOf(R.id.w_date), intArrayOf(), intArrayOf(), intArrayOf(),
            intArrayOf(), intArrayOf()),
        R.layout.widget_calendar to Spec(intArrayOf(R.id.cal_month, R.id.cal_day, R.id.wd0, R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6, R.id.w_c0, R.id.w_c1, R.id.w_c2, R.id.w_c3, R.id.w_c4, R.id.w_c5, R.id.w_c6, R.id.w_c7, R.id.w_c8, R.id.w_c9, R.id.w_c10, R.id.w_c11, R.id.w_c12, R.id.w_c13, R.id.w_c14, R.id.w_c15, R.id.w_c16, R.id.w_c17, R.id.w_c18, R.id.w_c19, R.id.w_c20, R.id.w_c21, R.id.w_c22, R.id.w_c23, R.id.w_c24, R.id.w_c25, R.id.w_c26, R.id.w_c27, R.id.w_c28, R.id.w_c29, R.id.w_c30, R.id.w_c31, R.id.w_c32, R.id.w_c33, R.id.w_c34, R.id.w_c35, R.id.w_c36, R.id.w_c37, R.id.w_c38, R.id.w_c39, R.id.w_c40, R.id.w_c41, R.id.cal_new), intArrayOf(R.id.cal_pm, R.id.wh0, R.id.wh1, R.id.wh2, R.id.wh3, R.id.wh4, R.id.wh5, R.id.wh6, R.id.w_h0, R.id.w_h1, R.id.w_h2, R.id.w_h3, R.id.w_h4, R.id.w_h5, R.id.w_h6, R.id.cal_remt), intArrayOf(R.id.cal_pw), intArrayOf(R.id.th_calendar_1), intArrayOf(),
            intArrayOf(R.id.cal_pw, R.id.cal_pm, R.id.cal_new), intArrayOf(R.drawable.gw_pill_on, R.drawable.gw_pill_off, R.drawable.gw_btn)),
        R.layout.widget_weather to Spec(intArrayOf(R.id.w_icon, R.id.w_temp, R.id.w_cond, R.id.w_sr, R.id.w_ss, R.id.wx_rain, R.id.wx_fi0, R.id.wx_fh0, R.id.wx_fi1, R.id.wx_fh1, R.id.wx_fi2, R.id.wx_fh2, R.id.wx_fi3, R.id.wx_fh3, R.id.wx_fi4, R.id.wx_fh4, R.id.wx_fi5, R.id.wx_fh5, R.id.wx_fi6, R.id.wx_fh6), intArrayOf(R.id.w_city, R.id.w_refresh, R.id.w_detail, R.id.w_hours, R.id.w_dur, R.id.w_hum, R.id.w_wind, R.id.wx_fl0, R.id.wx_fn0, R.id.wx_fl1, R.id.wx_fn1, R.id.wx_fl2, R.id.wx_fn2, R.id.wx_fl3, R.id.wx_fn3, R.id.wx_fl4, R.id.wx_fn4, R.id.wx_fl5, R.id.wx_fn5, R.id.wx_fl6, R.id.wx_fn6), intArrayOf(), intArrayOf(R.id.th_weather_1, R.id.th_weather_2), intArrayOf(),
            intArrayOf(R.id.wx_sun, R.id.wx_rain, R.id.wx_fc0, R.id.wx_fc1, R.id.wx_fc2, R.id.wx_fc3, R.id.wx_fc4, R.id.wx_fc5, R.id.wx_fc6), intArrayOf(R.drawable.gw_pill_dark, R.drawable.gw_pill_dark, R.drawable.gw_col, R.drawable.gw_col, R.drawable.gw_col, R.drawable.gw_col, R.drawable.gw_col, R.drawable.gw_col, R.drawable.gw_col)),
        R.layout.widget_agenda to Spec(intArrayOf(R.id.w_title, R.id.w_e1, R.id.w_e2, R.id.w_e3, R.id.w_e4, R.id.w_tasks), intArrayOf(R.id.w_t1, R.id.w_t2, R.id.w_t3, R.id.w_t4, R.id.w_more), intArrayOf(), intArrayOf(), intArrayOf(),
            intArrayOf(R.id.w_tasks), intArrayOf(R.drawable.gw_chip)),
        R.layout.widget_screentime to Spec(intArrayOf(R.id.st_total, R.id.st_an0, R.id.st_an1, R.id.st_an2, R.id.st_an3, R.id.st_an4, R.id.st_msg), intArrayOf(R.id.st_title, R.id.st_date, R.id.st_refresh, R.id.st_today, R.id.st_at0, R.id.st_at1, R.id.st_at2, R.id.st_at3, R.id.st_at4), intArrayOf(R.id.st_grant), intArrayOf(), intArrayOf(R.id.st_ab0, R.id.st_ab1, R.id.st_ab2, R.id.st_ab3, R.id.st_ab4),
            intArrayOf(R.id.st_grant), intArrayOf(R.drawable.gw_pill_on)),
    )

    fun fg(light: Boolean) = if (light) INK else 0xFFFFFFFF.toInt()
    fun mu(light: Boolean) = if (light) INK_MUTED else 0xB3FFFFFF.toInt()
    /** Teks di atas pil/kotak terpilih: gaya gelap = pil putih + teks gelap; gaya terang = pil gelap + teks putih. */
    fun inv(light: Boolean) = if (light) 0xFFFFFFFF.toInt() else INK

    /** Drawable kecil (pil, kolom, kotak terpilih) untuk gaya terang. */
    fun dr(light: Boolean, res: Int): Int = if (!light || res == 0) res else when (res) {
        R.drawable.gw_col -> R.drawable.gw_col_l
        R.drawable.gw_col_on -> R.drawable.gw_col_on_l
        R.drawable.gw_pill_dark -> R.drawable.gw_pill_dark_l
        R.drawable.gw_pill_on -> R.drawable.gw_pill_on_l
        R.drawable.gw_pill_off -> R.drawable.gw_pill_off_l
        R.drawable.gw_btn -> R.drawable.gw_btn_l
        R.drawable.gw_chip -> R.drawable.gw_chip_l
        R.drawable.gw_sel -> R.drawable.gw_sel_l
        else -> res
    }

    fun light(ctx: Context, d: GlassSlots.Def): Boolean = GlassBg.isLight(ctx, d.name)

    fun apply(ctx: Context, rv: RemoteViews, d: GlassSlots.Def) {
        if (!light(ctx, d)) return
        val s = SPECS[d.layout] ?: return
        for (id in s.fg) rv.setTextColor(id, INK)
        for (id in s.mu) rv.setTextColor(id, INK_MUTED)
        for (id in s.inv) rv.setTextColor(id, 0xFFFFFFFF.toInt())
        for (id in s.tint) rv.setInt(id, "setColorFilter", INK)
        for (i in s.bgId.indices) rv.setInt(s.bgId[i], "setBackgroundResource", dr(true, s.bgRes[i]))
        if (Build.VERSION.SDK_INT >= 31) for (id in s.pb) {
            rv.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(0xE61C1C1E.toInt()))
            rv.setColorStateList(id, "setProgressBackgroundTintList", ColorStateList.valueOf(TRACK))
        }
    }
}
