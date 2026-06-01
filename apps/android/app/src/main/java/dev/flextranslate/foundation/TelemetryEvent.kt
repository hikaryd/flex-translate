package dev.flextranslate.foundation

data class TelemetryEvent(
    val sessionId: String,
    val monotonicTsMs: Long,
    val eventType: String,
    val deviceTier: String,
    val deviceModel: String,
    val osVersion: String,
    val runtimeId: String,
    val modelId: String,
    val languagePair: String,
    val mode: String,
    val networkState: String,
    val appBuild: String,
    val payload: Map<String, String> = emptyMap(),
)
