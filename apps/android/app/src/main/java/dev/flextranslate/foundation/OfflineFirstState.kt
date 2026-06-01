package dev.flextranslate.foundation

sealed interface OfflineFirstState {
    data object ReadyOfflineAsr : OfflineFirstState
    data class MissingOfflinePack(val packId: String) : OfflineFirstState
    data class UnsupportedOfflineTranslation(val languagePair: String, val deviceTier: String) : OfflineFirstState
    data object CloudDisabled : OfflineFirstState
    data class CaptureBlocked(val reason: String) : OfflineFirstState
}
