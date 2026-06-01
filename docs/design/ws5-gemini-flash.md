# WS5 — Cloud Layer: Gemini Flash as a First-Class Translation Option

Status: design (G006 / WS5). This document specifies a **real, production-ready** Gemini Flash
cloud-assist adapter for flex-translate — not a stub. It implements §1.12 item 4 (Gemini Flash as a
first-class option) under the hard security and offline invariants of §1.9
(`.omx/plans/flex-translate-completion-ralplan.md`).

It is a design only. It prescribes types, contracts, request/response shapes, and the
ephemeral-token / backend-mediation flow. No code is written by this doc, and no embedded keys are
introduced anywhere.

---

## 0. Verified facts that anchor this design (mid-2026)

| Fact | Value | Why it matters here |
|---|---|---|
| Current GA fast Gemini model | **`gemini-3.5-flash`** (GA since **2026-05-19**) | The user said "Gemini 3.5 Flash"; this is the real current GA id. |
| Predecessor, still supported | `gemini-2.5-flash` | Fallback / config alternative; do not hardcode a single id. |
| Newer fast variants in the family | `gemini-3.1-flash-lite` (GA), `gemini-3-flash-preview`, `gemini-3.1-flash-live-preview` | Config can point at any of these without code change. |
| Text generation endpoint | `POST .../v1beta/models/{model}:generateContent` | Batch translate of a finalized transcript. |
| Streaming endpoint | `POST .../v1beta/models/{model}:streamGenerateContent?alt=sse` (SSE) | Low-latency token streaming for dialogue. |
| Host | `https://generativelanguage.googleapis.com` | Single base host for the Gemini Developer API. |
| **Ephemeral tokens** | **Live-API only**, `v1alpha`, single-use, ~1 min to open / ~30 min lifetime | A client-held ephemeral token works **only** for the Live (WebSocket) path — **not** for `generateContent`. This forces the text-translation path to use **full backend mediation**. |

> Decision driven by the last row: the WS5 text-translation adapter (the one that plugs into the MT
> picker) uses **backend mediation** (the app holds **no** Gemini token at all). The optional Live
> realtime-assistant path (`gemini-live-assistant`, already in `configs/cloud-providers.json`) is the
> only place where a client-held **ephemeral** token is appropriate. Both are key-free in the binary.

---

## 1. Architecture

### 1.1 Where it slots in

The MT picker already exposes four tiers (see `foundation/MtCandidate.kt`, registry verified in repo):

| Tier | Candidate | Execution | Quality / Speed | Runtime |
|---|---|---|---|---|
| balanced (default) | `m2m100-418m` | ON_DEVICE | MEDIUM / MEDIUM | ONNX |
| quality | `milmmt-46-4b-q6` | ON_DEVICE | HIGH / LOW | llama.cpp/GGUF |
| fast | `opus-mt-fast` | ON_DEVICE | LOW / HIGH | ONNX |
| **cloud** | **`gemini-flash-cloud`** | **CLOUD** | **HIGHEST / HIGH** | **Gemini API (this doc)** |

`MtExecution.CLOUD` and the `gemini-flash-cloud` candidate already exist; its `notes` say
"реальный вызов появится в WS5". WS5 makes that note true. **No new picker plumbing is invented** —
WS5 supplies the runtime behind the existing CLOUD candidate.

### 1.2 The adapter — `GeminiFlashTranslationProvider`

It implements the existing `TranslationProvider` interface verbatim (from `foundation/ProviderAdapters.kt`):

```kotlin
interface TranslationProvider {
    val providerId: String
    fun translate(text: String, languagePair: String, deviceTier: String): TranslationResult
    fun close() = Unit
}
```

`TranslationResult(text: String?, unsupportedReason: String?)` is the honest envelope: a cloud call
that is gated, offline, or failed returns `text = null` + a product-language `unsupportedReason`. It
**never fabricates** output. This keeps the no-false-claims invariant intact for the cloud tier.

Sketch (illustrative — not committed by this doc):

```kotlin
class GeminiFlashTranslationProvider(
    override val providerId: String = "gemini-flash-cloud",
    private val config: GeminiFlashConfig,        // model id + endpoints, see §4
    private val gate: CloudCallGate,              // wraps CloudOptInState (§2)
    private val backend: CloudMediationClient,    // talks to OUR backend, never to Google directly
    private val clock: () -> Long = System::currentTimeMillis,
) : TranslationProvider {

    override fun translate(text: String, languagePair: String, deviceTier: String): TranslationResult {
        // 1. Hard gate. No consent / no disclosure / offline / no credential => no call.
        val decision = gate.evaluate(providerId, clock())
        if (decision !is CloudCallGate.Decision.Allowed) {
            return TranslationResult(text = null, unsupportedReason = decision.userReason)
        }
        // 2. Build a translate/interpreter prompt (§1.4) for languagePair.
        val request = GeminiTranslateRequest(
            model = config.modelId,                // e.g. "gemini-3.5-flash" — CONFIG, not literal
            systemInstruction = config.interpreterSystemPrompt(languagePair),
            userText = text,
        )
        // 3. Mediated call — backend injects auth, calls Gemini, returns text only.
        return when (val r = backend.translate(request, decision.credential)) {
            is CloudMediationClient.Ok      -> TranslationResult(text = r.text, unsupportedReason = null)
            is CloudMediationClient.Refused -> TranslationResult(null, r.userReason) // backend declined
            is CloudMediationClient.Failed  -> TranslationResult(null, "Облачный перевод недоступен")
        }
    }
}
```

The adapter is **stateless per call** for the batch path (REST is stateless; full prompt + history
travel in each request — see §1.5). `close()` is a no-op for batch; for streaming it cancels any
in-flight SSE stream.

### 1.3 Integration with the dialogue flow

The dialogue (§1.12 item 2: bidirectional RU-pivot interpreter, RU↔EN and RU↔ZH) drives MT off the
**finalized ASR transcript** (`TranscriptEvent.isFinal == true`). The runtime resolves the
currently-selected MT candidate and calls `translate(...)`:

```
ASR final transcript ─► selected MT candidate
   ├─ ON_DEVICE (m2m100 / milmmt / opus) ── always available, offline path
   └─ CLOUD (gemini-flash-cloud) ── only if CloudCallGate.Allowed, else honest gate reason
```

- **Selection is intent, not capability.** Picking `gemini-flash-cloud` expresses "I want cloud
  quality." The actual call still requires the §2 gate to pass. If it does not, the dialogue surfaces
  the gate reason ("Облако выключено" / "Нет сети" / "Нужен токен") and the user can either consent
  or switch back to an on-device candidate.
- **No silent fallback** (§3): if the user selected cloud and the gate fails, WS5 does **not** quietly
  route to M2M-100. It returns `unsupportedReason`; the UI tells the truth and offers the choice. The
  *offline core continues to function* — but switching tiers is a user action, not a hidden swap.
- **Language pairs:** the same four RU-pivot directions the picker advertises
  (`ru->en, en->ru, ru->zh, zh->ru`). The prompt (§1.4) is parameterized by `languagePair`.

### 1.4 Issuing the "translate / be a dialogue interpreter" prompt

Gemini is a generative model; translation is done via a system instruction plus the source text as
user content. For dialogue we frame it as a **two-way interpreter**, RU as the pivot:

- `systemInstruction` (server-pinned where possible — see §2.4): a concise interpreter directive,
  e.g. *"You are a live dialogue interpreter. Translate the user message from {SRC} to {DST}. Output
  only the translation, no commentary, preserve meaning and register, keep it natural for spoken
  dialogue."* `{SRC}/{DST}` derived from `languagePair`.
- `contents`: a single `user` turn carrying the transcript text (batch), or the rolling dialogue
  turns (streaming/multi-turn — §1.5).
- `generationConfig`: leave `temperature`/`topP`/`topK` at defaults (Gemini 3.x is tuned for
  defaults; determinism is achieved via explicit system-instruction rules, not low temperature). Set
  `thinkingConfig.thinkingLevel = "low"` for translation: it is latency-sensitive and not a hard
  reasoning task, so `low` gives strong quality at the lowest latency/cost. (`thinkingLevel` replaces
  the legacy `thinkingBudget`; do not send both — that returns HTTP 400.)

### 1.5 Request / response shape (real, verified)

**Batch** (`generateContent`):

```http
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent
Content-Type: application/json
# Auth header injected by OUR backend, never by the app. App holds no Gemini key/token here.
```
```json
{
  "systemInstruction": {
    "parts": [{ "text": "You are a live dialogue interpreter. Translate from Russian to English. Output only the translation." }]
  },
  "contents": [
    { "role": "user", "parts": [{ "text": "Здравствуйте, как у вас дела?" }] }
  ],
  "generationConfig": {
    "thinkingConfig": { "thinkingLevel": "low" }
  }
}
```

Response (`GenerateContentResponse`):

```json
{
  "candidates": [
    { "content": { "role": "model", "parts": [{ "text": "Hello, how are you?" }] }, "finishReason": "STOP", "index": 0 }
  ],
  "usageMetadata": { "promptTokenCount": 12, "candidatesTokenCount": 5, "totalTokenCount": 17 }
}
```

The adapter extracts `candidates[0].content.parts[*].text`. `finishReason != "STOP"` (e.g. `SAFETY`,
`MAX_TOKENS`) maps to a `null` result + honest reason. `usageMetadata` feeds telemetry (§6) for
cost/latency observability.

**Streaming** (`streamGenerateContent?alt=sse`): identical body; the response is an SSE stream of
`GenerateContentResponse` chunks, each carrying an incremental `parts[].text`. The adapter
concatenates partials and can surface them as non-final UI updates (see §5). Multi-turn dialogue
passes prior `user`/`model` turns in `contents` (REST is stateless — history is resent each call;
only `user` and `model` roles are valid in `contents`; system-level guidance goes in
`systemInstruction`).

### 1.6 Module placement (read-only context — files NOT edited by this doc)

The adapter and its support types live alongside existing providers under
`apps/android/app/src/main/java/dev/flextranslate/foundation/`, mirroring `M2m100MtProvider.kt` /
`MilmmtMtProvider.kt`. The MT runtime resolver (in `ui/LiveSessionState.kt`) gains a CLOUD branch
that constructs `GeminiFlashTranslationProvider` when the selected candidate is `gemini-flash-cloud`.
**These edits are out of scope for this doc** (other agents own apps/android); this doc only specifies
the contract those edits must satisfy.

---

## 2. Security (HARD constraint, §1.9)

### 2.1 The non-negotiables

1. **No embedded API keys.** No Gemini/Cloud key in source, resources, manifests, BuildConfig,
   gradle properties, or any bundled config. CI must grep-fail the build on a key pattern in
   `apps/**`. `configs/cloud-providers.json` already declares `"noEmbeddedApiKeys": true` and
   `"credentialMode": "backend_ephemeral_token_or_backend_mediation"`.
2. **Two key-free credential modes, chosen by path:**
   - **Text translation (this WS5 MT tier): backend mediation.** The app calls **our backend**, which
     holds the Gemini key in its server environment, calls Gemini server-side, and returns **text
     only**. The app never sees a Gemini credential. This is mandatory because ephemeral tokens do
     **not** work with `generateContent` (verified, §0).
   - **Optional Live realtime assistant (`gemini-live-assistant`): backend-issued ephemeral token.**
     The app fetches a short-lived, single-use token from our backend and opens a WebSocket directly
     to Gemini's `v1alpha` Live endpoint. The token expires in ~30 min and can only open a session
     within ~1 min. This is the only client-held credential, and it is short-lived and scoped.

### 2.2 What the app ships vs what the backend must provide

| Concern | App (mobile binary) | Backend (server, operator-run) |
|---|---|---|
| Gemini API key | **never** | held only in server env / secret manager |
| Model id | reads from config (§4) | may override per request / policy |
| Text translate call | calls **our** `/v1/cloud/translate` | calls Gemini `generateContent`, returns text only |
| Live token | fetches from `/v1/cloud/live-token`, uses for WebSocket only | calls `authTokens.create` (v1alpha), returns `{ name, expireTime, newSessionExpireTime }` |
| System prompt | sends intent (langpair) | can pin/override system instruction server-side (defense in depth) |
| Auth to backend | user/session auth (existing app identity) | authenticates the app, rate-limits, logs, applies quotas/abuse controls |

### 2.3 Backend token / mediation endpoint contract (minimal)

**A. Mediated translate (primary path — `generateContent` behind the backend).**

```http
POST {BACKEND_BASE}/v1/cloud/translate
Authorization: Bearer <app-session-token>     # the app's existing identity, NOT a Gemini key
Content-Type: application/json
```
```json
{
  "providerId": "gemini-flash-cloud",
  "languagePair": "ru->en",
  "text": "Здравствуйте, как у вас дела?",
  "stream": false
}
```
Success:
```json
{ "ok": true, "text": "Hello, how are you?", "modelId": "gemini-3.5-flash",
  "usage": { "promptTokens": 12, "outputTokens": 5 } }
```
Declined / failure (honest, no fabrication):
```json
{ "ok": false, "reason": "rate_limited", "userMessage": "Облачный перевод временно недоступен" }
```
For `stream:true`, the backend proxies Gemini SSE to the app as SSE; each event carries an
incremental `text` delta and a terminal `done` event.

**B. Ephemeral Live token (only for the optional realtime assistant).**

```http
POST {BACKEND_BASE}/v1/cloud/live-token
Authorization: Bearer <app-session-token>
```
```json
{ "providerId": "gemini-live-assistant", "model": "gemini-3.1-flash-live-preview" }
```
Response (backend calls Gemini `authTokens.create` with `uses:1`, locked to a model/config via
`liveConnectConstraints`):
```json
{ "token": "auth_tokens/abc...", "expireTime": "2026-06-01T12:30:00Z",
  "newSessionExpireTime": "2026-06-01T12:01:00Z" }
```
The app maps this onto the existing `CloudCredential(source = "backend_ephemeral_token",
expiresAtEpochMs = <expireTime>)` and connects to
`wss://generativelanguage.googleapis.com/ws/...BidiGenerateContentConstrained?access_token={token}`.

### 2.4 How `CloudOptInState` / `CloudCredential` gate the call (already present)

The repo already defines the gate primitives (`foundation/CloudOptIn.kt`, verified):

```kotlin
fun CloudOptInState.canStart(nowEpochMs: Long): Boolean =
    userConsented && disclosureAccepted && networkState == "online" &&
        credential?.isEphemeral(nowEpochMs) == true   // source=="backend_ephemeral_token" && not expired
```

WS5 wraps this in a `CloudCallGate` that the adapter consults **before every call**:

- **Mediated text path:** the "credential" the app carries is its **own backend session** (not a
  Gemini token). To keep the existing `canStart` shape, the gate treats a valid backend session as
  the `CloudCredential` with `source = "backend_ephemeral_token"` semantics (short-lived, backend
  issued), so the same four conditions (consent + disclosure + online + live credential) gate the MT
  call. The Gemini key stays on the server.
- **Live path:** the `CloudCredential` is the real ephemeral token from §2.3-B; `isEphemeral` checks
  source + expiry exactly as written.

The gate returns a `Decision`: `Allowed(credential)` or `Blocked(userReason)` where `userReason` is
one of the product-language strings in §3, mapped from the existing `CloudScreen` "missing" list
(`согласие` / `раскрытие` / `онлайн` / `эфемерный токен`).

### 2.5 Threat-model notes

- A leaked **mediated** call exposes nothing: the app holds no Gemini key, and the backend rate-limits
  per app identity.
- A leaked **ephemeral Live token** is single-use, model-locked, and expires in minutes — bounded blast
  radius, by design the secure pattern for client-to-server Live.
- System instructions can be pinned server-side (mediation) or via `liveConnectConstraints` (Live), so
  prompt/policy can't be trivially rewritten by a tampered client.

---

## 3. Offline invariants (§1.9 / §3 of the doc)

1. **Cloud OFF by default.** `gemini-flash-cloud` is selectable but every cloud call is gated; default
   MT remains `m2m100-418m` (on-device). `CloudScreen` toggles default to **off** (consent switch
   default unchecked; disclosure must be separately accepted).
2. **No silent fallback — in either direction.** Cloud selected + gate fails → honest reason, offline
   core keeps running, user chooses to consent or switch tiers. The runtime never auto-swaps cloud↔
   on-device without a user action.
3. **Airplane / offline → on-device path continues.** Mic capture, VAD, on-device ASR (sherpa-onnx
   RU/EN/ZH), and on-device MT (M2M-100 / MiLMMT) are never blocked by cloud being off, unreachable,
   timing out, or flaky. A network failure mid-call returns `unsupportedReason`, never a stall of the
   live pipeline.
4. **Consent + disclosure + data-retention copy** are mandatory before any audio/text leaves the
   device. The existing `CloudScreen` already renders provider title, role, an expandable
   data-disclosure block, and a separate "Принять раскрытие" switch. WS5 disclosure copy for the
   Gemini MT tier must state plainly, in product language:
   - **what leaves the device:** the *finalized text transcript* of the current utterance (batch MT
     tier); audio leaves the device **only** for the separate Live/STT roles, never for the text MT
     tier;
   - **where it goes:** to our backend, which forwards it to Google's Gemini API;
   - **retention:** per the provider's data-handling policy, linked; the app states it does not retain
     transcripts beyond the session unless the user enables history.
5. **UX state strings** reuse the existing contract: `Cloud disabled` / `Cloud unavailable` /
   `Cloud active` / `Local only` (from `docs/security/cloud-opt-in.md`), localized RU/EN per the WS-I18N
   workstream.

These invariants are enforced by regression tests required by §1.9: a **cloud-off** test (cloud
disabled → on-device translation still produces output), a **flaky-network** test (cloud call fails →
honest reason, pipeline alive), and a **no-network** test (airplane → no request attempted, `Local
only` shown).

---

## 4. Model-configurability (§1.12 item 4)

The exact Gemini model id is **config, never a literal**, so "3.5 Flash" / the latest can change with
no code edit.

```kotlin
data class GeminiFlashConfig(
    val modelId: String,                 // e.g. "gemini-3.5-flash" (current GA), or "gemini-2.5-flash"
    val streaming: Boolean = true,       // §5
    val thinkingLevel: String = "low",   // latency-sensitive translation
    // Endpoints are the BACKEND's, not Google's — the app never holds Google's host+key together.
    val mediatedTranslatePath: String = "/v1/cloud/translate",
    val liveTokenPath: String = "/v1/cloud/live-token",
)
```

- The default ships as `modelId = "gemini-3.5-flash"` (verified current GA, §0).
- Source of truth: a config value, surfaced through `configs/cloud-providers.json` (which already
  declares the provider roles) and/or a remote-config value the backend can override per request.
  Because the **backend** ultimately issues the call, the operator can also pin/upgrade the model
  server-side independent of app releases — a second, code-free upgrade lever.
- The picker label stays "Gemini Flash (облако)"; the concrete id is an implementation detail behind
  config. No user-visible churn when the id moves to a newer Flash.

---

## 5. Streaming vs batch

| Path | Use | Endpoint |
|---|---|---|
| **Streaming (default for dialogue)** | Live two-way interpreter; show the translation as it forms for perceived low latency | `streamGenerateContent?alt=sse`, proxied through backend as SSE |
| **Batch** | One-shot translate of a short finalized utterance; simpler; used as the fallback when streaming is unavailable or for very short strings where streaming adds no value | `generateContent` |

**Recommendation:** use **streaming** for the dialogue flow. The product is a live interpreter; first
visible token latency matters more than total completion time, and SSE lets the UI render partial
translation under the live transcript (as a non-final line, promoted to final on the stream's `done`
event). For utterances below a small token threshold, the adapter may transparently use batch (the
backend decides, since `stream` is a request flag). Streaming and batch share the **identical** request
body, so the adapter builds one request object and only the transport differs — keeping the code DRY.

`close()` on the streaming adapter cancels the in-flight SSE subscription so a user switching tiers or
ending a session does not leak a connection.

---

## 6. Telemetry hooks (alignment with §1.10)

Each cloud MT call emits a telemetry event carrying `session_id`, monotonic timestamps (request start,
first token, completion), `provider/runtime ID = gemini-flash-cloud`, resolved `modelId`,
`language_pair`, `mode = cloud`, and `usageMetadata` token counts. This makes the cloud tier
observable for latency and cost without changing the offline event schema. A separate **cloud opt-in**
telemetry profile (already named in §1.10) captures the gate decisions (allowed / blocked-reason) for
the cloud path.

---

## 7. Summary of what WS5 delivers

- A real `GeminiFlashTranslationProvider` implementing the existing `TranslationProvider` interface,
  behind the existing `gemini-flash-cloud` CLOUD picker candidate.
- **Zero embedded keys**: text translation via **backend mediation**; optional Live realtime via
  **backend-issued ephemeral tokens** (the only client-held, short-lived credential).
- Gating through the already-present `CloudOptInState` / `CloudCredential` / `CloudConsent` types and
  `CloudScreen` consent + disclosure UI; cloud **off by default**, **no silent fallback**, offline
  core always alive.
- Model id fully **config-driven** (`gemini-3.5-flash` default; upgradable in config or server-side).
- **Streaming-first** dialogue (SSE), batch as the simple fallback; identical request body for both.
- Honest `TranslationResult` envelope preserves the no-false-claims invariant for the cloud tier.

---

## Sources

- [Models | Gemini API (Google AI for Developers)](https://ai.google.dev/gemini-api/docs/models) — current model id list incl. `gemini-3.5-flash`, `gemini-2.5-flash`.
- [Release notes / changelog | Gemini API](https://ai.google.dev/gemini-api/docs/changelog) — `gemini-3.5-flash` GA on 2026-05-19; `gemini-3.1-flash-lite` GA; `gemini-2.5-flash-preview-09-2025` retirement notice.
- [What's new in Gemini 3.5 Flash | Gemini API](https://ai.google.dev/gemini-api/docs/whats-new-gemini-3.5) — 3.5 Flash GA, 1M context, `generateContent` recommended for stable production, thought-signature behavior.
- [Generating content | Gemini API](https://ai.google.dev/api/generate-content) — `generateContent` / `streamGenerateContent` request body (`contents`/`parts`/`role`), `systemInstruction`, response shape, SSE `alt=sse`.
- [Gemini 3 Developer Guide | Gemini API](https://ai.google.dev/gemini-api/docs/gemini-3) — `generationConfig`, `thinkingConfig`, default temperature guidance for 3.x.
- [Gemini thinking | Gemini API](https://ai.google.dev/gemini-api/docs/thinking) — `thinkingLevel` (low/medium/high) replaces `thinkingBudget`; 400 if both sent.
- [Ephemeral tokens | Gemini API](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens) — ephemeral tokens are **Live-API only** (`v1alpha`), single-use, ~1 min open / ~30 min lifetime, `liveConnectConstraints` locking, backend issues via `authTokens.create`.
- [Get started with Gemini Live API using WebSockets | Gemini API](https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket) — `access_token` query param, `v1alpha` BidiGenerateContentConstrained WebSocket endpoint.
- [Gemini 2.5 Flash with Gemini Live API | Vertex AI docs](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/models/gemini/2-5-flash-live-api) — Live API model/config context.
