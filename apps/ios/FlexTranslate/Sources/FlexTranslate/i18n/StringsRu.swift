import Foundation

/// Russian UI-chrome copy. Mirrors the Android StringsRu verbatim.
struct StringsRu: Strings {

    // --- App shell -------------------------------------------------------------------------------
    var tabLive: String { "Эфир" }
    var tabLanguages: String { "Языки" }
    var tabModels: String { "Модели" }
    var tabCloud: String { "Облако" }
    var tabDiagnostics: String { "Диагностика" }
    var demoBanner: String { "Demo · launch-support не заявлен" }

    // --- Live screen -----------------------------------------------------------------------------
    var modeOffline: String { "offline" }
    var micReady: String { "микрофон готов" }
    func missingPackBadge(_ packId: String) -> String { "нет пакета: \(packId)" }
    var offlineTranslationNotClaimed: String { "offline-перевод не заявлен" }
    var cloudDisabledBadge: String { "облако выключено" }
    var micLevelTitle: String { "Уровень микрофона" }
    var speech: String { "речь" }
    var silence: String { "тишина" }
    var micIdleHint: String { "Простаивает — нажмите «Слушать», чтобы увидеть реальный уровень." }
    func asrNotClaimedHint(_ languageLabel: String) -> String {
        "ASR support пока не заявлен — транскрипт появится после загрузки " +
        "локальной модели для \(languageLabel) (см. Модели)."
    }
    func listeningHint(_ languageLabel: String) -> String {
        "Слушаю… говорите на языке \(languageLabel). " +
        "Текст распознаётся локально (demo, качество не проверено)."
    }
    func readyToListenHint(_ languageLabel: String) -> String {
        "Нажмите «Слушать» — локальная модель \(languageLabel) готова."
    }
    var translationTitle: String { "Перевод" }
    var translating: String { "перевожу…" }
    var translatingCloud: String { "Перевод в облаке…" }
    var translatingLocal: String { "Перевод выполняется локально…" }
    func translationPendingCloud(_ modelName: String) -> String {
        "Перевод появится после распознавания фразы " +
        "(облако \(modelName) — требует согласия и backend, см. Облако)."
    }
    func translationPendingLocal(_ modelName: String) -> String {
        "Перевод появится после распознавания фразы " +
        "(модель \(modelName), demo, качество не проверено)."
    }
    var stop: String { "Стоп" }
    var listen: String { "Слушать" }
    var grantMic: String { "Разрешить микрофон" }
    func missingPackHint(_ packId: String) -> String { "Нет offline-пакета: \(packId) (см. Модели)." }
    var demoRecognizing: String { "Распознаю тестовое аудио…" }
    func demoRecognizeButton(_ languageCode: String) -> String {
        "Demo: распознать тестовое \(languageCode) аудио"
    }

    // --- Languages screen ------------------------------------------------------------------------
    var languagePairTitle: String { "Языковая пара" }
    var sourceLabel: String { "Источник" }
    var targetLabel: String { "Цель" }
    var swapLanguagesDescription: String { "Поменять языки местами" }
    func pairSupportTitle(_ pairLabel: String) -> String { "Поддержка пары \(pairLabel)" }
    var offlineAsrAdapterReady: String { "offline-ASR: адаптер готов (demo)" }
    var offlineTranslationNotClaimedLong: String {
        "offline-перевод: не заявлен (нужны benchmark + модель)"
    }
    var supportFromBenchmarksFooter: String {
        "Поддержка генерируется из benchmark-доказательств, а не из намерений."
    }
    var mtModelPickerTitle: String { "Модель перевода" }
    var mtModelPickerHint: String {
        "Выберите модель по качеству/скорости. Выбор используется в диалоге."
    }
    var selected: String { "выбрано" }
    var executionCloud: String { "облако" }
    var executionOnDevice: String { "на устройстве" }
    func qualityBadge(_ label: String) -> String { "качество: \(label)" }
    func speedBadge(_ label: String) -> String { "скорость: \(label)" }
    var cloudCallInWs5: String { "реальный вызов в WS5 (нужны согласие + сеть)" }
    var mtModelInstalledLocal: String { "модель установлена — перевод локальный" }
    var mtModelNotInstalled: String { "модель не установлена (см. Модели)" }
    var mtModelOptional: String { "опционально — пакет ещё не добавлен" }

    // --- MT routing mode -------------------------------------------------------------------------
    var mtRoutingModeTitle: String { "Режим маршрутизации" }
    var mtRoutingModeAuto: String { "Авто" }
    var mtRoutingModeAutoHint: String {
        "Авто: Gemini Flash при наличии интернета и согласия, иначе — локальная модель."
    }
    var mtRoutingModeOnDevice: String { "Только устройство" }
    var mtRoutingModeCloud: String { "Только облако" }
    var engineBadgeGemini: String { "Gemini Flash" }
    func engineBadgeOnDevice(_ modelId: String) -> String { "на устройстве · \(modelId)" }

    // --- Models screen ---------------------------------------------------------------------------
    var offlinePacksTitle: String { "Offline-пакеты" }
    var offlinePacksHeader: String {
        "Веса моделей не входят в сборку (лицензия/размер) — они скачиваются в приложении " +
        "по сети с проверкой контрольной суммы. Установленные пакеты показывают реальный " +
        "размер на устройстве. Поддержка не заявляется без benchmark-доказательств."
    }
    var cancel: String { "Отмена" }
    func downloadingFile(_ file: String) -> String { "Загрузка: \(file)" }
    var installed: String { "установлен" }
    var delete: String { "Удалить" }
    var statusError: String { "ошибка" }
    var statusCancelled: String { "отменено" }
    var statusNotInstalled: String { "не установлен" }
    var retry: String { "Повторить" }
    var download: String { "Скачать" }
    var installedStatusLine: String {
        "Готов к локальному распознаванию (demo, качество не проверено)."
    }
    func downloadFailedLine(_ message: String) -> String {
        "Ошибка: \(message). Файл проверяется по SHA-256; повтор продолжит докачку."
    }
    var downloadCancelledLine: String {
        "Загрузка отменена. Частичный файл сохранён — повтор продолжит докачку."
    }
    var sourceNotConfiguredLine: String {
        "Источник загрузки пока не настроен для этого пакета."
    }
    var onlineOnlyLine: String {
        "Доступно только онлайн. Контрольная сумма проверяется после загрузки."
    }
    var downloadsOverNetworkLine: String {
        "Скачивается по сети, проверяется по SHA-256, затем доступно офлайн."
    }
    var gemmaTermsLink: String { "Gemma Terms of Use и Prohibited Use Policy" }
    var sizeUnknown: String { "размер —" }

    // --- Cloud screen ----------------------------------------------------------------------------
    var cloudTitle: String { "Облако" }
    var cloudHeader: String {
        "Облако выключено по умолчанию · нет silent fallback · нет встроенных " +
        "API-ключей (backend ephemeral tokens)."
    }
    var hideDisclosure: String { "Скрыть раскрытие данных" }
    var showDisclosure: String { "Показать раскрытие данных" }
    var acceptDisclosure: String { "Принять раскрытие" }
    var backendEndpointLabel: String { "Backend endpoint (без ключа Gemini)" }
    var backendEndpointPlaceholder: String { "https://flex-backend.example.com" }
    var backendMediationHint: String {
        "Перевод идёт через ваш backend (mediation): он хранит ключ Gemini на сервере и возвращает " +
        "только текст. Пока endpoint не указан — облачный перевод честно заблокирован."
    }
    var readyToStart: String { "готово к запуску" }
    func disabledMissing(_ missing: String) -> String { "выключено · не хватает: \(missing)" }
    var missingConsent: String { "согласие" }
    var missingDisclosure: String { "раскрытие" }
    var missingOnline: String { "онлайн" }
    var missingEphemeralToken: String { "эфемерный токен" }
    var interfaceLanguageTitle: String { "Язык интерфейса" }
    var interfaceLanguageHint: String {
        "Переключайте язык интерфейса мгновенно. Не влияет на языки распознавания и перевода."
    }

    // --- Diagnostics screen ----------------------------------------------------------------------
    var captureSectionTitle: String { "Захват аудио" }
    var pipelineSectionTitle: String { "Конвейер" }
    var buildDeviceSectionTitle: String { "Сборка / устройство" }
    var telemetrySectionTitle: String { "Телеметрия" }
    var asrSupportNotClaimed: String { "не заявлен" }
    var telemetryPendingHint: String { "События появятся после включения телеметрии (WS6)." }
    var telemetryNoEventsYet: String { "Событий пока нет — начните сессию захвата." }

    // --- LiveSessionModel reasons ----------------------------------------------------------------
    func mtEngineUnavailable(_ modelName: String) -> String {
        "MT-движок недоступен для \(modelName)"
    }
    func mtModelNotInstalledReason(_ modelId: String) -> String {
        "MT-модель \(modelId) не установлена (см. Модели)"
    }

    // --- Dialogue / conversation log -------------------------------------------------------------
    var dialogueClearButton: String { "Очистить диалог" }
    var dialogueEmptyHint: String {
        "Начните говорить — реплики появятся здесь. Используйте кнопку обмена языков " +
        "для смены говорящего."
    }
    func dialogueSpeakingLabel(_ languageLabel: String) -> String { languageLabel }
    var dialoguePendingTranslation: String { "перевожу…" }
}
