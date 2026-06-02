import SwiftUI

/// Полный каталог строк UI-оболочки, по одному члену на строку.
/// Две реализации — StringsRu и StringsEn — дают русский и английский текст;
/// активную подсовывает LocalStrings в корне приложения, переключается в рантайме
/// тумблером RU/EN внутри приложения.
///
/// Область: локализуем только оболочку/UI-chrome. Model-id и технические токены диагностики
/// сюда НЕ входят — это языко-нейтральные идентификаторы, остаются как есть. Семантика
/// гейтинга no-false-claims не меняется; переводим только человекочитаемый текст.
///
/// Обычные строки — computed vars; строки с подстановкой рантайм-данных — функции, чтобы
/// каталог оставался полным, а тест паритета RU/EN сравнивал фиксированный набор ключей.
protocol Strings: Sendable {

    // --- Оболочка приложения ---------------------------------------------------------------------
    var tabLive: String { get }
    var tabLanguages: String { get }
    var tabModels: String { get }
    var tabCloud: String { get }
    var tabDiagnostics: String { get }
    var demoBanner: String { get }

    // --- Экран Live ------------------------------------------------------------------------------
    var modeOffline: String { get }
    var micReady: String { get }
    func missingPackBadge(_ packId: String) -> String
    var offlineTranslationNotClaimed: String { get }
    var cloudDisabledBadge: String { get }
    var micLevelTitle: String { get }
    var speech: String { get }
    var silence: String { get }
    var micIdleHint: String { get }
    func asrNotClaimedHint(_ languageLabel: String) -> String
    func listeningHint(_ languageLabel: String) -> String
    func readyToListenHint(_ languageLabel: String) -> String
    var translationTitle: String { get }
    var translating: String { get }
    var translatingCloud: String { get }
    var translatingLocal: String { get }
    func translationPendingCloud(_ modelName: String) -> String
    func translationPendingLocal(_ modelName: String) -> String
    var stop: String { get }
    var listen: String { get }
    var grantMic: String { get }
    func missingPackHint(_ packId: String) -> String
    var demoRecognizing: String { get }
    func demoRecognizeButton(_ languageCode: String) -> String

    // --- Экран языков ----------------------------------------------------------------------------
    var languagePairTitle: String { get }
    var sourceLabel: String { get }
    var targetLabel: String { get }
    var swapLanguagesDescription: String { get }
    func pairSupportTitle(_ pairLabel: String) -> String
    var offlineAsrAdapterReady: String { get }
    var offlineTranslationNotClaimedLong: String { get }
    var supportFromBenchmarksFooter: String { get }
    var mtModelPickerTitle: String { get }
    var mtModelPickerHint: String { get }
    var selected: String { get }
    var executionCloud: String { get }
    var executionOnDevice: String { get }
    func qualityBadge(_ label: String) -> String
    func speedBadge(_ label: String) -> String
    var cloudCallInWs5: String { get }
    var mtModelInstalledLocal: String { get }
    var mtModelNotInstalled: String { get }
    var mtModelOptional: String { get }

    // --- Режим маршрутизации MT (AUTO / ON_DEVICE / CLOUD) ---------------------------------------
    var mtRoutingModeTitle: String { get }
    var mtRoutingModeAuto: String { get }
    var mtRoutingModeAutoHint: String { get }
    var mtRoutingModeOnDevice: String { get }
    var mtRoutingModeCloud: String { get }
    var engineBadgeGemini: String { get }
    func engineBadgeOnDevice(_ modelId: String) -> String

    // --- Экран моделей ---------------------------------------------------------------------------
    var offlinePacksTitle: String { get }
    var offlinePacksHeader: String { get }
    var cancel: String { get }
    func downloadingFile(_ file: String) -> String
    var installed: String { get }
    var delete: String { get }
    var statusError: String { get }
    var statusCancelled: String { get }
    var statusNotInstalled: String { get }
    var retry: String { get }
    var download: String { get }
    var installedStatusLine: String { get }
    func downloadFailedLine(_ message: String) -> String
    var downloadCancelledLine: String { get }
    var sourceNotConfiguredLine: String { get }
    var onlineOnlyLine: String { get }
    var downloadsOverNetworkLine: String { get }
    var gemmaTermsLink: String { get }
    var sizeUnknown: String { get }

    // --- Экран облака ----------------------------------------------------------------------------
    var cloudTitle: String { get }
    var cloudHeader: String { get }
    var hideDisclosure: String { get }
    var showDisclosure: String { get }
    var acceptDisclosure: String { get }
    var backendEndpointLabel: String { get }
    var backendEndpointPlaceholder: String { get }
    var backendMediationHint: String { get }
    var readyToStart: String { get }
    func disabledMissing(_ missing: String) -> String
    var missingConsent: String { get }
    var missingDisclosure: String { get }
    var missingOnline: String { get }
    var missingEphemeralToken: String { get }
    var interfaceLanguageTitle: String { get }
    var interfaceLanguageHint: String { get }

    // --- Карточка облачного MT Gemini Flash ------------------------------------------------------
    var geminiFlashTitle: String { get }
    var geminiFlashRole: String { get }
    var geminiGeoNote: String { get }
    var geminiCredentialModeTitle: String { get }
    var geminiModeBackend: String { get }
    var geminiModeOwnKey: String { get }
    var geminiOwnKeyLabel: String { get }
    var geminiOwnKeyPlaceholder: String { get }
    var geminiSaveKey: String { get }
    var geminiClearKey: String { get }
    var geminiKeyStoredBadge: String { get }
    var geminiKeyNotSetBadge: String { get }

    // --- Экран диагностики -----------------------------------------------------------------------
    var captureSectionTitle: String { get }
    var pipelineSectionTitle: String { get }
    var buildDeviceSectionTitle: String { get }
    var telemetrySectionTitle: String { get }
    var asrSupportNotClaimed: String { get }
    var telemetryPendingHint: String { get }
    var telemetryNoEventsYet: String { get }
    var telemetryMtP50: String { get }
    var telemetryMtP95: String { get }
    var telemetryTotalEvents: String { get }

    // --- Причины перевода из LiveSessionModel (показываются на экране Live) -----------------------
    func mtEngineUnavailable(_ modelName: String) -> String
    func mtModelNotInstalledReason(_ modelId: String) -> String

    // --- Диалог / лог разговора ------------------------------------------------------------------
    var dialogueClearButton: String { get }
    var dialogueEmptyHint: String { get }
    func dialogueSpeakingLabel(_ languageLabel: String) -> String
    var dialoguePendingTranslation: String { get }
}

/// Активный Strings для текущего окружения. По умолчанию StringsRu, чтобы любая view,
/// прочитанная до установки корневого провайдера, всё равно отрисовалась. Корень приложения
/// всегда переопределяет язык сохранённым/выбранным. @EnvironmentObject потому, что при смене
/// языка нужно перекомпоновать всё дерево — простой трюк с @Environment(\.locale) не годится.
class AppStrings: ObservableObject {
    @Published var current: any Strings

    init(language: AppLanguage = AppLanguageStore.shared.load()) {
        self.current = stringsFor(language)
    }

    func switchTo(_ language: AppLanguage) {
        AppLanguageStore.shared.save(language)
        current = stringsFor(language)
    }
}

/// Возвращает каталог Strings для заданного AppLanguage.
func stringsFor(_ language: AppLanguage) -> any Strings {
    switch language {
    case .ru: return StringsRu()
    case .en: return StringsEn()
    }
}

// Прямая инъекция `any Strings` через environment тут не используется — views берут строки
// через @EnvironmentObject AppStrings. Ключ оставлен на будущее, а defaultValue обходит
// проблему статического Sendable-хранилища через nonisolated(unsafe): это безопасно,
// потому что StringsRu — value type.
