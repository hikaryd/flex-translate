package dev.flextranslate.foundation

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The request the app sends to OUR mediation backend for a Gemini Flash translate. It carries the
 * user *intent* (language pair + text + model id) — never a Gemini credential. The backend injects
 * auth, calls Gemini's `generateContent` server-side, and returns text only.
 *
 * See `docs/design/ws5-gemini-flash.md` §2.3-A for the wire contract.
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
 * The seam between [GeminiFlashTranslationProvider] and the operator-run backend. Implementations
 * are the ONLY place that touches the network for the cloud MT tier; this keeps the provider unit
 * testable (a fake client) and concentrates the "no embedded keys" invariant in one file.
 *
 * The result is an explicit sealed type so the provider can map every outcome to an honest
 * [TranslationResult] — success text, a backend-declined reason, or a transport failure — and
 * NEVER fabricate output.
 */
interface CloudMediationClient {
    fun translate(request: GeminiTranslateRequest, credential: CloudCredential): Result

    sealed interface Result {
        /** Backend returned a real translation produced by Gemini server-side. */
        data class Ok(val text: String, val modelId: String?) : Result

        /** Backend explicitly declined (rate limit, policy, safety) with a product-language message. */
        data class Refused(val userReason: String) : Result

        /** Transport/parse failure — network down mid-call, malformed response, timeout, etc. */
        data class Failed(val cause: String) : Result
    }
}

/**
 * Real backend-mediation client over [HttpURLConnection] (no extra HTTP dependency; the app already
 * avoids heavy libs). It POSTs the translate intent as JSON to the configured backend endpoint with
 * the app's own session token in `Authorization` — **never** a Gemini key, which the app does not
 * possess.
 *
 * Honesty contract: any non-2xx, malformed body, or `{ "ok": false }` maps to [Refused]/[Failed];
 * the caller surfaces an honest reason. Nothing here can invent a translation.
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
            // The app's own backend session identity — NOT a Gemini API key. The mediation backend
            // holds the Gemini credential in its server environment.
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

/** Build the mediated-translate JSON body. Extracted so the request shape is unit-testable. */
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
 * Parse the backend's response into an honest [CloudMediationClient.Result]. Shapes (§2.3-A):
 *  - success: `{ "ok": true, "text": "...", "modelId": "..." }`
 *  - declined: `{ "ok": false, "reason": "...", "userMessage": "..." }`
 *  - any non-2xx or malformed body → [Failed].
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
