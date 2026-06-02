import Foundation

/// Облачный MT-уровень (WS5), который стоит за кандидатом gemini-flash-cloud в пикере.
///
/// Два режима доступа к ключу (см. GeminiCredentialMode):
///
/// backendMediation (исходный путь): перевод идёт через НАШ backend-посредник, он
/// подставляет ключ Gemini на своей стороне и возвращает только текст. Ключа в приложении
/// нет. Пропускает через CloudCallGate (согласие + дисклеймер + сеть + эфемерный токен +
/// настроенный backend).
///
/// ownKey (BYOK): личный ключ пользователя (лежит в iOS Keychain, не в UserDefaults)
/// берём в момент вызова и шлём в x-goog-api-key напрямую на REST-эндпоинт Gemini через
/// GeminiDirectClient. Гейт тот же (согласие + дисклеймер + сеть + наличие ключа).
/// Гео-блок (HTTP 400 "User location is not supported") показываем честно.
///
/// Контракт честности: заблокированный гейтом, офлайновый, отклонённый или упавший вызов
/// возвращает TranslationResult(text: nil, unsupportedReason: <причина>). Ничего не
/// выдумываем и молча на on-device модель не откатываемся.
///
/// id модели берётся из конфига (GeminiFlashConfig.modelId, по умолчанию gemini-3.5-flash).
///
/// Калька с Android GeminiFlashTranslationProvider.
final class GeminiFlashTranslationProvider: TranslationProvider, @unchecked Sendable {

    static let providerId = "gemini-flash-cloud"

    // Честные причины отказа для прямого ownKey-пути.
    static let reasonGeoBlocked =
        "Gemini is not available in your region — use backend mode or a VPN"
    static let reasonKeyRejected =
        "Gemini API key rejected (check the key in Cloud settings)"
    static let reasonDirectFailed =
        "Direct Gemini call failed"

    let providerId: String = GeminiFlashTranslationProvider.providerId

    private let config: GeminiFlashConfig
    private let gate: CloudCallGate
    private let backend: any CloudMediationClient
    private let directClient: GeminiDirectClient
    private let keyStore: (any GeminiKeyStore)?
    private let clock: @Sendable () -> Int64

    init(
        config: GeminiFlashConfig,
        gate: CloudCallGate,
        backend: any CloudMediationClient,
        directClient: GeminiDirectClient? = nil,
        keyStore: (any GeminiKeyStore)? = nil,
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.config = config
        self.gate = gate
        self.backend = backend
        self.directClient = directClient ?? GeminiDirectClient(config: config)
        self.keyStore = keyStore
        self.clock = clock
    }

    func translate(text: String, languagePair: String, deviceTier: String) -> TranslationResult {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return TranslationResult(text: nil, unsupportedReason: nil) }

        // Только заявленные демо-направления через RU-пивот (берём готовый MtDirection из M2m100MtProvider).
        guard MtDirection.parse(languagePair) != nil else {
            return TranslationResult(
                text: nil,
                unsupportedReason: "pair \(languagePair) not supported by cloud model"
            )
        }

        // Жёсткий гейт: нет согласия / нет дисклеймера / офлайн → вызова не будет (оба режима).
        let decision = gate.evaluate(providerId: Self.providerId, nowEpochMs: clock())
        switch decision {
        case .blocked(let reason):
            return TranslationResult(text: nil, unsupportedReason: reason)
        case .allowed(let credential):
            switch config.credentialMode {
            case .ownKey:
                return translateDirect(trimmed, languagePair: languagePair)
            case .backendMediation:
                return translateMediated(trimmed, languagePair: languagePair, credential: credential)
            }
        }
    }

    /// ownKey-путь: достаём ключ из Keychain в момент вызова и идём в Gemini напрямую.
    private func translateDirect(_ text: String, languagePair: String) -> TranslationResult {
        guard let apiKey = keyStore?.loadKey(), !apiKey.isEmpty else {
            return TranslationResult(text: nil, unsupportedReason: CloudCallGate.reasonNoOwnKey)
        }
        switch directClient.translate(text: text, languagePair: languagePair, apiKey: apiKey) {
        case .ok(let translated):
            return TranslationResult(text: translated, unsupportedReason: nil)
        case .geoBlocked:
            return TranslationResult(text: nil, unsupportedReason: Self.reasonGeoBlocked)
        case .keyRejected:
            return TranslationResult(text: nil, unsupportedReason: Self.reasonKeyRejected)
        case .failed:
            return TranslationResult(text: nil, unsupportedReason: Self.reasonDirectFailed)
        }
    }

    /// backendMediation-путь: собираем запрос-намерение и отдаём его backend-посреднику.
    private func translateMediated(
        _ text: String,
        languagePair: String,
        credential: CloudCredential
    ) -> TranslationResult {
        let request = GeminiTranslateRequest(
            providerId: Self.providerId,
            modelId: config.modelId,
            languagePair: languagePair,
            text: text,
            thinkingLevel: config.thinkingLevel,
            stream: config.streaming
        )
        switch backend.translate(request: request, credential: credential) {
        case .ok(let translated, _):
            return TranslationResult(text: translated, unsupportedReason: nil)
        case .refused(let reason):
            return TranslationResult(text: nil, unsupportedReason: reason)
        case .failed:
            return TranslationResult(text: nil, unsupportedReason: "Cloud translation unavailable")
        }
    }
}

