package dev.flextranslate.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты прямого пути BYOK (OWN_KEY) к Gemini:
 *  - Сборка запроса: правильный endpoint / header / форма body, ключа в body НЕТ
 *  - Разбор ответа: ok→Ok, гео-400→GeoBlocked, 401/403→KeyRejected, остальное→Failed
 *  - Маппинг ошибок: гео-блок и отклонённый ключ дают правильные честные причины
 *  - Гейтинг: нет ключа → блок; нет согласия → блок; офлайн → блок
 *  - Ключ не утекает: его нет ни в одном toString / reason / видимом в логах поле
 *
 * Все тесты — чистый JVM, без Android-фреймворка и реальных сетевых вызовов.
 */
class GeminiByokTest {

    // ---- In-memory фейк GeminiKeyStore ----------------------------------------------------------

    private class FakeKeyStore(initialKey: String? = null) : GeminiKeyStore {
        private var stored: String? = initialKey
        override fun saveKey(apiKey: String) { stored = apiKey }
        override fun loadKey(): String? = stored
        override fun clearKey() { stored = null }
    }

    // ---- сборка body прямого запроса ------------------------------------------------------------

    @Test
    fun `buildDirectRequestBody contains the text and language direction`() {
        val body = buildDirectRequestBody("Привет мир", "ru->en")
        assertTrue("body contains text", body.contains("Привет мир"))
        assertTrue("body references Russian", body.lowercase().contains("russian"))
        assertTrue("body references English", body.lowercase().contains("english"))
    }

    @Test
    fun `buildDirectRequestBody never contains an api key literal`() {
        val body = buildDirectRequestBody("Hello", "en->ru")
        val lower = body.lowercase()
        assertFalse("no apikey field", lower.contains("apikey"))
        assertFalse("no api_key field", lower.contains("api_key"))
        // Характерный префикс ключа Google не должен попасть в body.
        assertFalse("no AIza prefix", body.contains("AIza"))
        assertFalse("no x-goog-api-key in body", lower.contains("x-goog-api-key"))
    }

    @Test
    fun `buildDirectRequestBody wraps prompt in contents-parts shape`() {
        val body = buildDirectRequestBody("test", "ru->en")
        assertTrue("has 'contents' key", body.contains("\"contents\""))
        assertTrue("has 'parts' key", body.contains("\"parts\""))
        assertTrue("has 'text' key", body.contains("\"text\""))
    }

    // ---- разбор ответа --------------------------------------------------------------------------

    @Test
    fun `parseDirectResponse maps 2xx with candidates to Ok`() {
        val payload = """
            {
              "candidates": [{
                "content": {
                  "parts": [{"text": "Hello world"}]
                }
              }]
            }
        """.trimIndent()
        val result = parseDirectResponse(200, payload)
        assertTrue(result is GeminiDirectClient.DirectResult.Ok)
        assertEquals("Hello world", (result as GeminiDirectClient.DirectResult.Ok).text)
    }

    @Test
    fun `parseDirectResponse trims whitespace from translated text`() {
        val payload = """{"candidates":[{"content":{"parts":[{"text":"  Hello  "}]}}]}"""
        val result = parseDirectResponse(200, payload)
        assertEquals("Hello", (result as GeminiDirectClient.DirectResult.Ok).text)
    }

    @Test
    fun `parseDirectResponse maps 400 with location error to GeoBlocked`() {
        val payload = """{"error":{"code":400,"message":"User location is not supported for the API use."}}"""
        val result = parseDirectResponse(400, payload)
        assertTrue("should be GeoBlocked", result is GeminiDirectClient.DirectResult.GeoBlocked)
    }

    @Test
    fun `parseDirectResponse maps 400 with location error case-insensitively`() {
        val payload = """{"error":{"message":"user location is not supported"}}"""
        val result = parseDirectResponse(400, payload)
        assertTrue(result is GeminiDirectClient.DirectResult.GeoBlocked)
    }

    @Test
    fun `parseDirectResponse maps 401 to KeyRejected`() {
        val result = parseDirectResponse(401, """{"error":{"message":"API key not valid"}}""")
        assertTrue(result is GeminiDirectClient.DirectResult.KeyRejected)
    }

    @Test
    fun `parseDirectResponse maps 403 to KeyRejected`() {
        val result = parseDirectResponse(403, """{"error":{"message":"Permission denied"}}""")
        assertTrue(result is GeminiDirectClient.DirectResult.KeyRejected)
    }

    @Test
    fun `parseDirectResponse maps 400 non-location error to Failed`() {
        val payload = """{"error":{"message":"Invalid request body"}}"""
        val result = parseDirectResponse(400, payload)
        assertTrue(result is GeminiDirectClient.DirectResult.Failed)
    }

    @Test
    fun `parseDirectResponse maps 500 to Failed`() {
        val result = parseDirectResponse(500, """{"error":{"message":"Internal"}}""")
        assertTrue(result is GeminiDirectClient.DirectResult.Failed)
    }

    @Test
    fun `parseDirectResponse maps unparseable body to Failed`() {
        val result = parseDirectResponse(200, "<html>not json</html>")
        assertTrue(result is GeminiDirectClient.DirectResult.Failed)
    }

    @Test
    fun `parseDirectResponse maps empty text to Failed`() {
        val payload = """{"candidates":[{"content":{"parts":[{"text":""}]}}]}"""
        val result = parseDirectResponse(200, payload)
        assertTrue(result is GeminiDirectClient.DirectResult.Failed)
    }

    @Test
    fun `parseDirectResponse maps missing candidates to Failed`() {
        val result = parseDirectResponse(200, """{"candidates":[]}""")
        assertTrue(result is GeminiDirectClient.DirectResult.Failed)
    }

    // ---- маппинг честных причин -----------------------------------------------------------------

    @Test
    fun `GeoBlocked reason surfaced from provider does not contain any api key value`() {
        val reason = GeminiFlashTranslationProvider.REASON_GEO_BLOCKED
        assertFalse(reason.contains("AIza"))
        assertTrue("mentions backend or VPN", reason.contains("backend") || reason.contains("VPN"))
    }

    @Test
    fun `KeyRejected reason surfaced from provider does not contain any api key value`() {
        val reason = GeminiFlashTranslationProvider.REASON_KEY_REJECTED
        assertFalse(reason.contains("AIza"))
        // Должна упоминать «ключ», чтобы юзер понимал, что проверять.
        assertTrue("mentions key", reason.lowercase().contains("ключ"))
    }

    // ---- гейтинг: режим OWN_KEY -----------------------------------------------------------------

    private val now = 1_000_000L

    private fun ownKeyAllowedState() = CloudOptInState(
        providerId = GeminiFlashTranslationProvider.PROVIDER_ID,
        userConsented = true,
        disclosureAccepted = true,
        credential = null, // для OWN_KEY не нужен
        networkState = "online",
    )

    private fun ownKeyConfig() = GeminiFlashConfig(
        credentialMode = GeminiCredentialMode.OWN_KEY,
        backendBaseUrl = "", // для OWN_KEY не важен
    )

    /**
     * Фейковый direct-клиент: пишет вызовы и отдаёт заранее заданный результат.
     * Ключ приходит параметром, но НЕ сохраняется ни в одно поле — повторяем боевое
     * ограничение: ключ не должен пережить вызов.
     */
    private class RecordingDirectClient(
        private val result: GeminiDirectClient.DirectResult =
            GeminiDirectClient.DirectResult.Ok("Hello"),
    ) : GeminiDirectClient(GeminiFlashConfig()) {
        var callCount = 0
            private set
        var lastText: String? = null
            private set
        var lastPair: String? = null
            private set

        override fun translate(text: String, languagePair: String, apiKey: String): DirectResult {
            // Ключ принципиально НЕ сохраняем — это и есть контракт.
            callCount += 1
            lastText = text
            lastPair = languagePair
            return result
        }
    }

    private fun ownKeyProvider(
        state: CloudOptInState?,
        keyStore: GeminiKeyStore?,
        directClient: GeminiDirectClient,
        config: GeminiFlashConfig = ownKeyConfig(),
    ): GeminiFlashTranslationProvider {
        val gate = CloudCallGate(
            stateProvider = { state },
            config = config,
            keyStore = keyStore,
        )
        return GeminiFlashTranslationProvider(
            config = config,
            gate = gate,
            backend = NoOpMediationClient,
            directClient = directClient,
            keyStore = keyStore,
            clock = { now },
        )
    }

    @Test
    fun `own-key allowed — calls direct client and returns translated text`() {
        val client = RecordingDirectClient()
        val p = ownKeyProvider(ownKeyAllowedState(), FakeKeyStore("fake-key-abc"), client)

        val result = p.translate("Привет", "ru->en", "mid")

        assertEquals("Hello", result.text)
        assertNull(result.unsupportedReason)
        assertEquals(1, client.callCount)
        assertEquals("Привет", client.lastText)
        assertEquals("ru->en", client.lastPair)
    }

    @Test
    fun `own-key no stored key blocks honestly and makes no call`() {
        val client = RecordingDirectClient()
        val p = ownKeyProvider(ownKeyAllowedState(), FakeKeyStore(null), client)

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_NO_OWN_KEY, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `own-key no consent blocks honestly and makes no call`() {
        val client = RecordingDirectClient()
        val p = ownKeyProvider(
            ownKeyAllowedState().copy(userConsented = false),
            FakeKeyStore("fake-key"),
            client,
        )

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_NO_CONSENT, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `own-key offline blocks honestly and makes no call`() {
        val client = RecordingDirectClient()
        val p = ownKeyProvider(
            ownKeyAllowedState().copy(networkState = "offline"),
            FakeKeyStore("fake-key"),
            client,
        )

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_OFFLINE, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `own-key geo-blocked maps to honest reason`() {
        val client = RecordingDirectClient(GeminiDirectClient.DirectResult.GeoBlocked)
        val p = ownKeyProvider(ownKeyAllowedState(), FakeKeyStore("fake-key"), client)

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(GeminiFlashTranslationProvider.REASON_GEO_BLOCKED, result.unsupportedReason)
    }

    @Test
    fun `own-key key-rejected maps to honest reason`() {
        val client = RecordingDirectClient(GeminiDirectClient.DirectResult.KeyRejected)
        val p = ownKeyProvider(ownKeyAllowedState(), FakeKeyStore("fake-key"), client)

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(GeminiFlashTranslationProvider.REASON_KEY_REJECTED, result.unsupportedReason)
    }

    @Test
    fun `own-key direct failure maps to honest reason`() {
        val client = RecordingDirectClient(GeminiDirectClient.DirectResult.Failed("timeout"))
        val p = ownKeyProvider(ownKeyAllowedState(), FakeKeyStore("fake-key"), client)

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(GeminiFlashTranslationProvider.REASON_DIRECT_FAILED, result.unsupportedReason)
    }

    // ---- ключа нет ни в reason, ни в toString ---------------------------------------------------

    @Test
    fun `no reason string contains a realistic api key prefix`() {
        listOf(
            CloudCallGate.REASON_NO_OWN_KEY,
            GeminiFlashTranslationProvider.REASON_GEO_BLOCKED,
            GeminiFlashTranslationProvider.REASON_KEY_REJECTED,
            GeminiFlashTranslationProvider.REASON_DIRECT_FAILED,
        ).forEach { reason ->
            assertFalse("reason must not contain AIza: $reason", reason.contains("AIza"))
            assertFalse("reason must not contain AQ.: $reason", reason.contains("AQ."))
        }
    }

    @Test
    fun `FakeKeyStore does not expose key in toString`() {
        val store = FakeKeyStore("super-secret-key-AIzaXYZ")
        val str = store.toString()
        assertFalse("toString must not contain the key value", str.contains("AIzaXYZ"))
        assertFalse("toString must not contain 'super-secret'", str.contains("super-secret"))
    }

    // ---- гейт OWN_KEY: бэкенд не требуется -------------------------------------------------------

    @Test
    fun `own-key mode no backend configured is not a blocking condition`() {
        val client = RecordingDirectClient()
        // в конфиге нет URL бэкенда — блокировать не должно
        val p = ownKeyProvider(
            ownKeyAllowedState(),
            FakeKeyStore("fake-key"),
            client,
            config = GeminiFlashConfig(
                credentialMode = GeminiCredentialMode.OWN_KEY,
                backendBaseUrl = "",
            ),
        )

        val result = p.translate("Привет", "ru->en", "mid")

        assertEquals("Hello", result.text)
        assertEquals(1, client.callCount)
    }

    // ---- backend mediation работает и после добавления флага режима ------------------------------

    @Test
    fun `backend mediation mode still requires a backend endpoint`() {
        val mediationClient = object : CloudMediationClient {
            var callCount = 0
            override fun translate(
                request: GeminiTranslateRequest,
                credential: CloudCredential,
            ): CloudMediationClient.Result {
                callCount += 1
                return CloudMediationClient.Result.Ok("Translated", "gemini-3.5-flash")
            }
        }
        val config = GeminiFlashConfig(
            credentialMode = GeminiCredentialMode.BACKEND_MEDIATION,
            backendBaseUrl = "",
        )
        val gate = CloudCallGate(stateProvider = { ownKeyAllowedState() }, config = config)
        val p = GeminiFlashTranslationProvider(
            config = config,
            gate = gate,
            backend = mediationClient,
            clock = { now },
        )

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_NO_BACKEND, result.unsupportedReason)
        assertEquals(0, mediationClient.callCount)
    }

    // ---- хелперы --------------------------------------------------------------------------------

    private object NoOpMediationClient : CloudMediationClient {
        override fun translate(
            request: GeminiTranslateRequest,
            credential: CloudCredential,
        ): CloudMediationClient.Result = CloudMediationClient.Result.Failed("should not be called")
    }
}
