package dev.flextranslate.foundation

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * BYOK-клиент: шлёт промпт на перевод прямо в публичный REST-эндпоинт Gemini под ключом самого
 * пользователя. Ключ живёт только в заголовке `x-goog-api-key` — нигде не логируется, не попадает
 * в тексты ошибок и не хранится в этом классе.
 *
 * Endpoint: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
 * Auth:     заголовок `x-goog-api-key: <user key>` (ключ даёт вызывающий, тут не храним)
 *
 * Раскладка ошибок (честная, с поправкой на гео-ограничения):
 *  - HTTP 400 с "User location is not supported" → [DirectResult.GeoBlocked] (показываем как есть)
 *  - HTTP 401 / 403                              → [DirectResult.KeyRejected]
 *  - сбой сети/разбора                            → [DirectResult.Failed]
 *  - успех с непустым текстом                     → [DirectResult.Ok]
 *
 * Безопасность: ключ передаётся аргументом, чтобы вызывающий каждый раз доставал свежее значение
 * из защищённого хранилища; класс не держит ссылку на ключ между вызовами. Ключ уходит только в
 * [setRequestProperty], не захватывается ни одним замыканием и не логируется.
 */
open class GeminiDirectClient(
    private val config: GeminiFlashConfig,
) {

    sealed interface DirectResult {
        data class Ok(val text: String) : DirectResult

        /** HTTP 400 с телом про гео-ограничение — прямой Gemini в этом регионе недоступен. */
        data object GeoBlocked : DirectResult

        /** HTTP 401 или 403 — Google отверг ключ. */
        data object KeyRejected : DirectResult

        /** Сбой транспорта, разбора или неожиданная ошибка сервера. */
        data class Failed(val cause: String) : DirectResult
    }

    /**
     * Переводит [text] для пары [languagePair] прямым вызовом Gemini.
     *
     * @param apiKey Gemini API-ключ пользователя из [GeminiKeyStore]. Вызывающий обязан достать его
     *   из защищённого хранилища прямо перед вызовом, чтобы ключ не висел в памяти дольше нужного.
     *   Не должен быть пустым.
     */
    open fun translate(text: String, languagePair: String, apiKey: String): DirectResult {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        val endpoint = buildEndpoint(config.modelId)
        val body = buildDirectRequestBody(text, languagePair)
        return runCatching { post(endpoint, body, apiKey) }
            .getOrElse { t ->
                // Логируем класс/сообщение исключения — но не ключ и не тело запроса.
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
            // Ключ пользователя в стандартном заголовке Google AI.
            // Единственное место, где используется значение ключа; не логируется и не сохраняется.
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
 * Собирает тело запроса Gemini `generateContent` для задачи перевода.
 * В промпте только направление и текст — ни ключа, ни учётных данных.
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
 * Разбирает ответ Gemini `generateContent`.
 *
 * Формат успеха: `{ "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }`
 * Форматы ошибок:
 *  - HTTP 400 с `"User location is not supported"` в сообщении → [GeoBlocked]
 *  - HTTP 401/403 → [KeyRejected]
 *  - всё остальное не-2xx или битое → [Failed]
 */
internal fun parseDirectResponse(status: Int, payload: String): GeminiDirectClient.DirectResult {
    // Сначала ошибки авторизации — ещё до попытки разобрать JSON.
    if (status == 401 || status == 403) return GeminiDirectClient.DirectResult.KeyRejected

    // HTTP 400: либо гео-ограничение, либо кривой запрос.
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

/** Превращает код языка в название для промпта перевода. */
private fun languageDisplayName(code: String): String = when (code) {
    "ru" -> "Russian"
    "en" -> "English"
    "zh" -> "Chinese"
    else -> code
}
