import Foundation

/// Куда направлять каждый запрос на перевод. Зеркалит Android MtRoutingMode.
///
/// AUTO — дефолт: облако берём, когда проходит облачный гейт (онлайн + согласие + ключ),
/// иначе работает выбранная on-device модель.
/// Offline-first: нет сети / нет согласия / нет ключа → on-device, без тихого вызова в облако.
enum MtRoutingMode: String, CaseIterable, Sendable, Equatable {
    case auto       = "AUTO"
    case onDevice   = "ON_DEVICE"
    case cloud      = "CLOUD"
}
