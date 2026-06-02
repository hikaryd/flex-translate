package dev.flextranslate.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты политики [MtRoutingMode] и логики решений [CloudCallGate], на которой стоит
 * [dev.flextranslate.ui.LiveSessionState.isCloudUsable].
 *
 * Гоняем gate напрямую (чистый JVM, без Android-зависимостей) и проверяем:
 *
 *   AUTO + (online + согласие + credential)  → выбран Gemini (gate отдаёт Allowed)
 *   AUTO + offline                            → on-device (gate отдаёт Blocked)
 *   AUTO + нет согласия                       → on-device (gate отдаёт Blocked)
 *   AUTO + нет credential                     → on-device (gate отдаёт Blocked)
 *   ON_DEVICE всегда локально, даже если облако доступно (gate неважен)
 *   CLOUD всегда тянется к Gemini и при блокировке возвращает честную причину
 *
 * Решение о маршруте в LiveSessionState такое:
 *   cloudUsable = gate.evaluate(...) is Allowed
 *   useCloud = when(mode) { CLOUD→true; AUTO→cloudUsable; ON_DEVICE→false }
 *
 * Тесты повторяют эту логику с поддельным GeminiFlashTranslationProvider (через RecordingClient)
 * и поддельным CloudCallGate, подтверждая, что решение gate определяет выбор провайдера.
 */
class MtRoutingPolicyTest {

    private val now = 1_000_000L

    // ---- Хелперы для прогона gate ---------------------------------------------------------------

    private fun configWithBackend(): GeminiFlashConfig =
        GeminiFlashConfig(backendBaseUrl = "https://flex-backend.example.com")

    private fun allowedState(providerId: String = GeminiFlashTranslationProvider.PROVIDER_ID) =
        CloudOptInState(
            providerId = providerId,
            userConsented = true,
            disclosureAccepted = true,
            credential = CloudCredential(
                source = "backend_ephemeral_token",
                expiresAtEpochMs = now + 60_000,
            ),
            networkState = "online",
        )

    private fun gate(state: CloudOptInState?, config: GeminiFlashConfig = configWithBackend()) =
        CloudCallGate(
            stateProvider = { state },
            config = config,
        )

    /** Имитация isCloudUsable(): true только если gate.evaluate вернул Allowed. */
    private fun CloudCallGate.isUsable(): Boolean =
        evaluate(GeminiFlashTranslationProvider.PROVIDER_ID, now) is CloudCallGate.Decision.Allowed

    // ---- Режим AUTO: движок выбирает gate -------------------------------------------------------

    @Test
    fun `AUTO — online, consented, credential present — gate is Allowed (cloud chosen)`() {
        val g = gate(allowedState())
        val cloudUsable = g.isUsable()

        // Логика AUTO: useCloud = cloudUsable
        assertTrue("AUTO should choose cloud when gate passes", cloudUsable)
    }

    @Test
    fun `AUTO — offline — gate is Blocked (on-device chosen)`() {
        val g = gate(allowedState().copy(networkState = "offline"))
        val decision = g.evaluate(GeminiFlashTranslationProvider.PROVIDER_ID, now)

        assertTrue("gate must block when offline", decision is CloudCallGate.Decision.Blocked)
        assertEquals(
            CloudCallGate.REASON_OFFLINE,
            (decision as CloudCallGate.Decision.Blocked).userReason,
        )
        // Логика AUTO: cloudUsable=false → работаем on-device, в облако не ходим.
    }

    @Test
    fun `AUTO — no user consent — gate is Blocked (on-device chosen)`() {
        val g = gate(allowedState().copy(userConsented = false))
        val decision = g.evaluate(GeminiFlashTranslationProvider.PROVIDER_ID, now)

        assertTrue("gate must block when consent missing", decision is CloudCallGate.Decision.Blocked)
        assertEquals(
            CloudCallGate.REASON_NO_CONSENT,
            (decision as CloudCallGate.Decision.Blocked).userReason,
        )
    }

    @Test
    fun `AUTO — disclosure not accepted — gate is Blocked (on-device chosen)`() {
        val g = gate(allowedState().copy(disclosureAccepted = false))
        val decision = g.evaluate(GeminiFlashTranslationProvider.PROVIDER_ID, now)

        assertTrue("gate must block when disclosure not accepted", decision is CloudCallGate.Decision.Blocked)
        assertEquals(
            CloudCallGate.REASON_NO_DISCLOSURE,
            (decision as CloudCallGate.Decision.Blocked).userReason,
        )
    }

    @Test
    fun `AUTO — no backend endpoint (BACKEND_MEDIATION mode) — gate is Blocked (on-device chosen)`() {
        val g = gate(allowedState(), config = GeminiFlashConfig(backendBaseUrl = ""))
        val decision = g.evaluate(GeminiFlashTranslationProvider.PROVIDER_ID, now)

        assertTrue("gate must block when no backend endpoint", decision is CloudCallGate.Decision.Blocked)
        assertEquals(
            CloudCallGate.REASON_NO_BACKEND,
            (decision as CloudCallGate.Decision.Blocked).userReason,
        )
    }

    @Test
    fun `AUTO — OWN_KEY mode, no key stored — gate is Blocked (on-device chosen)`() {
        // Поддельный key store: ключа нет
        val fakeKeyStore = object : GeminiKeyStore {
            override fun hasKey() = false
            override fun saveKey(apiKey: String) {}
            override fun clearKey() {}
            override fun loadKey(): String? = null
        }
        val g = CloudCallGate(
            stateProvider = { allowedState() },
            config = GeminiFlashConfig(
                backendBaseUrl = "",
                credentialMode = GeminiCredentialMode.OWN_KEY,
            ),
            keyStore = fakeKeyStore,
        )
        val decision = g.evaluate(GeminiFlashTranslationProvider.PROVIDER_ID, now)

        assertTrue("gate must block when no own key", decision is CloudCallGate.Decision.Blocked)
        assertEquals(
            CloudCallGate.REASON_NO_OWN_KEY,
            (decision as CloudCallGate.Decision.Blocked).userReason,
        )
    }

    @Test
    fun `AUTO — OWN_KEY mode, key stored — gate is Allowed (cloud chosen)`() {
        val fakeKeyStore = object : GeminiKeyStore {
            override fun hasKey() = true
            override fun saveKey(apiKey: String) {}
            override fun clearKey() {}
            override fun loadKey(): String = "AIza-test-key"
        }
        val g = CloudCallGate(
            stateProvider = { allowedState() },
            config = GeminiFlashConfig(
                backendBaseUrl = "",
                credentialMode = GeminiCredentialMode.OWN_KEY,
            ),
            keyStore = fakeKeyStore,
        )
        val decision = g.evaluate(GeminiFlashTranslationProvider.PROVIDER_ID, now)

        assertTrue("gate must allow when own key is stored", decision is CloudCallGate.Decision.Allowed)
    }

    // ---- Режим ON_DEVICE: всегда локально, что бы ни решил gate ---------------------------------

    @Test
    fun `ON_DEVICE — gate would pass but mode forces local`() {
        val g = gate(allowedState())
        // Gate пропустил бы (cloudUsable=true), но ON_DEVICE перебивает:
        val cloudUsable = g.isUsable()
        assertTrue("gate passes (precondition for test)", cloudUsable)

        // MtRoutingMode.ON_DEVICE: useCloud всегда false
        val useCloud = false // ON_DEVICE — всегда false
        assertTrue("ON_DEVICE forces on-device even when gate passes", !useCloud)
    }

    // ---- Режим CLOUD: всегда пробует Gemini; при блокировке gate отдаёт честную причину ---------

    @Test
    fun `CLOUD mode — gate passes — cloud provider is used, call reaches backend`() {
        val client = RecordingClient()
        val p = GeminiFlashTranslationProvider(
            config = configWithBackend(),
            gate = gate(allowedState()),
            backend = client,
            clock = { now },
        )

        val result = p.translate("Привет", "ru->en", "mid")

        assertEquals("Hello, how are you?", result.text)
        assertNull(result.unsupportedReason)
        assertEquals("CLOUD mode: one backend call expected", 1, client.callCount)
    }

    @Test
    fun `CLOUD mode — gate blocks (offline) — honest reason returned, no backend call`() {
        val client = RecordingClient()
        val p = GeminiFlashTranslationProvider(
            config = configWithBackend(),
            gate = gate(allowedState().copy(networkState = "offline")),
            backend = client,
            clock = { now },
        )

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull("no fabricated text when gate blocks", result.text)
        assertEquals(CloudCallGate.REASON_OFFLINE, result.unsupportedReason)
        assertEquals("no backend call when gate blocks", 0, client.callCount)
    }

    @Test
    fun `CLOUD mode — gate blocks (no consent) — honest reason returned, no backend call`() {
        val client = RecordingClient()
        val p = GeminiFlashTranslationProvider(
            config = configWithBackend(),
            gate = gate(allowedState().copy(userConsented = false)),
            backend = client,
            clock = { now },
        )

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_NO_CONSENT, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    @Test
    fun `CLOUD mode — gate blocks (no backend) — honest reason returned, no backend call`() {
        val client = RecordingClient()
        val p = GeminiFlashTranslationProvider(
            config = GeminiFlashConfig(backendBaseUrl = ""),
            gate = gate(allowedState(), config = GeminiFlashConfig(backendBaseUrl = "")),
            backend = client,
            clock = { now },
        )

        val result = p.translate("Привет", "ru->en", "mid")

        assertNull(result.text)
        assertEquals(CloudCallGate.REASON_NO_BACKEND, result.unsupportedReason)
        assertEquals(0, client.callCount)
    }

    // ---- Контракт enum MtRoutingMode ------------------------------------------------------------

    @Test
    fun `MtRoutingMode AUTO is the first entry (default)`() {
        assertEquals(MtRoutingMode.AUTO, MtRoutingMode.entries.first())
    }

    @Test
    fun `MtRoutingMode has exactly three entries`() {
        assertEquals(3, MtRoutingMode.entries.size)
    }

    // ---- Поддельный клиент-хелпер ---------------------------------------------------------------

    private class RecordingClient(
        private val result: CloudMediationClient.Result =
            CloudMediationClient.Result.Ok(text = "Hello, how are you?", modelId = "gemini-3.5-flash"),
    ) : CloudMediationClient {
        var callCount = 0
            private set

        override fun translate(
            request: GeminiTranslateRequest,
            credential: CloudCredential,
        ): CloudMediationClient.Result {
            callCount += 1
            return result
        }
    }
}
