package app.timeflow

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.AlarmClock
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Widget "Jam Kaca": angka besar bergaya kaca bening (isi tembus pandang, garis tepi terang, bayangan lembut) di atas panel kaca. */
class GlassClockWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        GlassClock.update(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle?) {
        GlassClock.update(context, appWidgetManager, intArrayOf(appWidgetId))
    }
}

object GlassClock {
    /** Dipanggil peluncur tiap menit (ACTION_TIME_TICK) dan saat tampil. */
    fun refreshAll(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        val ids = mgr.getAppWidgetIds(ComponentName(app, GlassClockWidget::class.java))
        if (ids.isNotEmpty()) update(app, mgr, ids)
    }

    fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val d = ctx.resources.displayMetrics.density
        val pi = PendingIntent.getActivity(
            ctx, 41, Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in ids) {
            val o = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
            val wDp = (o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0).let { if (it > 0) it else 300 }
            val hDp = (o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0).let { if (it > 0) it else 150 }
            val w = (wDp * d).toInt().coerceIn(240, 1400)
            val h = (hDp * d).toInt().coerceIn(120, 900)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_glass_clock)
            rv.setImageViewBitmap(R.id.gclock_img, render(ctx, w, h))
            rv.setOnClickPendingIntent(R.id.gclock_img, pi)
            mgr.updateAppWidget(id, rv)
        }
    }

    fun render(ctx: Context, w: Int, h: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pad = 6f * d
        val panel = RectF(pad, pad, w - pad, h - pad)
        val r = 30f * d

        // panel kaca
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, panel.top, 0f, panel.bottom, intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(24, 255, 255, 255)), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(panel, r, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.4f * d
        p.color = Color.argb(120, 255, 255, 255)
        c.drawRoundRect(panel, r, r, p)

        // angka jam sebagai bentuk (path), diregangkan agar mengisi panel seperti jam kunci layar iOS
        val time = SimpleDateFormat(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "hh:mm", Locale.getDefault()).format(Date())
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        tp.textSize = 200f
        val path = Path()
        tp.getTextPath(time, 0, time.length, 0f, 0f, path)
        val rf = RectF()
        path.computeBounds(rf, true)
        if (rf.width() < 1f || rf.height() < 1f) return bmp
        val target = RectF(panel.left + panel.width() * 0.07f, panel.top + panel.height() * 0.13f, panel.right - panel.width() * 0.07f, panel.bottom - panel.height() * 0.13f)
        val sx0 = target.width() / rf.width()
        val sy0 = target.height() / rf.height()
        val s = minOf(sx0, sy0)
        val sx = minOf(sx0, s * 1.25f)
        val sy = minOf(sy0, s * 1.75f)
        val m = Matrix()
        m.postTranslate(-rf.centerX(), -rf.centerY())
        m.postScale(sx, sy)
        m.postTranslate(panel.centerX(), panel.centerY())
        path.transform(m)
        val bounds = RectF()
        path.computeBounds(bounds, true)

        // isi kaca: putih tembus pandang dengan bayangan lembut
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.style = Paint.Style.FILL
        fill.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom, intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(70, 255, 255, 255)), null, Shader.TileMode.CLAMP)
        fill.setShadowLayer(10f * d, 0f, 4f * d, Color.argb(95, 0, 0, 0))
        c.drawPath(path, fill)

        // kilau tepi dalam (cahaya lembut di dalam huruf)
        c.save()
        c.clipPath(path)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.style = Paint.Style.STROKE
        glow.strokeWidth = 9f * d
        glow.color = Color.argb(120, 255, 255, 255)
        glow.maskFilter = BlurMaskFilter(6f * d, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, glow)
        c.restore()

        // garis tepi terang
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.style = Paint.Style.STROKE
        line.strokeWidth = 2.2f * d
        line.strokeJoin = Paint.Join.ROUND
        line.color = Color.argb(235, 255, 255, 255)
        c.drawPath(path, line)
        return bmp
    }
}
