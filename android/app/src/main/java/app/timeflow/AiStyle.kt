package app.timeflow

import android.content.Context

/**
 * Gaya bicara AI: meniru cara pengguna ngomong. Contoh ucapan pengguna disimpan di HP (hanya beberapa terakhir),
 * dipakai untuk menentukan kata ganti dan nada, serta dikirim ke model sebagai contoh gaya.
 */
object AiStyle {
    private val GUE = Regex("(^| )(gue|gw|gua|gwe)( |$)")
    private val SAYA = Regex("(^| )(saya|anda)( |$)")

    /** Kata ganti orang pertama yang dipakai pengguna: gue, saya, atau aku (bawaan). */
    fun me(ctx: Context): String {
        val t = AiPrefs.styleSamples(ctx).joinToString(" ").lowercase()
        return when {
            GUE.containsMatchIn(t) -> "gue"
            SAYA.containsMatchIn(t) -> "saya"
            else -> "aku"
        }
    }

    fun block(ctx: Context): String {
        val s = AiPrefs.styleSamples(ctx)
        val me = me(ctx)
        if (s.isEmpty()) return "Belum ada contoh ucapan pengguna: pakai bahasa Indonesia santai sehari-hari (sebut dirimu \"$me\"), lalu sesuaikan begitu dia mulai ngomong."
        return "Sebut dirimu \"$me\". Contoh cara pengguna ngomong belakangan ini (tiru nada, kata ganti, dan kebiasaan katanya; jangan disalin mentah-mentah):\n" +
            s.takeLast(10).joinToString("\n") { "- $it" }
    }

    fun ready(ctx: Context) = "Iya, kenapa?"
    fun sleep(ctx: Context) = "Oke, ${me(ctx)} tidur dulu ya."
    fun problem(ctx: Context, msg: String) = "Duh, ada masalah nih: $msg"
    fun cancelled(ctx: Context) = "Oke, batal."
    fun done(ctx: Context) = "Udah ya."
    fun failed(ctx: Context) = "Hmm, ${me(ctx)} belum berhasil nih."
}
