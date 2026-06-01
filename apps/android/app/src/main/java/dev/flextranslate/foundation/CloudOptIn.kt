package dev.flextranslate.foundation

data class CloudCredential(
    val source: String,
    val expiresAtEpochMs: Long,
) {
    fun isEphemeral(nowEpochMs: Long): Boolean = source == "backend_ephemeral_token" && expiresAtEpochMs > nowEpochMs
}

data class CloudOptInState(
    val providerId: String,
    val userConsented: Boolean,
    val disclosureAccepted: Boolean,
    val credential: CloudCredential?,
    val networkState: String,
) {
    fun canStart(nowEpochMs: Long): Boolean =
        userConsented &&
            disclosureAccepted &&
            networkState == "online" &&
            credential?.isEphemeral(nowEpochMs) == true
}

class CloudOptInProvider(override val providerId: String) : CloudProvider {
    override fun isAvailable(consent: CloudConsent): Boolean =
        consent.enabled && consent.credentialSource == "backend_ephemeral_token"

    fun isAvailable(state: CloudOptInState, nowEpochMs: Long): Boolean = state.canStart(nowEpochMs)
}

object CloudProviderRegistry {
    val providers = listOf(
        CloudOptInProvider("cloud-stt-recognition-fallback"),
        CloudOptInProvider("gemini-live-assistant"),
        CloudOptInProvider("gemini-batch-audio-enrichment"),
    )
}
