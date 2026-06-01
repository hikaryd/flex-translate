package dev.flextranslate.ui.i18n

import dev.flextranslate.foundation.GeminiFlashTranslationProvider

/** Russian UI-chrome copy. Mirrors the original hardcoded strings verbatim. */
object StringsRu : Strings {

    // --- App shell -------------------------------------------------------------------------------
    override val tabLive = "Эфир"
    override val tabLanguages = "Языки"
    override val tabModels = "Модели"
    override val tabCloud = "Облако"
    override val tabDiagnostics = "Диагностика"
    override val demoBanner = "Demo · launch-support не заявлен"

    // --- Live screen -----------------------------------------------------------------------------
    override val modeOffline = "offline"
    override val micReady = "микрофон готов"
    override fun missingPackBadge(packId: String) = "нет пакета: $packId"
    override val offlineTranslationNotClaimed = "offline-перевод не заявлен"
    override val cloudDisabledBadge = "облако выключено"
    override val micLevelTitle = "Уровень микрофона"
    override val speech = "речь"
    override val silence = "тишина"
    override val micIdleHint = "Простаивает — нажмите «Слушать», чтобы увидеть реальный уровень."
    override fun asrNotClaimedHint(languageLabel: String) =
        "ASR support пока не заявлен — транскрипт появится после загрузки " +
            "локальной модели для $languageLabel (см. Модели)."
    override fun listeningHint(languageLabel: String) =
        "Слушаю… говорите на языке $languageLabel. " +
            "Текст распознаётся локально (demo, качество не проверено)."
    override fun readyToListenHint(languageLabel: String) =
        "Нажмите «Слушать» — локальная модель $languageLabel готова."
    override val translationTitle = "Перевод"
    override val translating = "перевожу…"
    override val translatingCloud = "Перевод в облаке…"
    override val translatingLocal = "Перевод выполняется локально…"
    override fun translationPendingCloud(modelName: String) =
        "Перевод появится после распознавания фразы " +
            "(облако $modelName — требует согласия и backend, см. Облако)."
    override fun translationPendingLocal(modelName: String) =
        "Перевод появится после распознавания фразы " +
            "(модель $modelName, demo, качество не проверено)."
    override val stop = "Стоп"
    override val listen = "Слушать"
    override val grantMic = "Разрешить микрофон"
    override fun missingPackHint(packId: String) = "Нет offline-пакета: $packId (см. Модели)."
    override val demoRecognizing = "Распознаю тестовое аудио…"
    override fun demoRecognizeButton(languageCode: String) =
        "Demo: распознать тестовое $languageCode аудио"

    // --- Languages screen ------------------------------------------------------------------------
    override val languagePairTitle = "Языковая пара"
    override val sourceLabel = "Источник"
    override val targetLabel = "Цель"
    override val swapLanguagesDescription = "Поменять языки местами"
    override fun pairSupportTitle(pairLabel: String) = "Поддержка пары $pairLabel"
    override val offlineAsrAdapterReady = "offline-ASR: адаптер готов (demo)"
    override val offlineTranslationNotClaimedLong = "offline-перевод: не заявлен (нужны benchmark + модель)"
    override val supportFromBenchmarksFooter =
        "Поддержка генерируется из benchmark-доказательств, а не из намерений."
    override val mtModelPickerTitle = "Модель перевода"
    override val mtModelPickerHint = "Выберите модель по качеству/скорости. Выбор используется в диалоге."
    override val selected = "выбрано"
    override val executionCloud = "облако"
    override val executionOnDevice = "на устройстве"
    override fun qualityBadge(label: String) = "качество: $label"
    override fun speedBadge(label: String) = "скорость: $label"
    override val cloudCallInWs5 = "реальный вызов в WS5 (нужны согласие + сеть)"
    override val mtModelInstalledLocal = "модель установлена — перевод локальный"
    override val mtModelNotInstalled = "модель не установлена (см. Модели)"
    override val mtModelOptional = "опционально — пакет ещё не добавлен"

    // --- Models screen ---------------------------------------------------------------------------
    override val offlinePacksTitle = "Offline-пакеты"
    override val offlinePacksHeader =
        "Веса моделей не входят в сборку (лицензия/размер) — они скачиваются в приложении " +
            "по сети с проверкой контрольной суммы. Установленные пакеты показывают реальный " +
            "размер на устройстве. Поддержка не заявляется без benchmark-доказательств."
    override val cancel = "Отмена"
    override fun downloadingFile(file: String) = "Загрузка: $file"
    override val installed = "установлен"
    override val delete = "Удалить"
    override val statusError = "ошибка"
    override val statusCancelled = "отменено"
    override val statusNotInstalled = "не установлен"
    override val retry = "Повторить"
    override val download = "Скачать"
    override val installedStatusLine = "Готов к локальному распознаванию (demo, качество не проверено)."
    override fun downloadFailedLine(message: String) =
        "Ошибка: $message. Файл проверяется по SHA-256; повтор продолжит докачку."
    override val downloadCancelledLine =
        "Загрузка отменена. Частичный файл сохранён — повтор продолжит докачку."
    override val sourceNotConfiguredLine = "Источник загрузки пока не настроен для этого пакета."
    override val onlineOnlyLine = "Доступно только онлайн. Контрольная сумма проверяется после загрузки."
    override val downloadsOverNetworkLine = "Скачивается по сети, проверяется по SHA-256, затем доступно офлайн."
    override val gemmaTermsLink = "Gemma Terms of Use и Prohibited Use Policy"
    override val sizeUnknown = "размер —"

    // --- Cloud screen ----------------------------------------------------------------------------
    override val cloudTitle = "Облако"
    override val cloudHeader =
        "Облако выключено по умолчанию · нет silent fallback · нет встроенных " +
            "API-ключей (backend ephemeral tokens)."
    override val hideDisclosure = "Скрыть раскрытие данных"
    override val showDisclosure = "Показать раскрытие данных"
    override val acceptDisclosure = "Принять раскрытие"
    override val backendEndpointLabel = "Backend endpoint (без ключа Gemini)"
    override val backendEndpointPlaceholder = "https://flex-backend.example.com"
    override val backendMediationHint =
        "Перевод идёт через ваш backend (mediation): он хранит ключ Gemini на сервере и возвращает " +
            "только текст. Пока endpoint не указан — облачный перевод честно заблокирован."
    override val readyToStart = "готово к запуску"
    override fun disabledMissing(missing: String) = "выключено · не хватает: $missing"
    override val missingConsent = "согласие"
    override val missingDisclosure = "раскрытие"
    override val missingOnline = "онлайн"
    override val missingEphemeralToken = "эфемерный токен"
    override fun cloudProviderTitle(providerId: String) = CloudCopyRu.copy[providerId]?.title
    override fun cloudProviderRole(providerId: String) = CloudCopyRu.copy[providerId]?.role
    override fun cloudProviderDisclosure(providerId: String) = CloudCopyRu.copy[providerId]?.disclosure
    override val interfaceLanguageTitle = "Язык интерфейса"
    override val interfaceLanguageHint =
        "Переключайте язык интерфейса мгновенно. Не влияет на языки распознавания и перевода."

    // --- Diagnostics screen ----------------------------------------------------------------------
    override val captureSectionTitle = "Захват аудио"
    override val pipelineSectionTitle = "Конвейер"
    override val buildDeviceSectionTitle = "Сборка / устройство"
    override val telemetrySectionTitle = "Телеметрия"
    override val asrSupportNotClaimed = "не заявлен"
    override val telemetryPendingHint = "События появятся после включения телеметрии (WS6)."

    // --- LiveSessionState reasons ----------------------------------------------------------------
    override fun mtEngineUnavailable(modelName: String) = "MT-движок недоступен для $modelName"
    override fun mtModelNotInstalledReason(modelId: String) =
        "MT-модель $modelId не установлена (см. Модели)"
}

/** Holder for the per-provider cloud copy (title/role/disclosure) in Russian. */
private object CloudCopyRu {
    val copy: Map<String, CloudProviderCopy> = mapOf(
        GeminiFlashTranslationProvider.PROVIDER_ID to CloudProviderCopy(
            title = "Gemini Flash · облачный перевод (MT)",
            role = "Наивысшее качество перевода через облако. Только текст финализированной фразы — " +
                "аудио в этом режиме не отправляется.",
            disclosure = "Что уходит с устройства: только текст распознанной (финализированной) фразы " +
                "текущего высказывания — аудио для текстового MT-режима НЕ отправляется.\n" +
                "Куда: на наш backend, который пересылает запрос в Google Gemini API. " +
                "Ключ Gemini хранится только на сервере — в приложении нет встроенных API-ключей.\n" +
                "Хранение: согласно политике обработки данных провайдера. Приложение не хранит " +
                "транскрипты дольше сессии, если вы сами не включите историю.\n" +
                "Облако выключено по умолчанию и не подменяет офлайн-перевод незаметно: если облако " +
                "недоступно — показывается честная причина, а офлайн-модель продолжает работать.",
        ),
        "cloud-stt-recognition-fallback" to CloudProviderCopy(
            title = "Cloud STT · fallback распознавания",
            role = "Облачное распознавание как запасной вариант, когда offline-модель не справляется.",
            disclosure = "Аудио уходит на сервер только при явном включении. Согласие на обработку и " +
                "политику хранения данных нужно подтвердить отдельно.",
        ),
        "gemini-live-assistant" to CloudProviderCopy(
            title = "Gemini Live · realtime ассистент",
            role = "Realtime-ассистент поверх живого аудио (низкая задержка).",
            disclosure = "Потоковая передача аудио в реальном времени. Требует согласия, принятого " +
                "disclosure и эфемерного токена от backend.",
        ),
        "gemini-batch-audio-enrichment" to CloudProviderCopy(
            title = "Gemini batch · async обогащение",
            role = "Асинхронное пакетное обогащение записанных фрагментов.",
            disclosure = "Фрагменты отправляются пакетами после сессии. Удержание данных описывается " +
                "в политике провайдера; согласие обязательно.",
        ),
    )
}
