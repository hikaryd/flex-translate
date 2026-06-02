import Foundation
import Testing
@testable import FlexTranslate

// Детерминированные тесты облачного MT-тира WS5: предусловия CloudCallGate,
// честность/гейтинг GeminiFlashTranslationProvider и хелперы сборки запроса /
// разбора ответа. Фейковый CloudMediationClient считает вызовы — так проверяем,
// что при заблокированном гейте сетевого вызова НЕТ.
//
// Зеркалит Android GeminiFlashTranslationProviderTest.

@Suite("GeminiFlashTranslationProvider")
struct GeminiFlashTranslationProviderTests {

    private let now: Int64 = 1_000_000

    // MARK: - Фейковый клиент с записью вызовов

    private final class RecordingClient: CloudMediationClient, @unchecked Sendable {
        private let result: CloudMediationResult
        private(set) var requests: [GeminiTranslateRequest] = []
        private(set) var callCount = 0

        init(result: CloudMediationResult = .ok(text: "Hello, how are you?", modelId: "gemini-3.5-flash")) {
            self.result = result
        }

        func translate(request: GeminiTranslateRequest, credential: CloudCredential) -> CloudMediationResult {
            callCount += 1
            requests.append(request)
            return result
        }
    }

    private func configWithBackend() -> GeminiFlashConfig {
        GeminiFlashConfig(backendBaseUrl: "https://flex-backend.example.com")
    }

    private func allowedState() -> CloudOptInState {
        CloudOptInState(
            providerId: GeminiFlashTranslationProvider.providerId,
            userConsented: true,
            disclosureAccepted: true,
            credential: CloudCredential(source: "backend_ephemeral_token", expiresAtEpochMs: now + 60_000),
            networkState: "online"
        )
    }

    private func makeProvider(
        state: CloudOptInState?,
        client: RecordingClient,
        config: GeminiFlashConfig? = nil
    ) -> GeminiFlashTranslationProvider {
        let cfg = config ?? configWithBackend()
        let gate = CloudCallGate(stateProvider: { _ in state }, config: cfg)
        return GeminiFlashTranslationProvider(
            config: cfg,
            gate: gate,
            backend: client,
            clock: { self.now }
        )
    }

    // MARK: - Гейтинг: каждый путь блокировки даёт честную причину и НЕ делает вызов

    @Test("No backend endpoint blocks honestly and makes no call")
    func noBackendBlocks() {
        let client = RecordingClient()
        let p = makeProvider(state: allowedState(), client: client, config: GeminiFlashConfig(backendBaseUrl: ""))
        let result = p.translate(text: "Здравствуйте", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == CloudCallGate.reasonNoBackend)
        #expect(client.callCount == 0)
    }

    @Test("Missing consent blocks honestly and makes no call")
    func missingConsentBlocks() {
        let client = RecordingClient()
        var s = allowedState()
        s = CloudOptInState(providerId: s.providerId, userConsented: false,
                            disclosureAccepted: s.disclosureAccepted,
                            credential: s.credential, networkState: s.networkState)
        let p = makeProvider(state: s, client: client)
        let result = p.translate(text: "Здравствуйте", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == CloudCallGate.reasonNoConsent)
        #expect(client.callCount == 0)
    }

    @Test("Missing disclosure blocks honestly and makes no call")
    func missingDisclosureBlocks() {
        let client = RecordingClient()
        var s = allowedState()
        s = CloudOptInState(providerId: s.providerId, userConsented: s.userConsented,
                            disclosureAccepted: false,
                            credential: s.credential, networkState: s.networkState)
        let p = makeProvider(state: s, client: client)
        let result = p.translate(text: "Здравствуйте", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == CloudCallGate.reasonNoDisclosure)
        #expect(client.callCount == 0)
    }

    @Test("Offline blocks honestly and makes no call")
    func offlineBlocks() {
        let client = RecordingClient()
        var s = allowedState()
        s = CloudOptInState(providerId: s.providerId, userConsented: s.userConsented,
                            disclosureAccepted: s.disclosureAccepted,
                            credential: s.credential, networkState: "offline")
        let p = makeProvider(state: s, client: client)
        let result = p.translate(text: "Здравствуйте", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == CloudCallGate.reasonOffline)
        #expect(client.callCount == 0)
    }

    @Test("Expired token blocks honestly and makes no call")
    func expiredTokenBlocks() {
        let client = RecordingClient()
        let s = CloudOptInState(
            providerId: allowedState().providerId,
            userConsented: true,
            disclosureAccepted: true,
            credential: CloudCredential(source: "backend_ephemeral_token", expiresAtEpochMs: now - 1),
            networkState: "online"
        )
        let p = makeProvider(state: s, client: client)
        let result = p.translate(text: "Здравствуйте", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == CloudCallGate.reasonNoToken)
        #expect(client.callCount == 0)
    }

    @Test("Unknown provider state blocks honestly and makes no call")
    func unknownStateBlocks() {
        let client = RecordingClient()
        let p = makeProvider(state: nil, client: client)
        let result = p.translate(text: "Здравствуйте", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == CloudCallGate.reasonDisabled)
        #expect(client.callCount == 0)
    }

    @Test("Unsupported language pair blocks before any call")
    func unsupportedPairBlocks() {
        let client = RecordingClient()
        let p = makeProvider(state: allowedState(), client: client)
        let result = p.translate(text: "hello", languagePair: "en->fr", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason?.contains("en->fr") == true)
        #expect(client.callCount == 0)
    }

    @Test("Blank text returns neither text nor reason and makes no call")
    func blankTextNoCall() {
        let client = RecordingClient()
        let p = makeProvider(state: allowedState(), client: client)
        let result = p.translate(text: "   ", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == nil)
        #expect(client.callCount == 0)
    }

    // MARK: - Разрешённый путь

    @Test("Allowed call mediates and returns backend text with correct request fields")
    func allowedCallMediates() {
        let client = RecordingClient()
        let p = makeProvider(state: allowedState(), client: client)
        let result = p.translate(text: "Здравствуйте, как у вас дела?", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == "Hello, how are you?")
        #expect(result.unsupportedReason == nil)
        #expect(client.callCount == 1)
        let req = client.requests[0]
        #expect(req.providerId == GeminiFlashTranslationProvider.providerId)
        #expect(req.modelId == GeminiFlashConfig.defaultModelId)
        #expect(req.languagePair == "ru->en")
        #expect(req.text == "Здравствуйте, как у вас дела?")
        #expect(req.thinkingLevel == "low")
    }

    @Test("Backend refusal surfaces the backend user reason")
    func backendRefusal() {
        let client = RecordingClient(result: .refused("Cloud translation temporarily unavailable"))
        let p = makeProvider(state: allowedState(), client: client)
        let result = p.translate(text: "Привет", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == "Cloud translation temporarily unavailable")
        #expect(client.callCount == 1)
    }

    @Test("Backend failure surfaces honest generic reason, not fabricated text")
    func backendFailure() {
        let client = RecordingClient(result: .failed("timeout"))
        let p = makeProvider(state: allowedState(), client: client)
        let result = p.translate(text: "Привет", languagePair: "ru->en", deviceTier: "mid")
        #expect(result.text == nil)
        #expect(result.unsupportedReason == "Cloud translation unavailable")
        #expect(client.callCount == 1)
    }

    // MARK: - Хелперы сборки запроса / разбора ответа

    @Test("buildMediatedRequestBody emits intent fields and no api key")
    func mediatedBodyNoKey() {
        let body = buildMediatedRequestBody(request: GeminiTranslateRequest(
            providerId: "gemini-flash-cloud",
            modelId: "gemini-3.5-flash",
            languagePair: "ru->en",
            text: "Здравствуйте",
            thinkingLevel: "low",
            stream: false
        ))
        #expect(body.contains("\"languagePair\":\"ru->en\""))
        #expect(body.contains("\"modelId\":\"gemini-3.5-flash\""))
        #expect(body.contains("\"text\":\"Здравствуйте\""))
        let lower = body.lowercased()
        #expect(!lower.contains("apikey"))
        #expect(!lower.contains("api_key"))
        #expect(!lower.contains("aiza"))
    }

    @Test("parseMediationResponse maps ok true to ok")
    func parseMediationOk() {
        let r = parseMediationResponse(status: 200, payload: "{\"ok\":true,\"text\":\"Hello\",\"modelId\":\"gemini-3.5-flash\"}")
        if case .ok(let text, _) = r {
            #expect(text == "Hello")
        } else {
            #expect(Bool(false), "Expected .ok, got \(r)")
        }
    }

    @Test("parseMediationResponse maps ok false to refused with user message")
    func parseMediationRefused() {
        let r = parseMediationResponse(status: 200, payload: "{\"ok\":false,\"reason\":\"rate_limited\",\"userMessage\":\"Недоступно\"}")
        if case .refused(let reason) = r {
            #expect(reason == "Недоступно")
        } else {
            #expect(Bool(false), "Expected .refused, got \(r)")
        }
    }

    @Test("parseMediationResponse maps non-2xx to refused")
    func parseMediationNon2xx() {
        let r = parseMediationResponse(status: 429, payload: "{\"userMessage\":\"Too many requests\"}")
        if case .refused(let reason) = r {
            #expect(reason == "Too many requests")
        } else {
            #expect(Bool(false), "Expected .refused, got \(r)")
        }
    }

    @Test("parseMediationResponse maps unparseable body to failed")
    func parseMediationUnparseable() {
        let r = parseMediationResponse(status: 200, payload: "<html>not json</html>")
        if case .failed = r { } else {
            #expect(Bool(false), "Expected .failed, got \(r)")
        }
    }

    @Test("parseDirectResponse maps geo-block honestly")
    func parseDirectGeoBlock() {
        let r = parseDirectResponse(status: 400, payload: "{\"error\":{\"message\":\"User location is not supported\"}}")
        #expect(r == .geoBlocked)
    }

    @Test("parseDirectResponse maps 401 to keyRejected")
    func parseDirectKeyRejected() {
        let r = parseDirectResponse(status: 401, payload: "")
        #expect(r == .keyRejected)
    }

    @Test("parseDirectResponse maps valid candidates to ok")
    func parseDirectOk() {
        let payload = """
        {"candidates":[{"content":{"parts":[{"text":"Hello"}]}}]}
        """
        let r = parseDirectResponse(status: 200, payload: payload)
        if case .ok(let text) = r {
            #expect(text == "Hello")
        } else {
            #expect(Bool(false), "Expected .ok, got \(r)")
        }
    }

    @Test("GeminiFlashConfig translateEndpoint is nil without backend, joins cleanly with one")
    func translateEndpoint() {
        #expect(GeminiFlashConfig(backendBaseUrl: "").translateEndpoint() == nil)
        #expect(
            GeminiFlashConfig(backendBaseUrl: "https://b.example.com/").translateEndpoint()
            == "https://b.example.com/v1/cloud/translate"
        )
    }

    // MARK: - BYOK CloudCallGate — режим ownKey

    @Test("OwnKey gate passes when key is present")
    func ownKeyGatePass() {
        let keyStore = InMemoryGeminiKeyStore(initialKey: "AIzaTestKey")
        let config = GeminiFlashConfig(credentialMode: .ownKey)
        let gate = CloudCallGate(
            stateProvider: { _ in
                CloudOptInState(
                    providerId: GeminiFlashTranslationProvider.providerId,
                    userConsented: true,
                    disclosureAccepted: true,
                    credential: nil,
                    networkState: "online"
                )
            },
            config: config,
            keyStore: keyStore
        )
        let decision = gate.evaluate(providerId: GeminiFlashTranslationProvider.providerId, nowEpochMs: now)
        if case .allowed = decision { } else {
            #expect(Bool(false), "Expected .allowed when key is present, got \(decision)")
        }
    }

    @Test("OwnKey gate blocks when key is absent")
    func ownKeyGateBlocksWhenAbsent() {
        let keyStore = InMemoryGeminiKeyStore(initialKey: nil)
        let config = GeminiFlashConfig(credentialMode: .ownKey)
        let gate = CloudCallGate(
            stateProvider: { _ in
                CloudOptInState(
                    providerId: GeminiFlashTranslationProvider.providerId,
                    userConsented: true,
                    disclosureAccepted: true,
                    credential: nil,
                    networkState: "online"
                )
            },
            config: config,
            keyStore: keyStore
        )
        let decision = gate.evaluate(providerId: GeminiFlashTranslationProvider.providerId, nowEpochMs: now)
        if case .blocked(let reason) = decision {
            #expect(reason == CloudCallGate.reasonNoOwnKey)
        } else {
            #expect(Bool(false), "Expected .blocked, got \(decision)")
        }
    }

    // MARK: - Паритет строк для новых строк Gemini

    @Test("Gemini Flash strings non-empty in both languages")
    func geminiStringsNonEmpty() {
        let ru = StringsRu()
        let en = StringsEn()
        #expect(!ru.geminiFlashTitle.isEmpty)
        #expect(!en.geminiFlashTitle.isEmpty)
        #expect(!ru.geminiFlashRole.isEmpty)
        #expect(!en.geminiFlashRole.isEmpty)
        #expect(!ru.geminiGeoNote.isEmpty)
        #expect(!en.geminiGeoNote.isEmpty)
        #expect(!ru.geminiCredentialModeTitle.isEmpty)
        #expect(!en.geminiCredentialModeTitle.isEmpty)
        #expect(!ru.geminiModeBackend.isEmpty)
        #expect(!en.geminiModeBackend.isEmpty)
        #expect(!ru.geminiModeOwnKey.isEmpty)
        #expect(!en.geminiModeOwnKey.isEmpty)
        #expect(!ru.geminiOwnKeyLabel.isEmpty)
        #expect(!en.geminiOwnKeyLabel.isEmpty)
        #expect(!ru.geminiSaveKey.isEmpty)
        #expect(!en.geminiSaveKey.isEmpty)
        #expect(!ru.geminiClearKey.isEmpty)
        #expect(!en.geminiClearKey.isEmpty)
        #expect(!ru.geminiKeyStoredBadge.isEmpty)
        #expect(!en.geminiKeyStoredBadge.isEmpty)
        #expect(!ru.geminiKeyNotSetBadge.isEmpty)
        #expect(!en.geminiKeyNotSetBadge.isEmpty)
        // Строки в разных языках должны отличаться
        #expect(ru.geminiFlashTitle != en.geminiFlashTitle)
        #expect(ru.geminiSaveKey != en.geminiSaveKey)
    }

    // MARK: - Проверка InMemoryGeminiKeyStore

    @Test("InMemoryGeminiKeyStore save/load/clear round-trip")
    func keyStoreRoundTrip() {
        let store = InMemoryGeminiKeyStore()
        #expect(store.loadKey() == nil)
        #expect(!store.hasKey())
        store.saveKey("test-key-123")
        #expect(store.loadKey() == "test-key-123")
        #expect(store.hasKey())
        store.clearKey()
        #expect(store.loadKey() == nil)
        #expect(!store.hasKey())
    }
}
