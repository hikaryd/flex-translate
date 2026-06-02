package dev.flextranslate.foundation

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Запрос, который приложение шлёт НАШЕМУ бэкенду-посреднику для перевода через Gemini Flash.
 * Несёт только *намерение* пользователя (пара языков + текст + id модели), но не credential Gemini.
 * Бэкенд сам подставляет авторизацию, дёргает `generateContent` Gemini на своей стороне и
 * возвращает только текст.
 *
 * Формат провода — см. `docs/design/ws5-gemini-flash.md` §2.3-A.
 */
data class GeminiTranslateRequest(
    val providerId: String,
    val modelId: String,
    val languagePair: String,
    val text: String,
    val thinkingLevel: String,
    val stream: Boolean = false,
)

/**
 * Шов между [GeminiFlashTranslationProvider] и бэкендом, который держит оператор. Реализации —
 * единственное место, которое лезет в сеть ради облачного MT; так провайдер остаётся тестируемым
 * (подсунул фейковый клиент) и инвариант «никаких вшитых ключей» живёт в одном файле.
 *
 * Результат — явный sealed-тип, чтобы провайдер мог честно отразить любой исход в
 * [TranslationResult] (текст успеха, причину отказа бэкенда или сбой транспорта) и НИКОГДА
 * не выдумывал вывод.
 */
interface CloudMediationClient {
    fun translate(request: GeminiTranslateRequest, credential: CloudCredential): Result

    sealed interface Result {
        /** Бэкенд вернул реальный перевод, сделанный Gemini на сервере. */
        data class Ok(val text: String, val modelId: String?) : Result

        /** Бэкенд явно отказал (лимит, политика, safety) с понятным пользователю сообщением. */
        data class Refused(val userReason: String) : Result

        /** Сбой транспорта или парсинга — сеть отвалилась на полпути, кривой ответ, таймаут и т.п. */
        data class Failed(val cause: String) : Result
    }
}

/**
 * Боевой клиент-посредник поверх [HttpURLConnection] (без лишней HTTP-зависимости — приложение и так
 * избегает тяжёлых либ). POST'ит намерение перевода в JSON на настроенный endpoint, кладя в
 * `Authorization` собственный сессионный токен приложения — но **никогда** ключ Gemini, которого у
 * приложения нет.
 *
 * Контракт честности: любой не-2xx, кривое тело или `{ "ok": false }` превращаются в [Refused]/[Failed],
 * а вызывающий показывает честную причину. Выдумать перевод тут нечем.
 */
class HttpCloudMediationClient(
    private val config: GeminiFlashConfig,
) : CloudMediationClient {

    override fun translate(
        request: GeminiTranslateRequest,
        credential: CloudCredential,
    ): CloudMediationClient.Result {
        val endpoint = config.translateEndpoint()
            ?: return CloudMediationClient.Result.Failed("backend endpoint not configured")

        return runCatching { post(endpoint, buildRequestBody(request), credential) }
            .getOrElse { t ->
                Log.w(TAG, "mediated translate failed", t)
                CloudMediationClient.Result.Failed(t.message ?: t.javaClass.simpleName)
            }
    }

    private fun post(
        endpoint: String,
        body: String,
        credential: CloudCredential,
    ): CloudMediationClient.Result {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.timeoutMs
            readTimeout = config.timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            // Это сессионная идентичность приложения для бэкенда, а НЕ ключ Gemini API. Ключ Gemini
            // лежит в серверном окружении бэкенда-посредника.
            setRequestProperty("Authorization", "Bearer ${credential.source}")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            parseResponse(status, payload)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "CloudMediationClient"
    }
}

/** Собирает JSON-тело запроса перевода. Вынесено отдельно, чтобы форму запроса можно было тестить. */
internal fun buildRequestBody(request: GeminiTranslateRequest): String =
    JSONObject().apply {
        put("providerId", request.providerId)
        put("modelId", request.modelId)
        put("languagePair", request.languagePair)
        put("text", request.text)
        put("thinkingLevel", request.thinkingLevel)
        put("stream", request.stream)
    }.toString()

/**
 * Разбирает ответ бэкенда в честный [CloudMediationClient.Result]. Формы (§2.3-A):
 *  - успех: `{ "ok": true, "text": "...", "modelId": "..." }`
 *  - отказ: `{ "ok": false, "reason": "...", "userMessage": "..." }`
 *  - любой не-2xx или кривое тело → [Failed].
 */
internal fun parseResponse(status: Int, payload: String): CloudMediationClient.Result {
    val json = runCatching { JSONObject(payload) }.getOrNull()
        ?: return CloudMediationClient.Result.Failed("HTTP $status: unparseable response")

    if (status !in 200..299) {
        val message = json.optString("userMessage").ifBlank { "HTTP $status" }
        return CloudMediationClient.Result.Refused(message)
    }
    if (!json.optBoolean("ok", false)) {
        val message = json.optString("userMessage")
            .ifBlank { json.optString("reason") }
            .ifBlank { "Облачный перевод отклонён сервером" }
        return CloudMediationClient.Result.Refused(message)
    }
    val text = json.optString("text")
    if (text.isBlank()) {
        return CloudMediationClient.Result.Failed("backend ok but empty text")
    }
    val modelId = json.optString("modelId").ifBlank { null }
    return CloudMediationClient.Result.Ok(text = text, modelId = modelId)
}
