# Cross-platform Mobile Foundation

Status: G002 foundation scaffold. This is an implementation skeleton and contract layer; it does **not** claim ASR or translation model support.

## Foundation goals

- Provide separate iOS and Android app shells.
- Capture microphone permission state explicitly.
- Keep platform-native audio capture behind `AudioCaptureController` abstractions.
- Keep ASR, MT, and cloud providers behind adapters.
- Show a local-only debug transcript UI that works with placeholder/local events.
- Represent offline-first UX states for missing models, unsupported translation pairs, and cloud-disabled mode.
- Emit telemetry events conforming to `schemas/telemetry-event.schema.json`.

## Runtime boundaries

```text
Platform App Shell
  -> Permission Controller
  -> AudioCaptureController
  -> Provider Adapters
       -> AsrProvider
       -> TranslationProvider
       -> CloudProvider
  -> OfflineFirstState
  -> Debug Transcript UI
  -> Telemetry Sink
```

## Offline-first states

| State | Meaning | Required UI behavior |
|---|---|---|
| `readyOfflineAsr` | Mic permission granted and local ASR model available | Allow local capture/transcript |
| `missingOfflinePack` | Required offline model is missing | Explain pack required; do not use cloud silently |
| `unsupportedOfflineTranslation` | Device/language pair lacks local translation support | Continue ASR; show translation unavailable offline |
| `cloudDisabled` | User has not opted in or network unavailable | Disable cloud controls without blocking ASR |
| `captureBlocked` | Permission or platform audio issue | Explain permission/device problem |

## Provider adapter rule

No UI or audio code should call a concrete model/cloud SDK directly. Providers implement these conceptual contracts:

- `AsrProvider`: accepts audio frames/events and emits partial/final transcript events.
- `TranslationProvider`: accepts text + language pair and emits translated text or unsupported reason.
- `CloudProvider`: requires explicit consent state and credential source before any network call.

## Security rule for cloud adapters

Mobile apps must not embed standard Gemini/Cloud API keys. G005 may add backend-issued ephemeral token or backend mediation support; G002 only defines the seam.
