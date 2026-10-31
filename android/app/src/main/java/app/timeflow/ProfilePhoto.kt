package app.timeflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Foto profil: disimpan sebagai file JPEG di penyimpanan privat, dilayani ke WebView lewat https://appassets.../profile/. */
object ProfilePhoto {
    fun dir(ctx: Context) = File(ctx.filesDir, "profile").apply { mkdirs() }

    private fun file(ctx: Context, owner: String): File {
        require(Alarm.OWNER_RE.matches(owner)) { "Nama profil tidak valid" }
        return File(dir(ctx), "$owner.jpg")
    }

    fun url(ctx: Context, owner: String): String? =
        file(ctx, owner).takeIf { it.exists() }?.let { "/profile/$owner.jpg?v=${it.lastModified()}" }

    fun delete(ctx: Context, owner: String) { file(ctx, owner).delete() }

    /** Baca gambar pilihan pengguna -> JPEG maks 1280 px (orientasi EXIF dikoreksi) sebagai data URL, untuk dipotong di UI. */
    fun decodeForCrop(ctx: Context, uri: Uri): String {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "File ini bukan gambar yang dapat dibaca." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Gambar tidak dapat dibuka.")
        val orient = try {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: Exception) { null } ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        val scale = 1280f / max(bmp.width, bmp.height)
        if (scale < 1f) m.postScale(scale, scale)
        bmp = flatten(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true))
        return "data:image/jpeg;base64," + Base64.encodeToString(jpeg(bmp, 85), Base64.NO_WRAP)
    }

    /** Simpan hasil potongan dari UI (data URL JPEG/PNG) sebagai foto profil 512 px. */
    fun saveFromDataUrl(ctx: Context, owner: String, dataUrl: String): String {
        val f = file(ctx, owner)
        require(dataUrl.startsWith("data:image/") && dataUrl.contains("base64,")) { "Data gambar tidak valid." }
        val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
        require(bytes.size in 1..(4 * 1024 * 1024)) { "Ukuran gambar tidak valid." }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("Gambar rusak.")
        val s = 512f / max(bmp.width, bmp.height)
        if (s < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        val out = jpeg(flatten(bmp), 90)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) { f.delete(); require(tmp.renameTo(f)) { "Gagal menyimpan foto." } }
        return url(ctx, owner)!!
    }

    private fun flatten(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return out
    }

    private fun jpeg(b: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
}
