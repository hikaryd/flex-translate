# Phase 0 Benchmark & Product Contract

Status: draft contract for `flex-translate` G001. This document defines gates only; it does **not** claim ASR/MT support until evidence is collected.

## 1. Product scope for first evidence cycle

### Launch UX mode
- **Primary UX:** continuous captions / interpreter text mode.
- **Secondary UX:** push-to-talk may be used in debug builds to simplify benchmark capture.
- **Out of scope for first evidence cycle:** speech-to-speech translation, always-on background listening, phone-call interception, and cloud-only mode.

### Offline promise
- Required offline baseline: microphone capture → local VAD → local ASR → transcript UI.
- Offline translation: supported only for language/device tiers that pass the MT matrix gates.
- If offline translation is unsupported, UI must continue local transcription and show an explicit unsupported state.

### Initial language pairs for evaluation
- ASR launch candidates: **Russian** and **English**.
- Translation evaluation pairs: **RU→EN** and **EN→RU**.
- Additional languages require a new ASR/MT matrix row before support can be claimed.

## 2. Reference device matrix

Exact commercial SKUs must be confirmed before lab execution. Initial target tiers:

| Tier | Android reference | iOS reference | Intended support claim |
|---|---|---|---|
| Low | 6 GB RAM Android, midrange SoC | iPhone 13-class | Offline ASR only unless small MT passes |
| Mid | 8 GB RAM Android, recent upper-mid SoC | iPhone 14/15-class | Offline ASR + smaller local MT if gates pass |
| High | 12 GB+ Android flagship | iPhone 15 Pro-class or newer | Offline ASR + MiLMMT Q6-class candidate only if gates pass |

Required metadata per run:
- Device model and SKU
- OS version
- Battery health/charge level
- Thermal state before run
- Available RAM/storage
- App build hash
- Model/runtime versions

## 3. ASR candidate matrix

| Language | Tier | Candidate model | Runtime | Quantization | Package size | WER/CER target | RTF target | p95 partial latency target | Memory ceiling | Battery target | Thermal gate | Status |
|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|---|
| RU | Low/Mid/High | `sherpa-onnx-streaming-t-one-russian-2025-09-08` | sherpa-onnx | int8 if available | TBD | TBD after corpus | <1.0 | ≤500 ms high-tier | TBD | see §7 | no critical | candidate |
| RU | Fallback | `sherpa-onnx-small-zipformer-ru-2024-09-18` / `zipformer-ru` | sherpa-onnx | int8/fp32 TBD | TBD | TBD | <1.0 | TBD | TBD | see §7 | no critical | baseline |
| EN | Low | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` | sherpa-onnx | int8/fp32 TBD | TBD | TBD | <1.0 | TBD | TBD | see §7 | no critical | candidate |
| EN | Mid/High | `sherpa-onnx-streaming-zipformer-en-2023-06-26` | sherpa-onnx | int8 preferred | TBD | TBD | <1.0 | ≤500 ms high-tier | TBD | see §7 | no critical | candidate |
| Any unsupported language | Fallback | Whisper base/small multilingual | whisper.cpp or sherpa-onnx Whisper | quantized TBD | TBD | TBD | TBD | likely slower | TBD | see §7 | no critical | fallback only |

ASR pass requires:
- RTF < 1.0 for sustained local recognition.
- p95 partial transcript latency target met on declared tier.
- No OS kill, critical thermal state, or audio-capture dropout.
- WER/CER target defined against the final corpus before support is claimed.

## 4. MT/runtime candidate matrix

| Candidate class | Example | Runtime candidates | Target tier | Required proof |
|---|---|---|---|---|
| High-tier LLM MT | MiLMMT-46-4B Q6/GGUF | llama.cpp/GGUF, MLC LLM if convertible, LiteRT-LM if convertible | High | p95 latency, memory, storage, battery, thermal, legal pass |
| Smaller quantized LLM MT | MiLMMT Q4/Q3 or smaller multilingual model | llama.cpp/GGUF, MLC, LiteRT-LM | Mid/High | quality degradation measured and UX disclosed |
| Dedicated MT model | NLLB/M2M/Marian/mobile-convertible candidate | ONNX/LiteRT/other mobile runtime | Low/Mid | conversion/runtime proof on both iOS and Android |
| Commercial/on-device SDK | Vendor/platform SDK if acceptable | SDK-native | Any | offline behavior, licensing, cost, language coverage |

MT pass requires:
- Language pair is in support matrix.
- Translation quality target is defined before measurement.
- p95 final translation latency target is met for the declared tier.
- Candidate passes memory/storage/battery/thermal gates.
- Legal distribution review passes before beta or external model downloads.

## 5. Benchmark corpus

### Corpus composition
For each language:
- 100 short utterances: 1–3 seconds.
- 100 medium utterances: 4–8 seconds.
- 50 long utterances: 9–20 seconds.
- 30 noisy utterances: background café/street/office noise.
- 20 interruption/partial-speech samples.

For each translation pair:
- 250 sentence-level pairs aligned with ASR corpus where possible.
- Domain mix: travel, everyday conversation, technical/product phrases, names/numbers, noisy colloquial speech.

### Required annotations
- Ground-truth transcript.
- Source language and target language.
- Segment boundaries.
- Noise profile.
- Speaker metadata class only, no PII.

## 6. Timestamp and log schema

Every event must include:
- `session_id`
- `monotonic_ts_ms`
- `event_type`
- `device_tier`
- `device_model`
- `os_version`
- `runtime_id`
- `model_id`
- `language_pair`
- `mode`: `offline`, `cloud_stt`, `gemini_live`, `gemini_batch`
- `network_state`
- `app_build`

Required event types:
- `audio_callback_received`
- `vad_speech_start`
- `vad_speech_end`
- `asr_partial_emitted`
- `asr_final_emitted`
- `mt_request_started`
- `mt_result_emitted`
- `ui_transcript_rendered`
- `ui_translation_rendered`
- `memory_sample`
- `battery_sample`
- `thermal_sample`
- `network_request_attempted`

CI/device-lab log validation must reject:
- non-monotonic event order within a session;
- missing session correlation;
- missing model/runtime identifiers;
- insufficient samples for p95.

## 7. Gates and thresholds

Initial thresholds are provisional until first lab calibration.

### Latency
- ASR partial p95: ≤500 ms on high-tier reference.
- ASR final p95: reported separately by language/tier.
- MT final p95: ≤1.5 s only for tiers/language pairs declared supported.
- UI render overhead p95: target ≤100 ms from ASR/MT event to visible render.

### Runtime performance
- RTF: <1.0 for ASR sustained sessions.
- 30-minute sustained session required for v1 gate.
- 60-minute soak required before broader beta.

### Memory/storage
- No OS kill during sustained run.
- Local MT must leave at least 20% RAM headroom or platform-equivalent safe margin.
- Model package size must be shown to user before download if not bundled.

### Thermal/battery
- No sustained critical/severe thermal state.
- Serious thermal may be transient only if audio capture continues and state recovers.
- Initial battery target: ≤12% drain per 30-minute high-tier session; mid/low must be measured and final budget set after calibration.

### Fail rule
If a device/language pair passes latency but fails memory, thermal, battery, crash, privacy, legal, or UX clarity gates, it is **not supported** for offline translation.

## 8. Cloud, privacy, and credential gates

- Cloud is opt-in only.
- Cloud STT role: recognition fallback.
- Gemini Live role: realtime assistant/conversational layer.
- Gemini audio/file API role: batch/chunked enrichment only.
- Mobile binaries must not embed standard API keys.
- Use backend-issued ephemeral tokens or backend mediation for Gemini/Cloud access.
- Every cloud path must display provider disclosure and data handling copy.
- Airplane mode and cloud-off tests must prove local ASR continues and no silent network fallback occurs.

## 9. Legal/compliance gates

Before beta distribution or external model downloads:
- Review Gemma/MiLMMT/GGUF redistribution terms.
- Review app-store policy for bundled/downloaded model weights.
- Record model license, source URL, checksum, and allowed distribution mode.
- If redistribution is unclear, do not ship that model; keep it internal/lab-only.

## 10. Phase 0 exit checklist

Phase 0 is complete only when:

- [ ] Launch UX selected.
- [ ] Launch language pairs selected.
- [ ] Reference device SKUs selected.
- [ ] Benchmark corpus path and annotation format defined.
- [ ] ASR matrix filled with candidate rows and target metrics.
- [ ] MT/runtime matrix filled with candidate rows and target metrics.
- [ ] Runtime bakeoff protocol defined for llama.cpp/GGUF, MLC LLM, LiteRT-LM, and SDK candidates.
- [ ] Log schema accepted.
- [ ] p95/sample-size rules accepted.
- [ ] Privacy/cloud consent copy drafted.
- [ ] Legal/compliance review owner assigned.
- [ ] No support claims made before measurement evidence exists.

## 11. Evidence produced by this G001 story

- This contract: `docs/product/phase-0-contract.md`
- Durable plan: `.omx/ultragoal/goals.json`
- Ledger: `.omx/ultragoal/ledger.jsonl`
