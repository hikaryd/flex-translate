# Cloud Opt-in Security and Privacy Contract

Status: G005 cloud layer scaffold. This defines consent and credential seams; it does not enable real cloud traffic.

## Roles

| Adapter | Role | Not allowed to do |
|---|---|---|
| Cloud STT | Online recognition fallback | Become required for offline ASR |
| Gemini Live | Realtime assistant/conversation | Silently replace local transcription |
| Gemini audio/file | Batch/chunked enrichment | Claim realtime transcription support |

## Consent invariant

No cloud adapter may start unless all are true:

1. User explicitly opted in for that provider role.
2. Provider disclosure and data handling copy are visible.
3. Credential source is backend-issued ephemeral token or backend mediation.
4. Network state is online.
5. Local ASR remains available or the UI clearly states local reason for unavailability.

## Credential invariant

- No standard Gemini/Cloud API keys in mobile source, resources, manifests, or config.
- Mobile apps may request short-lived credentials from a backend endpoint in G005+ implementation.
- Backend mediation is preferred for sensitive provider configuration.

## Offline invariant

Airplane mode, cloud disabled, provider timeout, and flaky network must not block:

- microphone capture;
- local VAD;
- local ASR provider path;
- transcript UI.

## UX copy requirements

Cloud controls must show one of:

- `Cloud disabled` — user has not opted in.
- `Cloud unavailable` — network/provider unavailable.
- `Cloud active` — explicit opt-in and credential available.
- `Local only` — offline mode; no network requests attempted.
