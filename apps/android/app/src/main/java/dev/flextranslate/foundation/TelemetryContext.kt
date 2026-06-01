package dev.flextranslate.foundation

import android.os.Build

/**
 * Immutable per-session context fields required by every [TelemetryEvent]. Constructed once
 * per session in [dev.flextranslate.ui.LiveSessionState] and passed to emit sites.
 *
 * Device-derived fields are read at construction time and do not change across the session.
 * No PII beyond the schema's typed fields is captured.
 */
data class TelemetryContext(
    val sessionId: String,
    val deviceTier: String,
    val deviceModel: String,
    val osVersion: String,
    val appBuild: String,
    /** Runtime identifier for the ASR provider active in this session, e.g. `"sherpa-onnx:ru-t-one"`. */
    var runtimeId: String = "none",
    /** The on-device (or cloud) MT model active in this session. */
    var modelId: String = "none",
    /** Current language pair key, e.g. `"ru->en"`. */
    var languagePair: String = "none",
    /** Translation mode: [TelemetrySink.MODE_OFFLINE] or [TelemetrySink.MODE_CLOUD]. */
    var mode: String = TelemetrySink.MODE_OFFLINE,
    /** Network state at the time of the last observed connectivity check. */
    var networkState: String = TelemetrySink.NET_UNKNOWN,
) {
    companion object {
        /** Build a [TelemetryContext] from device and build info, generating a fresh session UUID. */
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
 * Convenience: emit an event using stable fields from [TelemetryContext] plus call-site overrides.
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
