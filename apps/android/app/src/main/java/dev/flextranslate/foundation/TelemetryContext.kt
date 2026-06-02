package dev.flextranslate.foundation

import android.os.Build

/**
 * Неизменяемые поля контекста сессии, нужные каждому [TelemetryEvent]. Создаётся один раз на
 * сессию в [dev.flextranslate.ui.LiveSessionState] и передаётся в точки emit.
 *
 * Поля об устройстве читаются при создании и за сессию не меняются. Никакого PII сверх типизированных
 * полей схемы не собираем.
 */
data class TelemetryContext(
    val sessionId: String,
    val deviceTier: String,
    val deviceModel: String,
    val osVersion: String,
    val appBuild: String,
    /** Идентификатор активного в этой сессии ASR-провайдера, например `"sherpa-onnx:ru-t-one"`. */
    var runtimeId: String = "none",
    /** Активная в этой сессии MT-модель — на устройстве или в облаке. */
    var modelId: String = "none",
    /** Ключ текущей языковой пары, например `"ru->en"`. */
    var languagePair: String = "none",
    /** Режим перевода: [TelemetrySink.MODE_OFFLINE] или [TelemetrySink.MODE_CLOUD]. */
    var mode: String = TelemetrySink.MODE_OFFLINE,
    /** Состояние сети на момент последней проверки связи. */
    var networkState: String = TelemetrySink.NET_UNKNOWN,
) {
    companion object {
        /** Собирает [TelemetryContext] из данных об устройстве и сборке, генерируя новый UUID сессии. */
        fun forDevice(appBuild: String, sessionId: String): TelemetryContext = TelemetryContext(
            sessionId = sessionId,
            deviceTier = detectDeviceTier(),
            deviceModel = Build.MODEL,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appBuild = appBuild,
        )

        private fun detectDeviceTier(): String = when {
            Build.MODEL.contains("S937", ignoreCase = true) -> TelemetrySink.TIER_HIGH
            else -> TelemetrySink.TIER_UNKNOWN
        }
    }
}

/**
 * Удобная обёртка: отправить событие, взяв стабильные поля из [TelemetryContext] плюс переопределения
 * с места вызова.
 */
fun TelemetrySink.emitWith(
    ctx: TelemetryContext,
    eventType: String,
    monotonicTsMs: Long = 0L,
    payload: Map<String, String> = emptyMap(),
) {
    emit(
        sessionId = ctx.sessionId,
        eventType = eventType,
        deviceTier = ctx.deviceTier,
        deviceModel = ctx.deviceModel,
        osVersion = ctx.osVersion,
        runtimeId = ctx.runtimeId,
        modelId = ctx.modelId,
        languagePair = ctx.languagePair,
        mode = ctx.mode,
        networkState = ctx.networkState,
        appBuild = ctx.appBuild,
        monotonicTsMs = monotonicTsMs,
        payload = payload,
    )
}
