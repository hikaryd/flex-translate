package dev.flextranslate.foundation

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Шов безопасного хранения для пользовательского ключа Gemini (путь BYOK).
 *
 * Интерфейс оставляет боевую реализацию только для Android (EncryptedSharedPreferences /
 * AES-256-GCM + Android KeyStore), но позволяет юнит-тестам подсунуть простой in-memory фейк —
 * без Android-инструментации.
 *
 * Контракт безопасности:
 * - Ключ НИКОГДА не лежит в открытом виде в SharedPreferences, файлах или логах.
 * - [saveKey] / [loadKey] / [clearKey] — ЕДИНСТВЕННЫЕ разрешённые точки входа.
 * - Ничто в этом файле не логирует и не печатает ключ; вызывающие обязаны соблюдать то же.
 * - [loadKey] возвращает null, если ключ не сохранён (тогда вызывающий гейтит путь OWN_KEY).
 */
interface GeminiKeyStore {
    /** Зашифровать и сохранить [apiKey]. Перетирает прежний ключ, если был. */
    fun saveKey(apiKey: String)

    /**
     * Достать сохранённый ключ или null, если ничего не сохранено.
     * Значение чувствительное — вызывающий не должен его логировать.
     */
    fun loadKey(): String?

    /** Стереть сохранённый ключ. После этого [loadKey] вернёт null. */
    fun clearKey()

    /** True, когда сейчас хранится непустой ключ. */
    fun hasKey(): Boolean = loadKey()?.isNotBlank() == true
}

/**
 * Боевая реализация поверх [EncryptedSharedPreferences].
 *
 * Мастер-ключ — AES-256-GCM через аппаратный keychain Android KeyStore. Каждое значение шифруется
 * AES-256-GCM; само имя ключа — AES-256-SIV. Это дефолты security-crypto 1.1.0-alpha06.
 */
class AndroidGeminiKeyStore(context: Context) : GeminiKeyStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun saveKey(apiKey: String) {
        prefs.edit().putString(PREF_KEY, apiKey).apply()
    }

    override fun loadKey(): String? = prefs.getString(PREF_KEY, null)

    override fun clearKey() {
        prefs.edit().remove(PREF_KEY).apply()
    }

    private companion object {
        const val MASTER_KEY_ALIAS = "_gemini_byok_master_key_"
        const val PREFS_FILE_NAME = "gemini_byok_prefs"
        /** Имя записи в prefs. Само имя на диске зашифровано AES-SIV. */
        const val PREF_KEY = "k"
    }
}
