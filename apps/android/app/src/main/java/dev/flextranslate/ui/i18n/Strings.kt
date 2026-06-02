package dev.flextranslate.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Полный каталог строк интерфейса — по одному члену на строку. Две реализации, [StringsRu] и
 * [StringsEn], дают русский и английский текст; активная приходит через [LocalStrings] в корне
 * приложения и переключается на лету тумблером RU/EN.
 *
 * Что сюда входит: только текст оболочки/интерфейса. Технические токены (`sampleRateHz`, `peak`,
 * отображаемые имена моделей из реестров и т.п.) сюда НЕ попадают — это языконезависимые
 * идентификаторы, они остаются как есть. Логика честных блокировок не меняется, переводим только
 * человекочитаемый текст.
 *
 * Простые строки — `val`; строки с подстановкой данных — функции, чтобы каталог оставался полным,
 * а тест паритета RU/EN мог сравнить фиксированный набор ключей.
 */
interface Strings {

    // --- Оболочка приложения (AppScaffold) -------------------------------------------------------
    val tabLive: String
    val tabLanguages: String
    val tabModels: String
    val tabCloud: String
    val tabDiagnostics: String
    val demoBanner: String

    // --- Экран «Эфир» ----------------------------------------------------------------------------
    val modeOffline: String
    val micReady: String
    fun missingPackBadge(packId: String): String
    val offlineTranslationNotClaimed: String
    val cloudDisabledBadge: String
    val micLevelTitle: String
    val speech: String
    val silence: String
    val micIdleHint: String
    fun asrNotClaimedHint(languageLabel: String): String
    fun listeningHint(languageLabel: String): String
    fun readyToListenHint(languageLabel: String): String
    val translationTitle: String
    val translating: String
    val translatingCloud: String
    val translatingLocal: String
    fun translationPendingCloud(modelName: String): String
    fun translationPendingLocal(modelName: String): String
    val stop: String
    val listen: String
    val grantMic: String
    fun missingPackHint(packId: String): String
    val demoRecognizing: String
    fun demoRecognizeButton(languageCode: String): String

    // --- Экран языков ----------------------------------------------------------------------------
    val languagePairTitle: String
    val sourceLabel: String
    val targetLabel: String
    val swapLanguagesDescription: String
    fun pairSupportTitle(pairLabel: String): String
    val offlineAsrAdapterReady: String
    val offlineTranslationNotClaimedLong: String
    val supportFromBenchmarksFooter: String
    val mtModelPickerTitle: String
    val mtModelPickerHint: String
    val selected: String
    val executionCloud: String
    val executionOnDevice: String
    fun qualityBadge(label: String): String
    fun speedBadge(label: String): String
    val cloudCallInWs5: String
    val mtModelInstalledLocal: String
    val mtModelNotInstalled: String
    val mtModelOptional: String

    // --- Режим маршрутизации MT (AUTO / ON_DEVICE / CLOUD) -------------------------------------
    /** Заголовок секции выбора режима маршрутизации. */
    val mtRoutingModeTitle: String
    /** Подпись режима AUTO. */
    val mtRoutingModeAuto: String
    /** Краткое описание режима AUTO в пикере. */
    val mtRoutingModeAutoHint: String
    /** Подпись режима ON_DEVICE (принудительно локально). */
    val mtRoutingModeOnDevice: String
    /** Подпись режима CLOUD (принудительно Gemini). */
    val mtRoutingModeCloud: String
    /** Бейдж на реплике/переводе, когда результат выдал Gemini Flash. */
    val engineBadgeGemini: String
    /** Бейдж, когда результат выдала локальная модель; подставляет id модели. */
    fun engineBadgeOnDevice(modelId: String): String

    // --- Экран моделей ---------------------------------------------------------------------------
    val offlinePacksTitle: String
    val offlinePacksHeader: String
    val cancel: String
    fun downloadingFile(file: String): String
    val installed: String
    val delete: String
    val statusError: String
    val statusCancelled: String
    val statusNotInstalled: String
    val retry: String
    val download: String
    val installedStatusLine: String
    fun downloadFailedLine(message: String): String
    val downloadCancelledLine: String
    val sourceNotConfiguredLine: String
    val onlineOnlyLine: String
    val downloadsOverNetworkLine: String
    val gemmaTermsLink: String
    val sizeUnknown: String

    // --- Экран облака ----------------------------------------------------------------------------
    val cloudTitle: String
    val cloudHeader: String
    val hideDisclosure: String
    val showDisclosure: String
    val acceptDisclosure: String
    val backendEndpointLabel: String
    val backendEndpointPlaceholder: String
    val backendMediationHint: String
    val readyToStart: String
    fun disabledMissing(missing: String): String
    val missingConsent: String
    val missingDisclosure: String
    val missingOnline: String
    val missingEphemeralToken: String
    // Режим BYOK / OWN_KEY (свой ключ).
    val credentialModeLabel: String
    val credentialModeBackend: String
    val credentialModeOwnKey: String
    val ownKeyInputLabel: String
    val ownKeyInputPlaceholder: String
    val ownKeySaveButton: String
    val ownKeyClearButton: String
    val ownKeyStoredHint: String
    val ownKeyGeoRestrictionNote: String
    // Тексты облачных провайдеров по id провайдера (название/роль/дисклеймер).
    fun cloudProviderTitle(providerId: String): String?
    fun cloudProviderRole(providerId: String): String?
    fun cloudProviderDisclosure(providerId: String): String?
    // Блок переключателя языка интерфейса.
    val interfaceLanguageTitle: String
    val interfaceLanguageHint: String

    // --- Экран диагностики -----------------------------------------------------------------------
    val captureSectionTitle: String
    val pipelineSectionTitle: String
    val buildDeviceSectionTitle: String
    val telemetrySectionTitle: String
    val asrSupportNotClaimed: String
    val telemetryPendingHint: String
    val telemetryNoEventsYet: String

    // --- Причины блокировки перевода из LiveSessionState (показываются на «Эфире») ---------------
    fun mtEngineUnavailable(modelName: String): String
    fun mtModelNotInstalledReason(modelId: String): String

    // --- Диалог / лог разговора ------------------------------------------------------------------
    /** Подпись кнопки очистки лога разговора. */
    val dialogueClearButton: String
    /** Подсказка в области лога, пока реплик ещё нет. */
    val dialogueEmptyHint: String
    /** Подпись, кто сейчас говорит (например, "Русский говорит"). */
    fun dialogueSpeakingLabel(languageLabel: String): String
    /** Подпись в слоте перевода реплики, пока работает MT. */
    val dialoguePendingTranslation: String
}

/**
 * Активный [Strings] для текущей композиции. По умолчанию [StringsRu] — чтобы любой composable,
 * прочитанный до установки корневого провайдера, всё равно отрисовался (корень всё равно
 * переопределит его сохранённым/выбранным языком). Static, потому что значение меняется редко —
 * только при смене языка.
 */
val LocalStrings = staticCompositionLocalOf<Strings> { StringsRu }

/** Подобрать каталог [Strings] для [AppLanguage]. */
fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.RU -> StringsRu
    AppLanguage.EN -> StringsEn
}
