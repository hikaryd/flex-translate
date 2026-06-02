import Foundation

// Immutable per-session context fields required by every TelemetryEvent.
// Constructed once per session in LiveSessionModel and passed to emit sites.
//
// Device-derived fields are read at construction time and do not change across
// the session. No PII beyond the schema's typed fields is captured.
struct TelemetryContext: Sendable {
    let sessionId: String
    let deviceTier: String
    let deviceModel: String
    let osVersion: String
    let appBuild: String
    /// Runtime identifier for the ASR provider active in this session, e.g. "sherpa-onnx:ru-t-one".
    var runtimeId: String = "none"
    /// The on-device (or cloud) MT model active in this session.
    var modelId: String = "none"
    /// Current language pair key, e.g. "ru->en".
    var languagePair: String = "none"
    /// Translation mode.
    var mode: String = TelemetrySink.modeOffline
    /// Network state at the time of the last observed connectivity check.
    var networkState: String = TelemetrySink.netUnknown

    // MARK: - Factory

    /// Build a TelemetryContext from device and build info, generating a fresh session UUID.
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

    /// Returns the hardware machine identifier via utsname (e.g. "iPhone16,2").
    /// Avoids UIDevice which is @MainActor in Swift 6.
    private static func deviceModelName() -> String {
        var sysinfo = utsname()
        uname(&sysinfo)
        // sysinfo.machine is a fixed-size C tuple of Int8; read it via withUnsafeBytes.
        let identifier = withUnsafeBytes(of: sysinfo.machine) { rawPtr -> String in
            let bytes = rawPtr.bindMemory(to: CChar.self)
            return String(cString: bytes.baseAddress!)
        }
        return identifier.isEmpty ? "unknown" : identifier
    }

    /// Returns the OS version string via ProcessInfo (not @MainActor).
    private static func osVersionString() -> String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "iOS \(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }

    private static func detectDeviceTier() -> String {
        // Use the processor count as a rough proxy for tier.
        let cores = ProcessInfo.processInfo.processorCount
        switch cores {
        case 6...: return TelemetrySink.tierHigh
        case 4..<6: return TelemetrySink.tierMid
        default:   return TelemetrySink.tierLow
        }
    }
}

// MARK: - Convenience emit

/// Emit an event using stable fields from TelemetryContext plus call-site overrides.
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
