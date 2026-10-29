package app.timeflow

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cuaca lewat Open-Meteo (gratis, tanpa API key). Hanya kode native yang boleh memakai internet;
 * WebView tetap terkunci ke origin aplikasi.
 *
 * Lokasi: mode "gps" (lokasi perkiraan perangkat, izin ACCESS_COARSE_LOCATION, hanya dibaca saat app dibuka)
 * atau mode "city" (kota pilihan pengguna). Koordinat terakhir disimpan, jadi widget bisa memperbarui cuaca
 * di latar belakang tanpa izin lokasi latar belakang.
 */
object WeatherRepo {
    private const val SP = "timeflow_weather"
    const val STALE_MS = 20 * 60_000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ---------- status lokasi ----------
    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(ctx: Context): Boolean = sp(ctx).contains("lat") && sp(ctx).contains("lon")
    fun mode(ctx: Context): String = sp(ctx).getString("mode", "") ?: ""
    fun setMode(ctx: Context, m: String) { sp(ctx).edit().putString("mode", m).apply() }

    fun setLocation(ctx: Context, lat: Double, lon: Double, name: String, mode: String) {
        sp(ctx).edit()
            .putString("lat", lat.toString()).putString("lon", lon.toString())
            .putString("name", name.take(60)).putString("mode", mode)
            .remove("cache").apply()
    }

    fun cached(ctx: Context): JSONObject? =
        sp(ctx).getString("cache", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun isStale(ctx: Context): Boolean {
        val c = cached(ctx) ?: return true
        return System.currentTimeMillis() - c.optLong("ts", 0L) > STALE_MS
    }

    /** Status ringkas untuk UI web. */
    fun state(ctx: Context): JSONObject = JSONObject()
        .put("hasLoc", hasLocation(ctx))
        .put("mode", mode(ctx))
        .put("name", sp(ctx).getString("name", "") ?: "")
        .put("perm", hasPermission(ctx))

    /** Data cuaca tersimpan + status (bisa kosong). */
    fun snapshot(ctx: Context): JSONObject {
        val o = JSONObject().put("state", state(ctx))
        cached(ctx)?.let { o.put("data", it) }
        return o
    }

    // ---------- jaringan ----------
    private fun httpGet(url: String): String {
        require(url.startsWith("https://")) { "Hanya HTTPS" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 12_000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "TimeFlow/1.6 (Android)")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("Layanan cuaca membalas error $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun num(d: Double) = String.format(Locale.ROOT, "%.4f", d)

    /** Cari kota (Open-Meteo Geocoding). */
    fun search(q: String): JSONArray {
        val name = q.trim()
        require(name.length >= 2) { "Ketik minimal 2 huruf nama kota" }
        val body = try {
            httpGet("https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(name.take(60), "UTF-8") +
                "&count=6&language=id&format=json")
        } catch (e: IllegalStateException) { throw e } catch (e: Exception) {
            throw IllegalArgumentException("Tidak bisa terhubung ke internet")
        }
        val res = JSONObject(body).optJSONArray("results") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            out.put(JSONObject()
                .put("name", r.optString("name"))
                .put("admin", r.optString("admin1"))
                .put("country", r.optString("country"))
                .put("lat", r.optDouble("latitude"))
                .put("lon", r.optDouble("longitude")))
        }
        return out
    }

    /** Nama tempat dari koordinat (BigDataCloud, tanpa key). Gagal -> "Lokasi saya". */
    private fun placeName(lat: Double, lon: Double): String = try {
        val j = JSONObject(httpGet("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${num(lat)}&longitude=${num(lon)}&localityLanguage=id"))
        listOf("locality", "city", "principalSubdivision").map { j.optString(it) }.firstOrNull { it.isNotBlank() } ?: "Lokasi saya"
    } catch (_: Exception) { "Lokasi saya" }

    /** Ambil cuaca untuk koordinat tersimpan, simpan ke cache, dan kembalikan. */
    @Synchronized
    fun fetch(ctx: Context): JSONObject {
        val s = sp(ctx)
        val lat = s.getString("lat", null)?.toDoubleOrNull()
        val lon = s.getString("lon", null)?.toDoubleOrNull()
        require(lat != null && lon != null) { "Lokasi belum diatur" }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${num(lat)}&longitude=${num(lon)}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=2&wind_speed_unit=kmh"
        val body = try { httpGet(url) } catch (e: IllegalStateException) { throw e } catch (e: Exception) {
            throw IllegalArgumentException("Tidak bisa terhubung ke internet")
        }
        val j = JSONObject(body)
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly")

        val out = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("name", s.getString("name", "") ?: "")
            .put("cur", JSONObject()
                .put("t", cur.optDouble("temperature_2m"))
                .put("feel", cur.optDouble("apparent_temperature"))
                .put("hum", cur.optInt("relative_humidity_2m"))
                .put("code", cur.optInt("weather_code"))
                .put("wind", cur.optDouble("wind_speed_10m"))
                .put("day", cur.optInt("is_day", 1)))
            .put("day", JSONObject()
                .put("max", daily.getJSONArray("temperature_2m_max").optDouble(0))
                .put("min", daily.getJSONArray("temperature_2m_min").optDouble(0))
                .put("pop", daily.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0)
                .put("sunrise", daily.getJSONArray("sunrise").optString(0).takeLast(5))
                .put("sunset", daily.getJSONArray("sunset").optString(0).takeLast(5)))

        // 8 jam ke depan, mulai dari jam sekarang (waktu lokal lokasi, string ISO bisa dibandingkan langsung)
        val nowKey = cur.optString("time").take(13)
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val pops = hourly.optJSONArray("precipitation_probability")
        val hrs = JSONArray()
        var i = 0
        while (i < times.length() && times.getString(i).take(13) < nowKey) i++
        var n = 0
        while (i < times.length() && n < 8) {
            hrs.put(JSONObject()
                .put("h", times.getString(i).takeLast(5))
                .put("t", temps.optDouble(i))
                .put("code", codes.optInt(i))
                .put("pop", pops?.optInt(i, 0) ?: 0))
            i++; n++
        }
        out.put("hours", hrs)
        s.edit().putString("cache", out.toString()).apply()
        return out
    }

    /** Segarkan hanya bila data sudah lama dan lokasi ada. Mengembalikan true bila ada data baru. */
    fun refreshIfStale(ctx: Context): Boolean {
        if (!hasLocation(ctx) || !isStale(ctx)) return false
        return runCatching { fetch(ctx); true }.getOrDefault(false)
    }

    // ---------- GPS (hanya saat app terbuka di layar) ----------
    /** Perbarui koordinat dari lokasi perangkat lalu simpan. Mengembalikan false bila lokasi tidak tersedia. */
    fun updateFromDevice(ctx: Context): Boolean {
        val loc = locate(ctx) ?: return false
        val old = sp(ctx)
        val oldLat = old.getString("lat", null)?.toDoubleOrNull()
        val oldLon = old.getString("lon", null)?.toDoubleOrNull()
        val moved = oldLat == null || oldLon == null ||
            Math.abs(oldLat - loc.latitude) > 0.05 || Math.abs(oldLon - loc.longitude) > 0.05
        val name = if (moved || (old.getString("name", "") ?: "").isEmpty()) placeName(loc.latitude, loc.longitude)
                   else old.getString("name", "")!!
        if (moved) setLocation(ctx, loc.latitude, loc.longitude, name, "gps")
        else old.edit().putString("mode", "gps").putString("name", name).apply()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun locate(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || l.time > best.time) best = l
        }
        val b = best
        if (b != null && System.currentTimeMillis() - b.time < 3 * 3600_000L) return b

        // Minta lokasi baru dari provider jaringan (cukup untuk kota), tunggu maksimal 10 detik.
        val provider = if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false))
            LocationManager.NETWORK_PROVIDER else return b
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cs = CancellationSignal()
                lm.getCurrentLocation(provider, cs, Executors.newSingleThreadExecutor()) { l -> fresh = l; latch.countDown() }
                if (!latch.await(10, TimeUnit.SECONDS)) cs.cancel()
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { fresh = l; latch.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                if (!latch.await(10, TimeUnit.SECONDS)) lm.removeUpdates(listener)
            }
        } catch (_: Exception) { }
        return fresh ?: b
    }

    // ---------- teks & ikon (untuk widget) ----------
    fun describe(code: Int, day: Boolean): Pair<String, String> = when (code) {
        0 -> "Cerah" to (if (day) "☀️" else "🌙")
        1 -> "Cerah berawan" to (if (day) "🌤️" else "☁️")
        2 -> "Berawan sebagian" to (if (day) "⛅" else "☁️")
        3 -> "Mendung" to "☁️"
        45, 48 -> "Berkabut" to "🌫️"
        51, 53, 55 -> "Gerimis" to "🌦️"
        56, 57 -> "Gerimis dingin" to "🌧️"
        61 -> "Hujan ringan" to "🌧️"
        63 -> "Hujan sedang" to "🌧️"
        65 -> "Hujan lebat" to "🌧️"
        66, 67 -> "Hujan dingin" to "🌧️"
        71, 73, 75, 77, 85, 86 -> "Salju" to "❄️"
        80, 81 -> "Hujan lokal" to "🌦️"
        82 -> "Hujan deras" to "⛈️"
        95 -> "Badai petir" to "⛈️"
        96, 99 -> "Badai petir & es" to "⛈️"
        else -> "Cuaca" to "🌡️"
    }
}
