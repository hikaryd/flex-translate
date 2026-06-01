package dev.flextranslate.ui.i18n

import android.content.Context
import java.util.Locale

/**
 * The interface language the whole UI chrome is rendered in. This is a RUNTIME app-level choice
 * (the in-app RU/EN toggle), independent of the device system locale and independent of the
 * source/target translation languages ([dev.flextranslate.ui.FlexLanguage]). Switching it flips
 * [LocalStrings] at the app root, so every screen recomposes in the new language without losing
 * state.
 */
enum class AppLanguage(val code: String, val nativeLabel: String) {
    RU("ru", "Русский"),
    EN("en", "English"),
    ;

    companion object {
        /**
         * The sensible default for a fresh install: Russian when the device's primary locale is
         * Russian, English otherwise. Used only when the user has not yet picked a language.
         */
        fun fromSystem(locale: Locale = Locale.getDefault()): AppLanguage =
            if (locale.language.equals(RU.code, ignoreCase = true)) RU else EN

        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: fromSystem()
    }
}

/**
 * Persists the user's [AppLanguage] choice across launches via [android.content.SharedPreferences].
 * Until the user picks explicitly, [load] falls back to [AppLanguage.fromSystem]. No Compose
 * dependency here — the composition holds the live state; this only reads/writes the durable value.
 */
class AppLanguageStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The persisted language, or the system-derived default when nothing has been chosen yet. */
    fun load(): AppLanguage {
        val stored = prefs.getString(KEY_LANGUAGE, null) ?: return AppLanguage.fromSystem()
        return AppLanguage.fromCode(stored)
    }

    fun save(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.code).apply()
    }

    private companion object {
        const val PREFS_NAME = "flex_ui_prefs"
        const val KEY_LANGUAGE = "app_language"
    }
}
