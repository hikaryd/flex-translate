import Foundation

// Неизменяемые поля контекста сессии, нужные каждому TelemetryEvent.
// Создаётся один раз на сессию в LiveSessionModel и прокидывается в места emit.
//
// Поля про устройство читаются при создании и за сессию не меняются.
// Никакой PII сверх типизированных полей схемы не собираем.
struct TelemetryContext: Sendable {
    let sessionId: String
    let deviceTier: String
    let deviceModel: String
    let osVersion: String
    let appBuild: String
    /// runtimeId активного в сессии ASR-провайдера, например "sherpa-onnx:ru-t-one".
    var runtimeId: String = "none"
    /// Активная в сессии MT-модель (on-device или облачная).
    var modelId: String = "none"
    /// Текущая языковая пара, например "ru->en".
    var languagePair: String = "none"
    /// Режим перевода.
    var mode: String = TelemetrySink.modeOffline
    /// Состояние сети на момент последней проверки связи.
    var networkState: String = TelemetrySink.netUnknown

    // MARK: - Factory

    /// Собирает TelemetryContext из данных устройства и сборки, заводя свежий UUID сессии.
    static func forDevice(appBuild: String, sessionId: String) -> TelemetryContext {
        TelemetryContext(
            sessionId: sessionId,
            deviceTier: detectDeviceTier(),
            deviceModel: deviceModelName(),
            osVersion: osVersionString(),
            appBuild: appBuild
        )
    }

    // MARK: - Private helpers

    /// Возвращает идентификатор железа через utsname (например "iPhone16,2").
    /// Обходим UIDevice, который в Swift 6 завязан на @MainActor.
    private static func deviceModelName() -> String {
        var sysinfo = utsname()
        uname(&sysinfo)
        // sysinfo.machine — C-кортеж Int8 фиксированного размера; читаем через withUnsafeBytes.
        let identifier = withUnsafeBytes(of: sysinfo.machine) { rawPtr -> String in
            let bytes = rawPtr.bindMemory(to: CChar.self)
            return String(cString: bytes.baseAddress!)
        }
        return identifier.isEmpty ? "unknown" : identifier
    }

    /// Возвращает строку версии ОС через ProcessInfo (не @MainActor).
    private static func osVersionString() -> String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "iOS \(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }

    private static func detectDeviceTier() -> String {
        // Число ядер — грубый прокси для определения тира.
        let cores = ProcessInfo.processInfo.processorCount
        switch cores {
        case 6...: return TelemetrySink.tierHigh
        case 4..<6: return TelemetrySink.tierMid
        default:   return TelemetrySink.tierLow
        }
    }
}

// MARK: - Convenience emit

/// Шлёт событие, беря стабильные поля из TelemetryContext плюс переопределения с места вызова.
extension TelemetrySink {
    func emitWith(
        ctx: TelemetryContext,
        eventType: String,
        monotonicTsMs: Int64 = 0,
        payload: [String: String] = [:]
    ) {
        emit(
            sessionId: ctx.sessionId,
            eventType: eventType,
            deviceTier: ctx.deviceTier,
            deviceModel: ctx.deviceModel,
            osVersion: ctx.osVersion,
            runtimeId: ctx.runtimeId,
            modelId: ctx.modelId,
            languagePair: ctx.languagePair,
            mode: ctx.mode,
            networkState: ctx.networkState,
            appBuild: ctx.appBuild,
            monotonicTsMs: monotonicTsMs,
            payload: payload
        )
    }
}

// MARK: - App build helper

func currentAppBuild() -> String {
    let info = Bundle.main.infoDictionary
    let version = info?["CFBundleShortVersionString"] as? String ?? "?"
    let build = info?["CFBundleVersion"] as? String ?? "?"
    return "\(version) (\(build))"
}
