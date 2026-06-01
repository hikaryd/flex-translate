import Foundation

struct CloudCredential: Equatable {
    let source: String
    let expiresAtEpochMs: Int64

    func isEphemeral(nowEpochMs: Int64) -> Bool {
        source == "backend_ephemeral_token" && expiresAtEpochMs > nowEpochMs
    }
}

struct CloudOptInState: Equatable {
    let providerId: String
    let userConsented: Bool
    let disclosureAccepted: Bool
    let credential: CloudCredential?
    let networkState: String

    func canStart(nowEpochMs: Int64) -> Bool {
        userConsented &&
            disclosureAccepted &&
            networkState == "online" &&
            (credential?.isEphemeral(nowEpochMs: nowEpochMs) == true)
    }
}

struct CloudOptInProvider: CloudProvider {
    let providerId: String

    func isAvailable(consent: CloudConsent) -> Bool {
        consent.enabled && consent.credentialSource == "backend_ephemeral_token"
    }

    func isAvailable(state: CloudOptInState, nowEpochMs: Int64) -> Bool {
        state.canStart(nowEpochMs: nowEpochMs)
    }
}

enum CloudProviderRegistry {
    static let providers = [
        CloudOptInProvider(providerId: "cloud-stt-recognition-fallback"),
        CloudOptInProvider(providerId: "gemini-live-assistant"),
        CloudOptInProvider(providerId: "gemini-batch-audio-enrichment")
    ]
}
