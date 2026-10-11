package dev.beka.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import dev.beka.R
import dev.beka.llm.Lang
import java.util.Locale

/**
 * The language Beka's screens are in: English, or Indonesian when the phone (or, on Android 13+, Beka's own
 * language setting) asks for it. Core modules follow it through [Lang.current], kept in step by [sync].
 */
object AppLanguage {
    /** The choices in Settings; null follows the phone. */
    val choices: List<Lang?> = listOf(null, Lang.EN, Lang.ID)

    /** Android 13+ keeps a language per app; older versions always follow the phone. */
    val canChoose: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The language picked for Beka, or null when it follows the phone. */
    fun chosen(context: Context): Lang? {
        if (!canChoose) return null
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else Lang.of(locales[0].language)
    }

    /** Sets Beka's language (null = follow the phone). Android recreates the screens in the new language. */
    fun choose(context: Context, lang: Lang?) {
        if (!canChoose) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (lang == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(lang.tag)
    }

    /** The language the app's resources resolved to, which is what the user sees. */
    fun current(context: Context): Lang = Lang.of(context.getString(R.string.lang_tag))

    /** Makes core modules (tool lines, rule cards, prompts) speak the same language as the screens. */
    fun sync(context: Context) {
        Lang.current = current(context)
    }

    /** For the speech recognizer and text-to-speech: the phone's own English variant when it has one. */
    fun speechLocale(): Locale = when (Lang.current) {
        Lang.ID -> Locale.forLanguageTag("id-ID")
        Lang.EN -> Locale.getDefault().takeIf { it.language == "en" && it.country.isNotEmpty() } ?: Locale.US
    }
}
