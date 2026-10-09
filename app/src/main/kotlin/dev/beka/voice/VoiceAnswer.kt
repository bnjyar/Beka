package dev.beka.voice

/** Reads a spoken yes or no to a confirmation question. */
object VoiceAnswer {
    private val YES = setOf("ya", "iya", "iyah", "yes", "yup", "boleh", "lanjut", "lanjutkan", "oke", "ok", "okay", "gas", "kirim", "silakan", "betul", "setuju", "jadi")
    private val NO = setOf("batal", "batalkan", "jangan", "nggak", "ngga", "gak", "enggak", "tidak", "no", "stop", "berhenti", "cancel", "gausah", "nggausah")

    /** True for yes, false for no, null when unclear (both or neither). */
    fun parse(text: String): Boolean? {
        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        val yes = words.any { it in YES }
        val no = words.any { it in NO }
        return when {
            no -> false
            yes -> true
            else -> null
        }
    }
}
