package app.timeflow

import android.content.Context

/** Status Asisten AI yang ditampilkan di notch: 1 = mendengarkan, 2 = berpikir, 3 = berbicara, 4 = bekerja di layar. */
class AiInfo(val state: Int, val text: String)

/** Kapsul info singkat di notch (mengisi daya, baterai lemah, senyap, jangan ganggu). */
class InfoCap(val icon: String, val text: String, val ms: Long)

/**
 * Pengaturan Asisten AI. Disimpan di SharedPreferences "flow_ai" (privat, tidak ikut cadangan tata letak: kunci API tidak pernah diekspor).
 */
object AiPrefs {
    class Provider(val id: String, val label: String, val anthropic: Boolean, val baseUrl: String, val model: String)

    /** Dua format API menjangkau hampir semua penyedia: OpenAI-compatible (OpenAI, Gemini, OpenRouter, Groq, DeepSeek, Mistral, xAI, Ollama, LM Studio, dll.) dan Anthropic. */
    val PROVIDERS = listOf(
        Provider("openai", "OpenAI", false, "https://api.openai.com/v1", "gpt-4o-mini"),
        Provider("anthropic", "Anthropic (Claude)", true, "https://api.anthropic.com/v1", "claude-haiku-5-5"),
        Provider("gemini", "Google Gemini", false, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
        Provider("openrouter", "OpenRouter (semua model)", false, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        Provider("groq", "Groq", false, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        Provider("deepseek", "DeepSeek", false, "https://api.deepseek.com/v1", "deepseek-chat"),
        Provider("mistral", "Mistral", false, "https://api.mistral.ai/v1", "mistral-small-latest"),
        Provider("xai", "xAI (Grok)", false, "https://api.x.ai/v1", "grok-3-mini"),
        Provider("custom", "Kustom (format OpenAI)", false, "", ""),
        Provider("custom_anthropic", "Kustom (format Anthropic)", true, "", ""),
    )

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("flow_ai", Context.MODE_PRIVATE)
    private fun s(c: Context, k: String, d: String): String = sp(c).getString(k, d) ?: d
    private fun b(c: Context, k: String, d: Boolean): Boolean = sp(c).getBoolean(k, d)
    private fun put(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
    private fun put(c: Context, k: String, v: Boolean) { sp(c).edit().putBoolean(k, v).apply() }

    fun provider(c: Context): Provider = PROVIDERS.firstOrNull { it.id == s(c, "provider", "openai") } ?: PROVIDERS[0]
    fun setProvider(c: Context, id: String) { put(c, "provider", id) }
    fun isAnthropic(c: Context): Boolean = provider(c).anthropic

    fun baseUrl(c: Context): String = s(c, "base_url", "").ifBlank { provider(c).baseUrl }
    fun setBaseUrl(c: Context, v: String) = put(c, "base_url", v.trim())
    fun model(c: Context): String = s(c, "model", "").ifBlank { provider(c).model }
    fun setModel(c: Context, v: String) = put(c, "model", v.trim())
    fun apiKey(c: Context): String = s(c, "api_key", "")
    fun setApiKey(c: Context, v: String) = put(c, "api_key", v.trim())
    fun extraPrompt(c: Context): String = s(c, "extra_prompt", "")
    fun setExtraPrompt(c: Context, v: String) = put(c, "extra_prompt", v)

    fun elKey(c: Context): String = s(c, "el_key", "")
    fun setElKey(c: Context, v: String) = put(c, "el_key", v.trim())
    fun elVoice(c: Context): String = s(c, "el_voice", "21m00Tcm4TlvDq8ikWAM")
    fun setElVoice(c: Context, v: String) = put(c, "el_voice", v.trim())
    fun elModel(c: Context): String = s(c, "el_model", "eleven_flash_v2_5")
    fun setElModel(c: Context, v: String) = put(c, "el_model", v.trim())

    fun enabled(c: Context): Boolean = b(c, "enabled", false)
    fun setEnabled(c: Context, v: Boolean) = put(c, "enabled", v)
    fun wake(c: Context): Boolean = b(c, "wake", false)
    fun setWake(c: Context, v: Boolean) = put(c, "wake", v)
    fun speak(c: Context): Boolean = b(c, "speak", true)
    fun setSpeak(c: Context, v: Boolean) = put(c, "speak", v)
    fun vision(c: Context): Boolean = b(c, "vision", false)
    fun setVision(c: Context, v: Boolean) = put(c, "vision", v)
    fun muteBeep(c: Context): Boolean = b(c, "mute_beep", false)
    fun setMuteBeep(c: Context, v: Boolean) = put(c, "mute_beep", v)

    fun namesRaw(c: Context): String = s(c, "names", "ai, kyn, kin")
    fun setNames(c: Context, v: String) = put(c, "names", v)
    /** Nama panggilan untuk membangunkan AI, huruf kecil. */
    fun names(c: Context): List<String> = namesRaw(c).lowercase().split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun lang(c: Context): String = s(c, "lang", "id-ID").ifBlank { "id-ID" }
    fun setLang(c: Context, v: String) = put(c, "lang", v.trim())
    fun maxSteps(c: Context): Int = sp(c).getInt("max_steps", 12).coerceIn(3, 30)
}
