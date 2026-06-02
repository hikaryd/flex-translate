import Foundation

/// How to route each translation request. Mirrors Android MtRoutingMode.
///
/// AUTO is the default — cloud MT is used whenever the cloud gate passes
/// (online + consented + credential), otherwise the selected on-device model is used.
/// Offline-first: no network / no consent / no credential → on-device, no silent cloud call.
enum MtRoutingMode: String, CaseIterable, Sendable, Equatable {
    case auto       = "AUTO"
    case onDevice   = "ON_DEVICE"
    case cloud      = "CLOUD"
}
