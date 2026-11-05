package app.timeflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klien LLM universal. Dua format: OpenAI-compatible (/chat/completions) dan Anthropic (/messages).
 * Kunci API hanya dikirim ke alamat yang kamu isi sendiri dan tidak pernah dicatat di log atau pesan galat.
 */
object AiLlm {
    class Msg(val role: String, val text: String, val imageB64: String? = null)
    class AiError(message: String) : Exception(message)

    fun friendly(e: Throwable): String = when (e) {
        is AiError -> e.message ?: "galat AI"
        is java.net.UnknownHostException -> "tidak ada koneksi internet"
        is java.net.SocketTimeoutException -> "koneksi terlalu lama"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun chat(ctx: Context, system: String, history: List<Msg>): String {
        val key = AiPrefs.apiKey(ctx).trim()
        val base = AiPrefs.baseUrl(ctx).trim().trimEnd('/')
        val model = AiPrefs.model(ctx).trim()
        if (key.isEmpty() && !AiPrefs.provider(ctx).id.startsWith("custom")) throw AiError("API key AI belum diisi")
        if (base.isEmpty()) throw AiError("Base URL belum diisi")
        if (model.isEmpty()) throw AiError("Nama model belum diisi")
        val msgs = merge(history)
        return if (AiPrefs.isAnthropic(ctx)) anthropic(base, key, model, system, msgs) else openai(base, key, model, system, msgs)
    }

    /** Pesan beruntun dengan peran sama digabung (Anthropic mewajibkan peran bergantian). */
    private fun merge(h: List<Msg>): List<Msg> {
        val out = ArrayList<Msg>()
        for (m in h) {
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) out[out.size - 1] = Msg(m.role, last.text + "\n\n" + m.text, m.imageB64 ?: last.imageB64)
            else out.add(m)
        }
        return out
    }

    private fun openai(base: String, key: String, model: String, system: String, msgs: List<Msg>): String {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) {
            val o = JSONObject().put("role", m.role)
            if (m.imageB64 != null) {
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", m.text))
                parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + m.imageB64)))
                o.put("content", parts)
            } else o.put("content", m.text)
            arr.put(o)
        }
        val body = JSONObject().put("model", model).put("messages", arr)
        val headers = HashMap<String, String>()
        if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        val j = JSONObject(post("$base/chat/completions", headers, body.toString()))
        val c = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        val text = when (c) {
            is String -> c
            is JSONArray -> buildString { for (i in 0 until c.length()) append(c.optJSONObject(i)?.optString("text") ?: "") }
            else -> throw AiError("jawaban AI kosong")
        }
        return text.trim()
    }

    private fun anthropic(base: String, key: String, model: String, system: String, msgs: List<Msg>): String {
        val arr = JSONArray()
        for (m in msgs) {
            val parts = JSONArray()
            if (m.imageB64 != null) {
                parts.put(JSONObject().put("type", "image").put("source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", m.imageB64)))
            }
            parts.put(JSONObject().put("type", "text").put("text", m.text))
            arr.put(JSONObject().put("role", m.role).put("content", parts))
        }
        val body = JSONObject().put("model", model).put("max_tokens", 1024).put("system", system).put("messages", arr)
        val headers = HashMap<String, String>()
        if (key.isNotEmpty()) headers["x-api-key"] = key
        headers["anthropic-version"] = "2023-06-01"
        val j = JSONObject(post("$base/messages", headers, body.toString()))
        val c = j.optJSONArray("content") ?: throw AiError("jawaban AI kosong")
        val sb = StringBuilder()
        for (i in 0 until c.length()) {
            val o = c.optJSONObject(i) ?: continue
            if (o.optString("type") == "text") sb.append(o.optString("text"))
        }
        if (sb.isBlank()) throw AiError("jawaban AI kosong")
        return sb.toString().trim()
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw AiError(errMsg(code, text))
            return text
        } finally {
            c.disconnect()
        }
    }

    /** Pesan galat HTTP yang ringkas dan aman (tanpa kunci). */
    fun errMsg(code: Int, text: String): String {
        val m = runCatching {
            val j = JSONObject(text)
            val e = j.opt("error") ?: j.opt("detail")
            when (e) {
                is JSONObject -> e.optString("message")
                is String -> e
                else -> j.optString("message")
            }
        }.getOrNull()
        val hint = when (code) {
            401, 403 -> " (periksa API key)"
            404 -> " (periksa Base URL / nama model)"
            429 -> " (kuota atau batas permintaan)"
            else -> ""
        }
        return "HTTP $code" + hint + if (!m.isNullOrBlank()) ": " + m.take(160) else ""
    }
}
