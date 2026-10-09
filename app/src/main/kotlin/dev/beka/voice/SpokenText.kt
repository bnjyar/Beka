package dev.beka.voice

import dev.beka.rules.ProactivePrompt

/** Turns a chat reply into something a voice can read: no Markdown marks, links or code fences. */
object SpokenText {
    private val FENCE = Regex("```[\\s\\S]*?```")
    private val LINK = Regex("""\[([^\]]+)]\([^)]+\)""")
    private val URL = Regex("""https?://\S+""")
    private val BULLET = Regex("""(?m)^\s*(?:[-*•]|\d+[.)])\s+""")
    private val TABLE_RULE = Regex("""(?m)^\s*\|?[\s:|-]+\|[\s:|-]*$""")
    private val SPACES = Regex("""[ \t]+""")
    private val BLANK_LINES = Regex("""\n{2,}""")

    fun of(reply: String): String {
        var t = FENCE.replace(reply, " ")
        t = LINK.replace(t) { it.groupValues[1] }
        t = URL.replace(t, "")
        t = ProactivePrompt.plain(t)
        t = TABLE_RULE.replace(t, "")
        t = BULLET.replace(t, "")
        t = t.replace('|', ',').replace("`", "")
        t = SPACES.replace(t, " ")
        return BLANK_LINES.replace(t, "\n").trim()
    }

    /** Pieces no longer than [max] characters, cut at sentence ends where possible. */
    fun chunks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf('\n'), window.lastIndexOf("? "), window.lastIndexOf("! "))
                .takeIf { it > max / 3 }?.plus(1) ?: window.lastIndexOf(' ').takeIf { it > 0 } ?: max
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out
    }
}
