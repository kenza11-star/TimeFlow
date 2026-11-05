package app.timeflow

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Pengaturan Asisten AI (dibuka dari Pengaturan Flow Launcher): penyedia dan API key AI, ElevenLabs, kata aktivasi, izin, dan tes.
 */
class AiSettingsActivity : Activity() {
    private companion object {
        const val BG = 0xFF0E0D16.toInt()
        const val CARD = 0xFF1A1925.toInt()
        const val INK = 0xFFEDEDF3.toInt()
        const val MUTED = 0xFF9A9AA8.toInt()
        const val ACC = 0xFF8E7CFF.toInt()
        const val REQ_MIC = 41
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    private lateinit var testOut: TextView
    private lateinit var provBtn: TextView
    private lateinit var etBase: EditText
    private lateinit var etModel: EditText
    private var voice: AiVoice? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val sv = ScrollView(this).apply { setBackgroundColor(BG) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(44), dp(16), dp(48)) }
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        setContentView(sv)
        build()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            updateStatus()
            AiService.ensure(this)
        }
    }

    // ---------- pembangun tampilan ----------
    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun card(title: String?): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(18).toFloat() }
        }
        if (title != null) c.addView(text(title, 13f, ACC, true), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        col.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        return c
    }

    private fun note(parent: LinearLayout, t: String) {
        parent.addView(text(t, 12.5f, MUTED), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(4) })
    }

    private fun switchRow(parent: LinearLayout, label: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tx.addView(text(label, 15f, INK))
        if (desc.isNotEmpty()) tx.addView(text(desc, 12f, MUTED))
        row.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, v -> set(v) }
        row.addView(sw)
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(6) })
    }

    private fun field(parent: LinearLayout, label: String, hint: String, value: String, secret: Boolean, multi: Boolean, set: (String) -> Unit): EditText {
        parent.addView(text(label, 12.5f, MUTED), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        val e = EditText(this).apply {
            setText(value); this.hint = hint
            setHintTextColor(0x66FFFFFF); setTextColor(INK); textSize = 14.5f
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (!multi) setSingleLine(true) else minLines = 3
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(12).toFloat() }
        }
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { set(s?.toString() ?: "") }
        })
        parent.addView(e, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        return e
    }

    private fun button(parent: LinearLayout, label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        val b = text(label, 14.5f, if (primary) Color.WHITE else INK, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(if (primary) ACC else 0x33FFFFFF); cornerRadius = dp(14).toFloat() }
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(MATCH, dp(46)).apply { topMargin = dp(10) })
        return b
    }

    private fun build() {
        col.addView(text("Asisten AI", 28f, INK, true))
        col.addView(text("Ucapkan \"ai aktif\", \"kyn aktif\", atau \"wake up\" untuk membangunkan. AI bisa membuka aplikasi, mengetuk, menggeser, mengetik, dan membacakan notifikasi.", 13f, MUTED),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        status = text("", 13f, INK).apply { setLineSpacing(0f, 1.2f) }
        card("STATUS").addView(status)

        val sw = card("MODE")
        switchRow(sw, "Nyalakan asisten AI", "Izinkan Flow Launcher menjalankan asisten", { AiPrefs.enabled(this) }) { v ->
            AiPrefs.setEnabled(this, v)
            if (v) { askMic(); AiService.ensure(this) } else AiService.stop(this)
            updateStatus()
        }
        switchRow(sw, "Dengarkan kata aktivasi", "Mikrofon menyala terus saat siaga (ada notifikasi tetap). Matikan bila ingin mengaktifkan lewat tombol saja.", { AiPrefs.wake(this) }) { v ->
            AiPrefs.setWake(this, v)
            if (v) { askMic(); AiService.ensure(this) } else if (AiService.running && !AiService.active) AiService.stop(this)
            updateStatus()
        }
        switchRow(sw, "Ucapkan jawaban", "Pakai suara ElevenLabs; cadangan suara HP", { AiPrefs.speak(this) }) { AiPrefs.setSpeak(this, it) }
        switchRow(sw, "Kirim tangkapan layar ke AI", "Untuk model yang bisa melihat gambar (vision). Lebih akurat, tapi lebih lambat dan lebih mahal.", { AiPrefs.vision(this) }) { AiPrefs.setVision(this, it) }
        switchRow(sw, "Redam bunyi \"ting\" pengenal suara", "Menyenyapkan aliran sistem dan notifikasi selama AI mendengarkan. Coba bila ada bunyi berulang.", { AiPrefs.muteBeep(this) }) { AiPrefs.setMuteBeep(this, it) }
        button(sw, "Aktifkan AI sekarang", true) {
            if (!AiPrefs.enabled(this)) { out("Nyalakan asisten AI dulu."); return@button }
            if (!AiService.hasMic(this)) { askMic(); return@button }
            AiService.activate(this)
            out("AI diaktifkan. Lihat notch.")
        }
        button(sw, "Tidurkan AI") { AiService.sleep(this); out("AI ditidurkan.") }

        val ai = card("MODEL AI (OTAK)")
        provBtn = button(ai, "", false) { chooseProvider() }
        etBase = field(ai, "Base URL", "https://api.openai.com/v1", AiPrefs.baseUrl(this), false, false) { AiPrefs.setBaseUrl(this, it) }
        etModel = field(ai, "Nama model", "mis. gpt-4o-mini / claude-haiku-5-5 / gemini-2.0-flash", AiPrefs.model(this), false, false) { AiPrefs.setModel(this, it) }
        field(ai, "API key AI", "tempel token di sini", AiPrefs.apiKey(this), true, false) { AiPrefs.setApiKey(this, it) }
        note(ai, "Dua format didukung: OpenAI-compatible (OpenAI, Gemini, OpenRouter, Groq, DeepSeek, Mistral, xAI, Ollama, LM Studio, dll.) dan Anthropic. Pilih \"Kustom\" untuk alamat sendiri (harus https).")
        button(ai, "Tes AI") { testAi() }
        refreshProvider()

        val el = card("SUARA ELEVENLABS")
        field(el, "API key ElevenLabs", "tempel token di sini", AiPrefs.elKey(this), true, false) { AiPrefs.setElKey(this, it) }
        field(el, "Voice ID", "mis. 21m00Tcm4TlvDq8ikWAM", AiPrefs.elVoice(this), false, false) { AiPrefs.setElVoice(this, it) }
        field(el, "Model suara", "eleven_flash_v2_5 (cepat) atau eleven_multilingual_v2", AiPrefs.elModel(this), false, false) { AiPrefs.setElModel(this, it) }
        button(el, "Tes suara") { testVoice() }

        val wk = card("KATA AKTIVASI")
        field(wk, "Nama panggilan (pisahkan dengan koma)", "ai, kyn", AiPrefs.namesRaw(this), false, false) { AiPrefs.setNames(this, it) }
        field(wk, "Bahasa pengenal suara", "id-ID", AiPrefs.lang(this), false, false) { AiPrefs.setLang(this, it) }
        note(wk, "Tambahkan ejaan yang sering salah didengar, mis. \"kyn, kin, kain\". Bangun: \"<nama> aktif\" atau \"wake up\". Tidur: \"<nama> nonaktif\" atau \"sleep\". Batalkan tugas: \"stop\" atau \"batal\".")
        field(wk, "Instruksi tambahan untuk AI (opsional)", "mis. panggil aku Kyn, jawab singkat", AiPrefs.extraPrompt(this), false, true) { AiPrefs.setExtraPrompt(this, it) }

        val pm = card("IZIN")
        button(pm, "Izinkan mikrofon") { askMic() }
        button(pm, "Nyalakan layanan aksesibilitas \"Flow AI (kendali layar)\"") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        note(pm, "Layanan \"Flow AI (kendali layar)\" terpisah dari Control Center dan wajib agar AI bisa melihat layar dan menyentuh. Akses notifikasi dan \"tampil di atas aplikasi lain\" juga dipakai (membaca notifikasi dan membuka aplikasi).")

        testOut = text("", 13f, INK).apply { setLineSpacing(0f, 1.2f) }
        col.addView(testOut, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        note(col, "Privasi: kunci API disimpan hanya di HP ini dan dikirim hanya ke penyedia yang kamu pilih. Isi layar yang dibaca AI dikirim ke penyedia AI itu, tanpa kolom kata sandi. Hati-hati menyebut data rahasia saat AI aktif.")
    }

    private fun out(t: String) { runOnUiThread { testOut.text = t } }

    private fun askMic() {
        if (!AiService.hasMic(this)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun refreshProvider() {
        provBtn.text = "Penyedia: " + AiPrefs.provider(this).label + "  ▾"
        etBase.setText(AiPrefs.baseUrl(this))
        etModel.setText(AiPrefs.model(this))
    }

    private fun chooseProvider() {
        val labels = AiPrefs.PROVIDERS.map { it.label }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Penyedia AI").setItems(labels) { _, which ->
            val p = AiPrefs.PROVIDERS[which]
            AiPrefs.setProvider(this, p.id)
            AiPrefs.setBaseUrl(this, p.baseUrl)
            AiPrefs.setModel(this, p.model)
            refreshProvider()
        }.show()
    }

    private fun testAi() {
        out("Menguji AI…")
        val app = applicationContext
        Thread {
            val r = try {
                val a = AiLlm.chat(app, "Balas dengan satu kata saja: siap.", listOf(AiLlm.Msg("user", "tes koneksi")))
                "AI berfungsi. Jawaban: " + a.take(80)
            } catch (e: Exception) {
                "AI gagal: " + AiLlm.friendly(e)
            }
            out(r)
        }.start()
    }

    private fun testVoice() {
        out("Menguji suara…")
        val v = voice ?: AiVoice(this).also { voice = it }
        v.speak("Halo, saya siap membantu.", onError = { msg -> out("ElevenLabs gagal ($msg), memakai suara HP.") }) { }
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun updateStatus() {
        val mic = AiService.hasMic(this)
        val acc = AiAccessibilityService.connected()
        val gest = AiAccess.canGesture()
        val st = if (!AiService.running) "mati" else if (AiService.active) "AKTIF" else "siaga"
        val sb = StringBuilder()
        sb.append(mark(mic)).append(" Mikrofon\n")
        sb.append(mark(acc)).append(" Layanan \"Flow AI\" (lihat layar)").append("   ").append(mark(gest)).append(" gestur sentuh\n")
        sb.append(mark(NotchListener.accessGranted(this))).append(" Akses notifikasi (baca notifikasi)\n")
        sb.append(mark(NotchListener.overlayGranted(this))).append(" Tampil di atas aplikasi lain (buka aplikasi dari latar)\n")
        sb.append(mark(AiPrefs.apiKey(this).isNotEmpty() || AiPrefs.provider(this).id.startsWith("custom"))).append(" API key AI   ")
            .append(mark(AiPrefs.elKey(this).isNotEmpty())).append(" API key ElevenLabs\n")
        sb.append("Layanan AI: ").append(st)
        if (AiService.lastError.isNotEmpty()) sb.append("\nGalat terakhir: ").append(AiService.lastError)
        status.text = sb.toString()
    }
}
