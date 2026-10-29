package app.timeflow

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * Suara alarm: 4 nada bawaan (res/raw) + audio kustom yang DISALIN ke penyimpanan privat aplikasi.
 * Menyalin (bukan menyimpan URI SAF) membuat akses tetap ada setelah restart, tanpa izin penyimpanan.
 */
object SoundLibrary {
    const val DEFAULT_ID = "builtin:classic"
    private const val MAX_BYTES = 25L * 1024 * 1024

    data class Sound(val id: String, val name: String, val builtin: Boolean, val missing: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("builtin", builtin).put("missing", missing)
    }

    private val BUILTIN = listOf(
        Triple("builtin:classic", "Klasik", R.raw.alarm_classic),
        Triple("builtin:soft", "Lembut", R.raw.alarm_soft),
        Triple("builtin:digital", "Digital", R.raw.alarm_digital),
        Triple("builtin:melody", "Melodi", R.raw.alarm_melody),
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, "sounds").apply { mkdirs() }
    private fun metaFile(ctx: Context) = File(dir(ctx), "sounds.json")

    private fun meta(ctx: Context): JSONArray =
        try { JSONArray(readAtomic(metaFile(ctx)) ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun fileOf(ctx: Context, o: JSONObject): File = File(dir(ctx), o.getString("file").substringAfterLast('/'))

    fun list(ctx: Context): List<Sound> {
        val out = BUILTIN.map { Sound(it.first, it.second, true, false) }.toMutableList()
        val m = meta(ctx)
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i)
            out += Sound(o.getString("id"), o.getString("name"), false, !fileOf(ctx, o).exists())
        }
        return out
    }

    /** Mengisi [mp] dengan sumber audio. false jika tidak tersedia (mis. file kustom hilang). */
    fun setSource(ctx: Context, mp: MediaPlayer, id: String): Boolean {
        BUILTIN.firstOrNull { it.first == id }?.let { b ->
            ctx.resources.openRawResourceFd(b.third).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            return true
        }
        val m = meta(ctx)
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i)
            if (o.getString("id") == id) {
                val f = fileOf(ctx, o)
                if (!f.exists()) return false
                FileInputStream(f).use { mp.setDataSource(it.fd) }
                return true
            }
        }
        return false
    }

    fun import(ctx: Context, uri: Uri): Sound {
        val cr = ctx.contentResolver
        var name = "Audio kustom"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        require(size < 0 || size <= MAX_BYTES) { "File audio terlalu besar (maks 25 MB)." }
        val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        val shortId = UUID.randomUUID().toString().replace("-", "").take(10)
        val dest = File(dir(ctx), "$shortId.$ext")
        try {
            var total = 0L
            cr.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_BYTES) { "File audio terlalu besar (maks 25 MB)." }
                        out.write(buf, 0, n)
                    }
                }
            }
            // Pastikan benar-benar audio yang bisa didekode Android.
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(dest.absolutePath)
                require(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "File ini bukan audio yang didukung." }
            } finally { r.release() }
        } catch (e: Exception) {
            dest.delete()
            throw if (e is IllegalArgumentException) e else IllegalArgumentException("Audio tidak dapat dibaca atau formatnya tidak didukung.")
        }
        val id = "custom:$shortId"
        val clean = name.substringBeforeLast('.').trim().ifEmpty { "Audio kustom" }.take(40)
        val m = meta(ctx)
        m.put(JSONObject().put("id", id).put("name", clean).put("file", dest.name))
        writeAtomic(metaFile(ctx), m.toString())
        return Sound(id, clean, false, false)
    }

    fun delete(ctx: Context, id: String): Boolean {
        val m = meta(ctx)
        val keep = JSONArray()
        var found = false
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i)
            if (o.getString("id") == id) { found = true; fileOf(ctx, o).delete() } else keep.put(o)
        }
        if (found) writeAtomic(metaFile(ctx), keep.toString())
        return found
    }
}
