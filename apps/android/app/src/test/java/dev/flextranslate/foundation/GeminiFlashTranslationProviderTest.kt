package dev.flextranslate.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Детерминированные JVM-тесты облачного MT-слоя WS5: предусловия [CloudCallGate], контракт честности
 * и гейтинга [GeminiFlashTranslationProvider], а также хелперы сборки запроса / разбора ответа.
 * Фейковый [CloudMediationClient] записывает вызовы, чтобы убедиться: при заблокированном гейте
 * сетевого вызова НЕТ — ключевой инвариант «никакого тихого трафика, никаких выдумок» из §2/§3.
 */
class GeminiFlashTranslationProviderTest {

    private val now = 1_000_000L

    /** Записывает каждый translate() и возвращает заранее заданный результат. */
    private class RecordingClient(
        private val result: CloudMediationClient.Result =
            CloudMediationClient.Result.Ok(text = "Hello, how are you?", modelId = "gemini-3.5-flash"),
    ) : CloudMediationClient {
        val requests = mutableListOf<GeminiTranslateRequest>()
        var callCount = 0
            private set

        override fun translate(
            request: GeminiTranslateRequest,
            credential: CloudCredential,
        ): CloudMediationClient.Result {
            callCount += 1
            requests.add(request)
            return result
        }
    }

    private fun configWithBackend(): GeminiFlashConfig =
        GeminiFlashConfig(backendBaseUrl = "https://flex-backend.example.com")

    private fun allowedState(): CloudOptInState = CloudOptInState(
        providerId = GeminiFlashTranslationProvider.PROVIDER_ID,
        userConsented = true,
        disclosureAccepted = true,
        credential = CloudCredential(source = "backend_ephemeral_token", expiresAtEpochMs = now + 60_000),
        networkState = "online",
    )

    private fun provider(
        state: CloudOptInState?,
        client: CloudMediationClient,
        config: GeminiFlashConfig = configWithBackend(),
    ): GeminiFlashTranslationProvider {
        val gate = CloudCallGate(stateProvider = { state }, config = config)
        return GeminiFlashTranslationProvider(
            config = config,
            gate = gate,
            backend = client,
            clock = { now },
        )
    }

    // ---- Гейтинг: каждый путь блокировки даёт честную причину и НЕ делает вызова -----------------

    @Test
    fun `no backend endpoint blocks honestly and makes no call`() {
        val client = RecordingClient()
        val p = provider(allowedState(), client, config = GeminiFlashConfig(backendBaseUrl = ""))

        val result = p.translate("Здравствуйте", "ru->en", "mid")

        assertNull("no fabricated text", result.text)
        assertEquals(CloudCallGate.REASON_NO_BACKEND, result.unsupportedReason)
        assertEquals("no network call when gate blocks", 0, client.callCount)
    }

    @Test
    fun `missing consent blocks honestly and makes no call`() {
        val client = RecordingClient()
        val p = provider(allowedState().copy(userConsented = false), client)

        val result = p.translate("Здравствуйте", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_NO_CONSENT, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `missing disclosure blocks honestly and makes no call`() {
        val client = RecordingClient()
        val p = provider(allowedState().copy(disclosureAccepted = false), client)

        val result = p.translate("Здравствуйте", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_NO_DISCLOSURE, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `offline blocks honestly and makes no call`() {
        val client = RecordingClient()
        val p = provider(allowedState().copy(networkState = "offline"), client)

        val result = p.translate("Здравствуйте", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_OFFLINE, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `missing or expired token blocks honestly and makes no call`() {
        val client = RecordingClient()
        val expired = allowedState().copy(
            credential = CloudCredential(source = "backend_ephemeral_token", expiresAtEpochMs = now - 1),
        )
        val p = provider(expired, client)

        val result = p.translate("Здравствуйте", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_NO_TOKEN, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `unknown provider state blocks honestly and makes no call`() {
        val client = RecordingClient()
        val p = provider(state = null, client = client)

        val result = p.translate("Здравствуйте", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_DISABLED, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `unsupported language pair blocks before any call`() {
        val client = RecordingClient()
        val p = provider(allowedState(), client)

        val result = p.translate("hello", "en->fr", "mid")

        assertNull(result.text)
        assertTrue(result.unsupportedReason?.contains("en->fr") == true)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `blank text returns neither text nor reason and makes no call`() {
        val client = RecordingClient()
        val p = provider(allowedState(), client)

        val result = p.translate("   ", "ru->en", "mid")

        assertNull(result.text)
        assertNull(result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    // ---- Разрешённый путь: посредничает и возвращает реальный текст, собрав верный запрос --------

    @Test
    fun `allowed call mediates and returns backend text`() {
        val client = RecordingClient()
        val p = provider(allowedState(), client)

        val result = p.translate("Здравствуйте, как у вас дела?", "ru->en", "mid")

        assertEquals("Hello, how are you?", result.text)
        assertNull(result.unsupportedReason)
        assertEquals(1, client.callCount)
        val request = client.requests.single()
        assertEquals(GeminiFlashTranslationProvider.PROVIDER_ID, request.providerId)
        assertEquals("gemini-3.5-flash", request.modelId)
        assertEquals("ru->en", request.languagePair)
        assertEquals("Здравствуйте, как у вас дела?", request.text)
        assertEquals("low", request.thinkingLevel)
    }

    @Test
    fun `backend refusal surfaces the backend user reason`() {
        val client = RecordingClient(
            CloudMediationClient.Result.Refused("Облачный перевод временно недоступен"),
        )
        val p = provider(allowedState(), client)

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals("Облачный перевод временно недоступен", result.unsupportedReason)
        assertEquals(1, client.callCount)
    }

    @Test
    fun `backend failure surfaces an honest generic reason, not fabricated text`() {
        val client = RecordingClient(CloudMediationClient.Result.Failed("timeout"))
        val p = provider(allowedState(), client)

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals("Облачный перевод недоступен", result.unsupportedReason)
        assertEquals(1, client.callCount)
    }

    // ---- Хелперы сборки запроса / разбора ответа (настоящий org.json в classpath тестов) ---------

    @Test
    fun `buildRequestBody emits intent fields and no api key`() {
        val body = buildRequestBody(
            GeminiTranslateRequest(
                providerId = "gemini-flash-cloud",
                modelId = "gemini-3.5-flash",
                languagePair = "ru->en",
                text = "Здравствуйте",
                thinkingLevel = "low",
                stream = false,
            ),
        )
        assertTrue(body.contains("\"languagePair\":\"ru->en\""))
        assertTrue(body.contains("\"modelId\":\"gemini-3.5-flash\""))
        assertTrue(body.contains("\"text\":\"Здравствуйте\""))
        // В теле запроса никогда не должно быть поля с ключом/токеном Gemini.
        val lower = body.lowercase()
        assertFalse(lower.contains("apikey"))
        assertFalse(lower.contains("api_key"))
        assertFalse(lower.contains("aiza"))
    }

    @Test
    fun `parseResponse maps ok true to Ok`() {
        val r = parseResponse(200, """{"ok":true,"text":"Hello","modelId":"gemini-3.5-flash"}""")
        assertTrue(r is CloudMediationClient.Result.Ok)
        assertEquals("Hello", (r as CloudMediationClient.Result.Ok).text)
        assertEquals("gemini-3.5-flash", r.modelId)
    }

    @Test
    fun `parseResponse maps ok false to Refused with user message`() {
        val r = parseResponse(200, """{"ok":false,"reason":"rate_limited","userMessage":"Недоступно"}""")
        assertTrue(r is CloudMediationClient.Result.Refused)
        assertEquals("Недоступно", (r as CloudMediationClient.Result.Refused).userReason)
    }

    @Test
    fun `parseResponse maps non-2xx to Refused`() {
        val r = parseResponse(429, """{"userMessage":"Слишком много запросов"}""")
        assertTrue(r is CloudMediationClient.Result.Refused)
        assertEquals("Слишком много запросов", (r as CloudMediationClient.Result.Refused).userReason)
    }

    @Test
    fun `parseResponse maps unparseable body to Failed`() {
        val r = parseResponse(200, "<html>not json</html>")
        assertTrue(r is CloudMediationClient.Result.Failed)
    }

    @Test
    fun `parseResponse maps ok true with empty text to Failed`() {
        val r = parseResponse(200, """{"ok":true,"text":""}""")
        assertTrue(r is CloudMediationClient.Result.Failed)
    }

    // ---- Сборка endpoint из конфига -------------------------------------------------------------

    @Test
    fun `translateEndpoint is null without a backend and joins cleanly with one`() {
        assertNull(GeminiFlashConfig(backendBaseUrl = "").translateEndpoint())
        assertEquals(
            "https://b.example.com/v1/cloud/translate",
            GeminiFlashConfig(backendBaseUrl = "https://b.example.com/").translateEndpoint(),
        )
    }
}
