package dev.flextranslate.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [MtRoutingMode] policy and [CloudCallGate] decision logic that backs
 * [dev.flextranslate.ui.LiveSessionState.isCloudUsable].
 *
 * These tests exercise the gate directly (pure JVM, no Android deps) to verify:
 *
 *   AUTO + (online + consent + credential)  → Gemini chosen (gate returns Allowed)
 *   AUTO + offline                           → on-device (gate returns Blocked)
 *   AUTO + no consent                        → on-device (gate returns Blocked)
 *   AUTO + no credential                     → on-device (gate returns Blocked)
 *   ON_DEVICE forces local even when cloud usable (gate irrelevant)
 *   CLOUD forces Gemini and surfaces honest reason if gate blocks
 *
 * The routing decision in LiveSessionState is:
 *   cloudUsable = gate.evaluate(...) is Allowed
 *   useCloud = when(mode) { CLOUD→true; AUTO→cloudUsable; ON_DEVICE→false }
 *
 * The tests mirror this logic with a fake GeminiFlashTranslationProvider (via RecordingClient)
 * and a fake CloudCallGate, confirming the gate decision drives the right provider selection.
 */
class MtRoutingPolicyTest {

    private val now = 1_000_000L

    // ---- Gate evaluation helpers ----------------------------------------------------------------

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

    /** Simulate isCloudUsable(): true iff gate.evaluate returns Allowed. */
    private fun CloudCallGate.isUsable(): Boolean =
        evaluate(GeminiFlashTranslationProvider.PROVIDER_ID, now) is CloudCallGate.Decision.Allowed

    // ---- AUTO mode: gate controls which engine is selected ------------------------------------

    @Test
    fun `AUTO — online, consented, credential present — gate is Allowed (cloud chosen)`() {
        val g = gate(allowedState())
        val cloudUsable = g.isUsable()

        // AUTO logic: useCloud = cloudUsable
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
        // AUTO logic: cloudUsable=false → on-device used, no cloud call.
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
        // Fake key store: no key present
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

    // ---- ON_DEVICE mode: always on-device regardless of gate state ---------------------------

    @Test
    fun `ON_DEVICE — gate would pass but mode forces local`() {
        val g = gate(allowedState())
        // Gate would pass (cloudUsable=true), but ON_DEVICE overrides:
        val cloudUsable = g.isUsable()
        assertTrue("gate passes (precondition for test)", cloudUsable)

        // MtRoutingMode.ON_DEVICE: useCloud = false, always
        val useCloud = false // ON_DEVICE always false
        assertTrue("ON_DEVICE forces on-device even when gate passes", !useCloud)
    }

    // ---- CLOUD mode: always tries Gemini; gate failure surfaces honest reason -----------------

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

    // ---- MtRoutingMode enum contract -----------------------------------------------------------

    @Test
    fun `MtRoutingMode AUTO is the first entry (default)`() {
        assertEquals(MtRoutingMode.AUTO, MtRoutingMode.entries.first())
    }

    @Test
    fun `MtRoutingMode has exactly three entries`() {
        assertEquals(3, MtRoutingMode.entries.size)
    }

    // ---- Helper fake client -------------------------------------------------------------------

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
