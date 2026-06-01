package dev.flextranslate.foundation

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage seam for the user's Gemini API key (BYOK path).
 *
 * The interface keeps the production implementation Android-only (EncryptedSharedPreferences /
 * AES-256-GCM + Android KeyStore) while letting unit tests inject a simple in-memory fake —
 * no Android instrumentation required.
 *
 * Security contract:
 * - The key is NEVER stored in plaintext SharedPreferences, files, or logs.
 * - [saveKey] / [loadKey] / [clearKey] are the ONLY authorized entry points.
 * - Nothing in this file logs or prints the key value; callers must honor the same constraint.
 * - [loadKey] returns null when no key is saved (the caller then gates the OWN_KEY path).
 */
interface GeminiKeyStore {
    /** Encrypt and persist [apiKey]. Overwrites any previously stored key. */
    fun saveKey(apiKey: String)

    /**
     * Retrieve the stored key, or null if none has been saved.
     * The returned value is sensitive — the caller must not log it.
     */
    fun loadKey(): String?

    /** Erase the stored key. After this [loadKey] returns null. */
    fun clearKey()

    /** True when a non-blank key is currently stored. */
    fun hasKey(): Boolean = loadKey()?.isNotBlank() == true
}

/**
 * Production implementation backed by [EncryptedSharedPreferences].
 *
 * The master key uses AES-256-GCM keyed through the Android KeyStore hardware-backed keychain.
 * Each value is encrypted with AES-256-GCM; the key name itself is encrypted with AES-256-SIV.
 * This matches the security-crypto 1.1.0-alpha06 defaults.
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
        /** The preference entry name. The name itself is AES-SIV encrypted on disk. */
        const val PREF_KEY = "k"
    }
}
