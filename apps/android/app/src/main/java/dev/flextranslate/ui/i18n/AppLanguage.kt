package dev.flextranslate.ui.i18n

import android.content.Context
import java.util.Locale

/**
 * Язык, на котором рисуется весь интерфейс. Это выбор уровня приложения В РАНТАЙМЕ (тумблер RU/EN
 * внутри приложения), не зависящий ни от системной локали устройства, ни от языков перевода
 * (источник/цель — [dev.flextranslate.ui.FlexLanguage]). Переключение меняет [LocalStrings] в корне
 * приложения, и все экраны рекомпозятся на новом языке, не теряя состояния.
 */
enum class AppLanguage(val code: String, val nativeLabel: String) {
    RU("ru", "Русский"),
    EN("en", "English"),
    ;

    companion object {
        /**
         * Разумный дефолт для свежей установки: русский, если основная локаль устройства русская,
         * иначе английский. Применяется только пока пользователь сам не выбрал язык.
         */
        fun fromSystem(locale: Locale = Locale.getDefault()): AppLanguage =
            if (locale.language.equals(RU.code, ignoreCase = true)) RU else EN

        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: fromSystem()
    }
}

/**
 * Хранит выбор [AppLanguage] между запусками через [android.content.SharedPreferences]. Пока
 * пользователь не выбрал явно, [load] откатывается к [AppLanguage.fromSystem]. Compose здесь не
 * нужен — живое состояние держит композиция, а это только читает/пишет постоянное значение.
 */
class AppLanguageStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Сохранённый язык, либо дефолт из системной локали, если ещё ничего не выбрано. */
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
