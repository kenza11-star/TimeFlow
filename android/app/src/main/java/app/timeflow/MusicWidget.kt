package app.timeflow

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/** Widget layar utama: pemutar musik gaya kaca (sampul, judul, artis, progres, kontrol). Data dari NotchListener (sesi media aktif). */
class MusicWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { push(context, NotchListener.hubMedia()) }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            A_PREV -> NotchListener.hubTransport(0)
            A_PLAY -> NotchListener.hubTransport(1)
            A_NEXT -> NotchListener.hubTransport(2)
            A_OPEN -> NotchListener.hubOpenMedia()
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val A_PREV = "app.timeflow.action.MW_PREV"
        const val A_PLAY = "app.timeflow.action.MW_PLAY"
        const val A_NEXT = "app.timeflow.action.MW_NEXT"
        const val A_OPEN = "app.timeflow.action.MW_OPEN"

        private fun ids(ctx: Context): IntArray =
            runCatching { AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, MusicWidget::class.java)) }.getOrDefault(IntArray(0))

        fun has(ctx: Context) = ids(ctx).isNotEmpty()

        private fun pi(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(ctx, code, Intent(ctx, MusicWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun fmt(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60)
        }

        private fun rounded(src: Bitmap, px: Int, r: Float): Bitmap {
            val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), r, r, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val side = minOf(src.width, src.height)
            val sx = (src.width - side) / 2; val sy = (src.height - side) / 2
            c.drawBitmap(src, android.graphics.Rect(sx, sy, sx + side, sy + side), RectF(0f, 0f, px.toFloat(), px.toFloat()), p)
            return out
        }

        private var lrcKey = ""
        private var lrc: Lyrics? = null

        private fun lyricLine(ctx: Context, m: MediaInfo, now: Long): String {
            val k = m.title + "|" + m.artist
            if (k != lrcKey) {
                lrcKey = k; lrc = null
                val app = ctx.applicationContext
                CcLyrics.request(app, m.title, m.artist, m.dur) { l -> if (lrcKey == k) { lrc = l; push(app, NotchListener.hubMedia()) } }
            }
            val l = lrc ?: return ""
            val i = l.indexAt(now + 300)
            return if (i >= 0) l.lines[i].ifEmpty { "♪" } else ""
        }

        /**
         * Perbarui widget musik (aman dipanggil kapan saja; tidak melakukan apa-apa bila tidak ada widget).
         * partial = true: hanya teks, progres, lirik, dan ikon putar/jeda (tanpa sampul), untuk pembaruan tiap detik.
         */
        fun push(ctx: Context, m: MediaInfo?, partial: Boolean = false) {
            val all = ids(ctx)
            if (all.isEmpty()) return
            val rv = RemoteViews(ctx.packageName, R.layout.widget_music)
            if (!partial) {
                rv.setOnClickPendingIntent(R.id.mw_prev, pi(ctx, A_PREV, 1))
                rv.setOnClickPendingIntent(R.id.mw_play, pi(ctx, A_PLAY, 2))
                rv.setOnClickPendingIntent(R.id.mw_next, pi(ctx, A_NEXT, 3))
                rv.setOnClickPendingIntent(R.id.mw_head, pi(ctx, A_OPEN, 4))
            }
            if (m == null) {
                rv.setTextViewText(R.id.mw_title, "Tidak ada musik")
                rv.setTextViewText(R.id.mw_artist, "Putar lagu di aplikasi musik")
                rv.setTextViewText(R.id.mw_lyric, "")
                if (!partial) rv.setViewVisibility(R.id.mw_art, View.GONE)
                rv.setTextViewText(R.id.mw_pos, "0:00"); rv.setTextViewText(R.id.mw_left, "-0:00")
                rv.setProgressBar(R.id.mw_bar, 1000, 0, false)
                rv.setImageViewResource(R.id.mw_play, R.drawable.mw_play)
            } else {
                rv.setTextViewText(R.id.mw_title, m.title.ifEmpty { "Musik" })
                rv.setTextViewText(R.id.mw_artist, m.artist)
                val art = m.art
                if (!partial) {
                    if (art != null) { rv.setViewVisibility(R.id.mw_art, View.VISIBLE); rv.setImageViewBitmap(R.id.mw_art, rounded(art, 160, 26f)) }
                    else rv.setViewVisibility(R.id.mw_art, View.GONE)
                }
                val now = (if (m.playing) m.pos + ((SystemClock.elapsedRealtime() - m.stamp) * m.speed).toLong() else m.pos).coerceIn(0L, if (m.dur > 0) m.dur else Long.MAX_VALUE)
                rv.setTextViewText(R.id.mw_pos, fmt(now))
                rv.setTextViewText(R.id.mw_left, if (m.dur > 0) "-" + fmt(m.dur - now) else "")
                rv.setProgressBar(R.id.mw_bar, 1000, if (m.dur > 0) (now * 1000 / m.dur).toInt() else 0, false)
                rv.setImageViewResource(R.id.mw_play, if (m.playing) R.drawable.mw_pause else R.drawable.mw_play)
                rv.setTextViewText(R.id.mw_lyric, lyricLine(ctx, m, now))
            }
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                if (partial) mgr.partiallyUpdateAppWidget(all, rv) else mgr.updateAppWidget(all, rv)
            }
        }
    }
}
