package dev.flextranslate.foundation

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * BYOK direct client: POSTs a translate prompt straight to the public Gemini REST endpoint using
 * the user's own API key. The key travels ONLY in the `x-goog-api-key` request header — it is
 * NEVER logged, NEVER included in error messages, NEVER stored by this class.
 *
 * Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
 * Auth:     `x-goog-api-key: <user key>` header (key supplied by caller, never stored here)
 *
 * Error mapping (honest, per the geo-restriction reality):
 *  - HTTP 400 with "User location is not supported" → [DirectResult.GeoBlocked] (surfaced honestly)
 *  - HTTP 401 / 403                                 → [DirectResult.KeyRejected]
 *  - network / parse failure                        → [DirectResult.Failed]
 *  - success with non-empty text                    → [DirectResult.Ok]
 *
 * Security: [apiKey] is a function so the caller always fetches the latest value from secure
 * storage; this class never holds a reference to the key between calls. The key is passed only
 * to [setRequestProperty] and is not captured by any closure or logged.
 */
open class GeminiDirectClient(
    private val config: GeminiFlashConfig,
) {

    sealed interface DirectResult {
        data class Ok(val text: String) : DirectResult

        /** HTTP 400 with a geo-restriction body — direct Gemini is unavailable in this region. */
        data object GeoBlocked : DirectResult

        /** HTTP 401 or 403 — the key was rejected by Google. */
        data object KeyRejected : DirectResult

        /** Transport, parse, or unexpected server error. */
        data class Failed(val cause: String) : DirectResult
    }

    /**
     * Translate [text] for [languagePair] by calling Gemini directly.
     *
     * @param apiKey The user's Gemini API key retrieved from [GeminiKeyStore]. The caller is
     *   responsible for fetching it from secure storage immediately before this call so we never
     *   hold the key in memory longer than necessary. Must not be blank.
     */
    open fun translate(text: String, languagePair: String, apiKey: String): DirectResult {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        val endpoint = buildEndpoint(config.modelId)
        val body = buildDirectRequestBody(text, languagePair)
        return runCatching { post(endpoint, body, apiKey) }
            .getOrElse { t ->
                // Log the exception class/message — NOT the key, NOT the body.
                Log.w(TAG, "direct Gemini call failed: ${t.javaClass.simpleName}: ${t.message}")
                DirectResult.Failed(t.javaClass.simpleName)
            }
    }

    private fun post(endpoint: String, body: String, apiKey: String): DirectResult {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.timeoutMs
            readTimeout = config.timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            // The user's own Gemini API key in the standard Google AI header.
            // This is the ONLY place the key value is used; it is not logged or stored.
            setRequestProperty("x-goog-api-key", apiKey)
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            parseDirectResponse(status, payload)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "GeminiDirectClient"
        const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

        fun buildEndpoint(modelId: String): String = "$GEMINI_BASE/$modelId:generateContent"
    }
}

/**
 * Build the Gemini `generateContent` request body for a translation task.
 * The prompt encodes only the direction and text — no key, no credential.
 */
internal fun buildDirectRequestBody(text: String, languagePair: String): String {
    val (srcCode, tgtCode) = languagePair.split("->").let { parts ->
        Pair(parts.getOrElse(0) { "ru" }, parts.getOrElse(1) { "en" })
    }
    val srcName = languageDisplayName(srcCode)
    val tgtName = languageDisplayName(tgtCode)
    val prompt = "Translate the following $srcName text to $tgtName. " +
        "Reply with only the translation, no explanation:\n$text"

    val part = JSONObject().put("text", prompt)
    val parts = JSONArray().put(part)
    val content = JSONObject().put("parts", parts)
    val contents = JSONArray().put(content)
    return JSONObject().put("contents", contents).toString()
}

/**
 * Parse a Gemini `generateContent` response.
 *
 * Success shape: `{ "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }`
 * Error shapes:
 *  - HTTP 400 with `"User location is not supported"` in the error message → [GeoBlocked]
 *  - HTTP 401/403 → [KeyRejected]
 *  - anything else non-2xx or malformed → [Failed]
 */
internal fun parseDirectResponse(status: Int, payload: String): GeminiDirectClient.DirectResult {
    // Handle auth errors first — before trying to parse JSON.
    if (status == 401 || status == 403) return GeminiDirectClient.DirectResult.KeyRejected

    // HTTP 400: could be geo-restriction or a bad request.
    if (status == 400) {
        val lowerPayload = payload.lowercase()
        if (lowerPayload.contains("user location is not supported") ||
            lowerPayload.contains("location is not supported")
        ) {
            return GeminiDirectClient.DirectResult.GeoBlocked
        }
        val errorMsg = runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message") ?: "HTTP 400"
        }.getOrElse { "HTTP 400" }
        return GeminiDirectClient.DirectResult.Failed(errorMsg)
    }

    if (status !in 200..299) {
        return GeminiDirectClient.DirectResult.Failed("HTTP $status")
    }

    val json = runCatching { JSONObject(payload) }.getOrNull()
        ?: return GeminiDirectClient.DirectResult.Failed("unparseable response")

    val text = runCatching {
        json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }.getOrNull()

    if (text.isNullOrBlank()) {
        return GeminiDirectClient.DirectResult.Failed("empty or missing text in response")
    }
    return GeminiDirectClient.DirectResult.Ok(text.trim())
}

/** Map a language code to a display name for the translation prompt. */
private fun languageDisplayName(code: String): String = when (code) {
    "ru" -> "Russian"
    "en" -> "English"
    "zh" -> "Chinese"
    else -> code
}
