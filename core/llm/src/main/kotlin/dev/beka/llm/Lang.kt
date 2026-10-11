package dev.beka.llm

/**
 * The language Beka speaks to the user. The app sets [current] from its own language (English unless
 * the app runs in Indonesian); core modules read it for their user-facing lines and to tell models which
 * language a note for the user must be in. Prompts themselves stay English.
 */
enum class Lang(val tag: String, val englishName: String) {
    EN("en", "English"),
    ID("id", "Indonesian");

    companion object {
        @Volatile
        var current: Lang = EN

        /** A BCP 47 language tag or code; anything but Indonesian is English. Android may still say "in". */
        fun of(tag: String?): Lang = when (tag?.substringBefore('-')?.substringBefore('_')?.lowercase()) {
            "id", "in" -> ID
            else -> EN
        }
    }
}

/** [en] or [id], whichever language Beka currently speaks. For short user-facing lines in core modules. */
fun tr(en: String, id: String): String = if (Lang.current == Lang.ID) id else en
