package dev.beka.memory

import kotlin.math.ln

/**
 * Word-overlap relevance between a query and memory items, weighted by how rare each word is
 * across all memory (IDF), then nudged by use and recency. Stands in for vector search until
 * on-device embeddings exist (phase 5); with a few hundred personal notes it finds what matters.
 */
internal class Ranker(corpus: List<MemoryItem>, private val now: Long) {
    private val tokens: Map<Long, Set<String>> = corpus.associate { it.id to tokenize(it.text()) }
    private val docCount = corpus.size.coerceAtLeast(1)

    /** Number of items containing each word, for IDF. */
    private val frequency: Map<String, Int> = HashMap<String, Int>().also { counts ->
        tokens.values.forEach { words -> words.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    }

    /** 0 when no query word matches the item. */
    fun lexical(item: MemoryItem, query: Set<String>): Double {
        val words = tokens[item.id] ?: tokenize(item.text())
        return query.sumOf { q ->
            val hit = words.firstOrNull { w -> matches(q, w) } ?: return@sumOf 0.0
            idf(hit)
        }
    }

    /** Lexical score with a mild preference for items used often and touched recently. */
    fun score(item: MemoryItem, query: Set<String>): Double {
        val lexical = lexical(item, query)
        if (lexical == 0.0) return 0.0
        return lexical * usage(item) * recency(item)
    }

    fun usage(item: MemoryItem): Double = 1.0 + 0.1 * item.useCount.coerceAtMost(10)

    fun recency(item: MemoryItem): Double {
        val days = (now - (item.lastUsed ?: item.createdAt)).coerceAtLeast(0) / DAY_MS.toDouble()
        return 1.0 / (1.0 + days / 30.0)
    }

    private fun idf(word: String): Double = ln(1.0 + docCount.toDouble() / ((frequency[word] ?: 0) + 1))

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Lowercased words of 3+ letters, minus common Indonesian and English filler. */
        fun tokenize(text: String): Set<String> =
            text.lowercase().split(NON_WORD).filterTo(LinkedHashSet()) { it.length >= 3 && it !in STOPWORDS }

        /**
         * Same word, or one is a prefix of the other (at least 4 letters): Indonesian suffixes
         * such as -nya, -kan, -an ("kopinya", "kirimkan") still match their base word.
         */
        fun matches(a: String, b: String): Boolean {
            if (a == b) return true
            val (short, long) = if (a.length <= b.length) a to b else b to a
            return short.length >= 4 && long.startsWith(short)
        }

        private fun MemoryItem.text(): String = content + " " + appPackage.orEmpty().replace('.', ' ')

        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        private val STOPWORDS = setOf(
            // Indonesian, including casual forms
            "yang", "dan", "atau", "ini", "itu", "ada", "dengan", "untuk", "dari", "pada", "dalam", "akan", "sudah",
            "udah", "belum", "bisa", "juga", "aja", "saja", "nya", "lagi", "kalau", "kalo", "tapi", "biar", "buat",
            "gak", "nggak", "ngga", "tidak", "enggak", "mau", "pengen", "dong", "deh", "sih", "kok", "kan", "tuh",
            "gue", "gua", "elo", "lo", "kamu", "aku", "saya", "dia", "mereka", "kita", "kami", "apa", "gimana",
            "bagaimana", "kenapa", "mana", "siapa", "kapan", "tolong", "coba", "terus", "trus", "jadi", "sama", "kayak",
            "user", "pengguna",
            // English
            "the", "and", "for", "with", "that", "this", "are", "was", "you", "your", "from", "have", "has", "not",
            "but", "what", "how", "can", "please", "about", "into", "they", "them", "then", "than", "does", "did",
            // package names
            "com", "org", "app", "android",
        )
    }
}
