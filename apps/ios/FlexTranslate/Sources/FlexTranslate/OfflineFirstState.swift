import Foundation

enum OfflineFirstState: Equatable, CustomStringConvertible {
    case readyOfflineAsr
    case missingOfflinePack(packId: String)
    case unsupportedOfflineTranslation(languagePair: String, deviceTier: String)
    case cloudDisabled
    case captureBlocked(reason: String)

    var description: String {
        switch self {
        case .readyOfflineAsr: return "readyOfflineAsr"
        case let .missingOfflinePack(packId): return "missingOfflinePack(\(packId))"
        case let .unsupportedOfflineTranslation(languagePair, deviceTier): return "unsupportedOfflineTranslation(\(languagePair), \(deviceTier))"
        case .cloudDisabled: return "cloudDisabled"
        case let .captureBlocked(reason): return "captureBlocked(\(reason))"
        }
    }
}
