# WS6 — Telemetry + On-Device Benchmark Plan

Status: G006/G007/WS6 design. This document is the **evidence path** that lets the
app honestly drop its `Demo · launch-support не заявлен` scaffolding for a
specific language/pair on a specific device tier. It is **not** itself a support
claim, and nothing here upgrades any candidate to `supported` until real
SM-S937B bundles pass `scripts/validate_device_lab_bundle.py` and the release
gate. Until then every candidate in `configs/asr-candidates.json` and
`configs/mt-candidates.json` stays `support: not_claimed`.

The contract this implements: `docs/product/phase-0-contract.md` §6 (log schema)
and §7 (gates). The schemas it conforms to: `schemas/telemetry-event.schema.json`
and `schemas/benchmark-result.schema.json`. The validators it feeds:
`scripts/validate_telemetry_log.py`, `scripts/validate_benchmark_result.py`,
`scripts/validate_device_metadata.py`, `scripts/validate_battery_thermal_log.py`,
`scripts/validate_device_lab_bundle.py`, `scripts/validate_device_lab_evidence_root.py`,
`scripts/generate_support_matrix.py`, `scripts/check_release_gate.py`.

---

## 0. Scope, ownership, and the honesty invariant

WS6 has three jobs, and they must stay in separate lanes (see §0.1):

1. **In-app telemetry emission** — the QA build emits schema-conformant
   `TelemetryEvent` rows during a session and exports them as JSONL the
   device-lab validators can ingest. (§1)
2. **On-device benchmark execution on SM-S937B** — a concrete runbook to produce
   one validated evidence bundle per `(result_type, model_id, language(_pair),
   device_tier)` candidate, with real WER/CER, RTF, p50/p95 latency, memory
   high-water, battery drain/30 min, and thermal state. (§2)
3. **Support-matrix + gates → cleanup link** — feeding those bundles through the
   generator + release gate so PASS rows justify removing the demo/`не заявлен`
   labels for exactly that scope, and FAIL rows keep them. (§3, §4)

### 0.1 Authorship vs. verification separation

- **Telemetry the app emits is never self-certifying.** `validate_telemetry_log.py`
  proves a run is *well-formed enough to evaluate* — it never converts a benchmark
  into a support claim. Support is owned by benchmark-result JSON + release gate.
- **The app must not read its own benchmark verdict.** The Kotlin app emits
  telemetry; a separate offline pipeline (Python validators + human release/legal
  review) decides `support_decision`. The app then reads the *generated support
  matrix outcome* (a build-time/config input), not its own telemetry.
- **`not_measured` / placeholder is the honest default.** Any field the app
  cannot truthfully measure on-device stays absent or `not_measured`; the
  validators reject `not_measured` thermal and placeholder device metadata in
  strict (supported) mode.

---

## 1. In-app telemetry emission (Android QA build)

### 1.1 Conformance target

Every emitted event MUST carry the 12 required schema fields
(`schemas/telemetry-event.schema.json`, mirrored in
`scripts/validate_telemetry_log.py` `REQUIRED_FIELDS`):

`session_id`, `monotonic_ts_ms`, `event_type`, `device_tier`, `device_model`,
`os_version`, `runtime_id`, `model_id`, `language_pair`, `mode`,
`network_state`, `app_build`, plus optional `payload: {…}`.

Allowed `event_type` (must match the schema enum exactly — no extras, validator
rejects unknown types):

`audio_callback_received`, `vad_speech_start`, `vad_speech_end`,
`asr_partial_emitted`, `asr_final_emitted`, `mt_request_started`,
`mt_result_emitted`, `ui_transcript_rendered`, `ui_translation_rendered`,
`memory_sample`, `battery_sample`, `thermal_sample`, `network_request_attempted`.

Constraints the validator enforces (design to them, not around them):
- `monotonic_ts_ms` is a **non-negative integer** and **non-decreasing within a
  `session_id`**. Use `SystemClock.elapsedRealtime()` (already used by
  `AudioCaptureController` via `startedAt`) — never wall-clock `System.currentTimeMillis()`.
- `language_pair` matches `^[a-z]{2,3}(-[A-Z]{2})?->[a-z]{2,3}(-[A-Z]{2})?$|^none$`.
  Pure-ASR sessions with no translation use `"none"` (the existing
  `telemetry.valid.jsonl` sample does exactly this). MT sessions use e.g.
  `"ru->en"`, `"en->ru"`, `"zh->ru"`.
- One `session_id` must keep a **single** `(device_model, os_version,
  runtime_id, model_id, app_build)` signature — the validator's
  `validate_session_correlation` rejects a session that mixes identifiers. So a
  session that switches model mid-run must start a **new** `session_id`.
- In `mode: offline`, `network_state` must be `offline` and there must be **no**
  `network_request_attempted` events (enforced under `--offline-no-network`).
  This is the same invariant the airplane-mode test in §2.5 proves.

### 1.2 Field-source mapping (where each value comes from)

| Field | Source on SM-S937B QA build |
|---|---|
| `session_id` | UUID minted at session start (`LiveSessionState` start / `runWavDemo`). New UUID per model switch. |
| `monotonic_ts_ms` | `SystemClock.elapsedRealtime() - sessionStartElapsedMs` (always ≥ 0, monotonic). |
| `event_type` | The emit call site (see §1.3). |
| `device_tier` | Reuse `DiagnosticsScreen.deviceTier()` logic (`Build.MODEL` contains `S937` → `high`). Telemetry allows `unknown`; benchmark-result schema does **not** (`low/mid/high` only), so SM-S937B → `high`. |
| `device_model` | `Build.MODEL` → `"SM-S937B"`. |
| `os_version` | `"Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"` → `"Android 16 (API 36)"`. Keep one canonical string and reuse it verbatim in `device-metadata.json` so the bundle validator's device-match passes. |
| `runtime_id` | `"sherpa-onnx"` for ASR (matches `model-manifest.json` `runtime_ids`); `"onnxruntime-android"` for M2M-100 MT; `"llama.cpp/GGUF"` for MiLMMT. Must be a member of the manifest's `runtime_ids` array or the bundle validator rejects it. |
| `model_id` | The candidate id, e.g. `ru-t-one-streaming-2025-09-08`, `en-zipformer-mid-high-2023-06-26`, `zh-en-bilingual-zipformer-2023-02-20`, `m2m100-418m`, `milmmt-46-4b-q6-gguf-high-tier`. Must equal benchmark-result `model_id` and manifest `model_id`. |
| `language_pair` | `"none"` for ASR-only; the dialogue direction for MT. |
| `mode` | `offline` for the WS6 launch scope. `cloud_stt` / `gemini_live` / `gemini_batch` only on the cloud opt-in path (WS5), and only with `network_state != offline`. |
| `network_state` | `offline` while airplane mode / cloud-off. Derive from `ConnectivityManager` + the cloud opt-in flag; in the offline benchmark it is hard-`offline`. |
| `app_build` | Build identifier. `DiagnosticsScreen` currently shows `"0.1.0"`; emit the same string and reuse it as `device-metadata.json.app_build_id`. Prefer a Gradle `BuildConfig.VERSION_NAME + "+" + git-short-sha` so a bundle is traceable to a commit. |
| `payload` | Free-form `Map<String, String>` (the current `TelemetryEvent.payload` type). Carries per-event metrics (see §1.3). |

> Note on `payload` typing: the current `TelemetryEvent.payload` is
> `Map<String, String>`. Schema `payload` is `object` with `additionalProperties:
> true`, so numbers-as-strings serialize fine for the validator, but the
> **benchmark metric extraction** (§2.4) needs numeric latency/memory. Emit
> numeric metrics as JSON numbers in the exported JSONL (serialize the payload
> map values that are numeric without quotes), or widen `payload` to
> `Map<String, Any>` for the QA build. Either is acceptable to the schema; the
> benchmark post-processor (§2.4) is what consumes the numbers.

### 1.3 Emit points (mapping app pipeline → event types)

The Android pipeline is `AudioCaptureController` (mic/callback) → energy-VAD →
`SherpaOnnxAsrProvider` (ASR) → `M2m100MtProvider` / `MilmmtMtProvider` (MT) →
Compose UI (`LiveScreen`). Insert emit calls at these boundaries. Each row below
lists the suggested `payload` keys (used by §2.4 latency math).

| Event | Emit site | Suggested `payload` |
|---|---|---|
| `audio_callback_received` | Each `AudioRecord.read()` loop iteration in `AudioCaptureController.start` | `{frames, sample_rate_hz}` |
| `vad_speech_start` | Energy-VAD transitions idle→speech | `{}` |
| `vad_speech_end` | Energy-VAD transitions speech→idle | `{utterance_ms}` |
| `asr_partial_emitted` | `SherpaOnnxAsrProvider` emits a partial transcript | `{chars, partial_latency_ms}` where `partial_latency_ms = now − vad_speech_start_ts` for the first partial, or `now − prev_partial_ts` per increment (pick one convention and keep it; §2.4 reads `partial_latency_ms`). |
| `asr_final_emitted` | Provider emits the endpointed final | `{chars, final_latency_ms}` |
| `mt_request_started` | `MtProvider.translate()` entry | `{src_chars}` |
| `mt_result_emitted` | `MtProvider.translate()` return | `{translation_latency_ms, tgt_chars}` |
| `ui_transcript_rendered` | `LiveScreen` recomposition committing transcript text | `{render_latency_ms}` (event ts − asr emit ts; §7 UI overhead target ≤100 ms) |
| `ui_translation_rendered` | `LiveScreen` committing translation text | `{render_latency_ms}` |
| `memory_sample` | Periodic sampler (every 1 s, see §1.4) | `{rss_mb}` from `Debug.getMemoryInfo()` / `android.os.Process` RSS |
| `battery_sample` | Periodic sampler (every 60 s) | `{battery_percent}` from `BatteryManager.EXTRA_LEVEL` |
| `thermal_sample` | `PowerManager.OnThermalStatusChangedListener` + periodic poll | `{thermal_state}` mapping `THERMAL_STATUS_*` → `nominal/fair/serious_recovered/critical` |
| `network_request_attempted` | Any outbound request attempt (cloud path only) | `{host, provider}` — **must never appear in an offline run**. |

### 1.4 Background samplers (memory / battery / thermal)

A QA-build-only `TelemetrySampler` runs while a session is active:
- **memory**: 1 s cadence, `rss_mb` high-water tracked across the session →
  feeds benchmark `memory_peak_mb`.
- **battery**: 60 s cadence via `BatteryManager` → start/end percent feed
  `battery_delta_percent_30m` and the `battery-thermal-log.json` `samples[]`.
- **thermal**: register `PowerManager.addThermalStatusListener` (API 29+; S937B
  is API 36) **and** poll every 60 s so a steady state still produces samples.
  Map Android thermal status to the schema enum:
  - `THERMAL_STATUS_NONE/LIGHT/MODERATE` → `nominal` / `fair`
  - `THERMAL_STATUS_SEVERE` (transient, recovers, capture continues) → `serious_recovered`
  - `THERMAL_STATUS_CRITICAL`+ → `critical` (fails the gate)

The sampler thresholds for `--require-event-count` in a 30-min run: at 1 s memory
and 60 s battery/thermal you get ≥1800 `memory_sample`, ≥30 `battery_sample`,
≥30 `thermal_sample` — comfortably above the template's `=30` requirement
(`create_device_lab_run_template.py` `ASR_EVENTS`/`MT_EVENTS`).

### 1.5 Where logs are written and how they're exported

- **In-app sink**: append each event as one JSON object per line to
  `getExternalFilesDir(null)/telemetry/<session_id>.jsonl` (QA build only; gated
  behind a build flag so production never writes telemetry to disk).
- **Export for validators**: pull via adb. Because of the Android 11+
  scoped-storage FUSE caveat already documented in `ws3-a2-device-demo.json`
  (`run-as` needed to reach internal `filesDir`), prefer writing telemetry to
  the app's **external** files dir (world-readable to adb for the package) or
  pull with `run-as`:

  ```sh
  # external files dir (simplest):
  adb -s R5CY61166KE pull \
    /sdcard/Android/data/dev.flextranslate/files/telemetry/<session>.jsonl \
    benchmarks/device-lab/results/2026-06-01/sm-s937b/<candidate>/telemetry.jsonl

  # or internal filesDir via run-as (debuggable build):
  adb -s R5CY61166KE exec-out run-as dev.flextranslate \
    cat files/telemetry/<session>.jsonl \
    > benchmarks/device-lab/results/2026-06-01/sm-s937b/<candidate>/telemetry.jsonl
  ```

- **Surface in Diagnostics**: replace the `TelemetrySection` placeholders in
  `DiagnosticsScreen.kt` (`lastEvents = "pending"`, "События появятся после
  включения телеметрии (WS6)") with the live event counter + a "Export"
  affordance. This is the only Diagnostics change WS6 requires; capture/pipeline
  rows stay honest.

### 1.6 iOS parity (deferred but kept symmetric)

`apps/ios/.../TelemetryEvent.swift` already mirrors the schema. iOS samplers map
to `ProcessInfo.thermalState` (`nominal/fair/serious/critical`), `os_proc_available_memory`,
and `UIDevice.batteryLevel`. iOS device-lab runs are gated behind the
`iOS build bringup` task (#19) and are **out of scope for the SM-S937B runbook**
below, but the same bundle format applies (`device-metadata.json` `platform: ios`).

---

## 2. On-device benchmark runbook — SM-S937B

**Target device (real, confirmed):**

| Property | Value |
|---|---|
| Model | `SM-S937B` (Galaxy S25-class) |
| adb id | `R5CY61166KE` |
| SoC | Snapdragon 8 Elite |
| RAM | ~11.4 GB |
| OS | Android 16 |
| `device_tier` | `high` |

This device satisfies the §2 contract "12 GB+ Android flagship" high tier and is
the device that proves the **p95 ASR ≤500 ms high-tier** latency gate (§7). Lower
tiers (`low`/`mid`) require their own SKUs from `configs/device-lab-matrix.json`
(currently `TBD`) and are **not** proven by SM-S937B.

### 2.1 Candidate scope for this device (high tier)

| result_type | model_id | language / pair | runtime_id | manifest |
|---|---|---|---|---|
| asr | `ru-t-one-streaming-2025-09-08` | `ru` / `none` | `sherpa-onnx` | `benchmarks/device-lab/model-artifacts/ru-t-one-streaming-2025-09-08/model-manifest.json` |
| asr | `en-zipformer-mid-high-2023-06-26` | `en` / `none` | `sherpa-onnx` | `…/en-zipformer-mid-high-2023-06-26/model-manifest.json` |
| asr | `zh-en-bilingual-zipformer-2023-02-20` | `zh` / `none` | `sherpa-onnx` | `…/zh-en-bilingual-zipformer-2023-02-20/model-manifest.json` |
| mt | `m2m100-418m` | `ru->en`, `en->ru`, `ru->zh`, `zh->ru` | `onnxruntime-android` | `…/m2m100-418m/model-manifest.json` |
| mt (later) | `milmmt-46-4b-q6-gguf-high-tier` | `ru->en`, `en->ru` | `llama.cpp/GGUF` | `…/milmmt-46-4b-q6/model-manifest.json` |

MiLMMT is the high-tier **experimental** lane (task #30/#32 still in progress:
`libllama.so` arm64 + JNI bridge). It only enters the runbook once the runtime
loads on-device; until then it stays `not_claimed` regardless of corpus results.

### 2.2 Preconditions (before any run)

1. **Corpus present.** Fill `benchmarks/asr/corpus_manifest.sample.json` (and the
   per-language equivalents) and `benchmarks/mt/corpus_manifest.sample.json` to
   the §5 composition: per language 100×short + 100×medium + 50×long + 30×noisy
   + 20×interruption with ground-truth transcripts; per pair 250 aligned
   sentence pairs. The harness reads `manifest["items"][].{id,audioPath,
   durationSec,groundTruth}` (ASR) and `.{id,source,reference}` (MT). Until the
   real corpus exists, WER/CER targets stay `null` in the candidate configs and
   no row can become `supported` (`run_asr_benchmark.py` only marks `needs_review`,
   never `supported`).
2. **Models on device.** Push each model into the app's internal
   `files/models/<model_id>/` (the FUSE caveat from `ws3-a2-device-demo.json`
   means `adb push` into another app's `Android/data` is invisible; use
   `run-as … cp` for the demo, or first-run download in a real build):
   ```sh
   adb -s R5CY61166KE push <local-model-dir> /data/local/tmp/<model_id>
   adb -s R5CY61166KE shell run-as dev.flextranslate \
     cp -r /data/local/tmp/<model_id> files/models/<model_id>
   ```
3. **Manifests validated.** For every candidate:
   ```sh
   python3 scripts/validate_model_manifest.py \
     --manifest benchmarks/device-lab/model-artifacts/<model_id>/model-manifest.json
   ```
4. **Matrix + preflight.** Fill the `high` row of `configs/device-lab-matrix.json`
   with `sku: "SM-S937B"`, `osVersion: "Android 16"`, then:
   ```sh
   python3 scripts/validate_device_lab_matrix.py        # strict, no TBD in the high row
   python3 scripts/validate_device_lab_preflight.py      # adb present, device declarable, artifacts present
   ```
5. **QA build installed** with telemetry export enabled (`telemetry_export_enabled:
   true` in device metadata; the validator hard-requires it true even in fixture mode).

### 2.3 Create the per-run template directory

Use the existing generator so the runner knows exactly which files to fill. The
generated `*.template.json` / `evidence.bundle.template.json` are deliberately
**non-ingestible** (intake only scans `*.bundle.json`):

```sh
# ASR (RU primary)
python3 scripts/create_device_lab_run_template.py \
  --type asr \
  --candidate-id ru-t-one-streaming-2025-09-08 \
  --device-model "SM-S937B" \
  --os-version "Android 16" \
  --device-tier high \
  --language ru \
  --output-dir benchmarks/device-lab/results/2026-06-01/sm-s937b/ru-t-one-streaming-2025-09-08

# MT (M2M-100, ru->en)
python3 scripts/create_device_lab_run_template.py \
  --type mt \
  --candidate-id m2m100-418m \
  --runtime-id "onnxruntime-android" \
  --language-pair "ru->en" \
  --device-model "SM-S937B" \
  --os-version "Android 16" \
  --device-tier high \
  --output-dir benchmarks/device-lab/results/2026-06-01/sm-s937b/m2m100-418m-ru-en
```

Evidence storage convention (from the runbook, anchored to this device/date):

```
benchmarks/device-lab/results/2026-06-01/sm-s937b/<candidate>/
  benchmark-result.json
  telemetry.jsonl
  device-metadata.json
  battery-thermal-log.json
  package-evidence.json
  runtime-conversion-evidence.json
  legal-review-evidence.json
  model-manifest.json
  evidence.bundle.json
```

### 2.4 Measure each metric (concrete method on SM-S937B)

Each candidate gets ONE 30-minute sustained session (the §7 v1 gate). A 60-minute
soak is required before broader beta but not for the v1 supported row.

| Metric | How measured | Feeds field |
|---|---|---|
| **WER / CER** | Run the corpus through `run_asr_benchmark.py --engine sherpa_cli` with a `--sherpa-command` that invokes the on-device recognizer per clip (or post-process the app's `asr_final_emitted` text against ground truth). Harness computes `wer_mean`/`cer_mean`. | `wer`, `cer` |
| **RTF** | `total_elapsed / total_audio` from the harness (sustained decode time ÷ audio duration). Gate: `< 1.0`. | `rtf` |
| **p50 / p95 partial latency** | Percentiles over all `asr_partial_emitted.payload.partial_latency_ms` from the exported telemetry. Gate: p95 ≤ 500 ms (high tier). | `p95_partial_latency_ms` (+ p50 reported in summary) |
| **MT p95 latency** | p95 over `mt_result_emitted.payload.translation_latency_ms`. Gate: ≤ 1.5 s. | `p95_translation_latency_ms` |
| **MT quality** | Define the metric before measuring (BLEU/chrF over the 250-pair corpus; the harness placeholder `token_overlap` is **not** acceptable for a supported row). | `quality_metric`, `quality_score` |
| **Memory high-water** | Max `memory_sample.payload.rss_mb` over the session. Gate: no OS kill; MT must leave ≥20% RAM headroom (≈ ≤9.1 GB used on 11.4 GB). | `memory_peak_mb` |
| **Battery drain/30 min** | `battery_start_percent − battery_end_percent`, normalized to 30 min. Gate: ≤12% (high tier). Also written to `battery-thermal-log.json`. | `battery_delta_percent_30m` |
| **Thermal** | Worst `thermal_sample` state over the session, mapped to the enum. Gate: no sustained `critical`; transient `severe`→`serious_recovered` only if capture continued and it recovered. | `thermal_result` |
| **Audio dropouts** | Count of dropped/overrun frames detected in `AudioCaptureController` (gaps in `audio_callback_received` cadence or `AudioRecord` error). Gate: 0 sustained dropouts. | `audio_dropouts` |
| **Package size** | From `package-evidence.json` (sum of on-device model files). Supported row requires benchmark `package_size_mb ≥ manifest.artifact_size_mb`. | `package_size_mb` |

Resulting `benchmark-result.json` MUST contain the full required set
(`validate_benchmark_result.py`): common fields + ASR set
(`language, wer, cer, rtf, p95_partial_latency_ms, audio_dropouts`) or MT set
(`language_pair, quality_metric, quality_score, p95_translation_latency_ms,
legal_review_status`), plus `evidence_paths[]` listing telemetry, device
metadata, battery/thermal log, package/runtime-conversion/legal evidence, and
manifest. Keep `support_decision: needs_review` until release/legal review.

### 2.5 Mandatory invariants captured in the same session

- **Offline no-network**: run in airplane mode. Telemetry validated with
  `--offline-no-network` must contain zero `network_request_attempted` and every
  row `network_state: offline`. This is the §8 "airplane mode proves local ASR
  continues, no silent fallback" gate, enforced mechanically.
- **Monotonic, single-signature session**: one `session_id`, non-decreasing
  `monotonic_ts_ms`, one model/runtime/build signature (do not switch models mid
  file).
- **Sample sufficiency**: enough events for p95 — the bundle declares
  `telemetry_require_event_counts` (ASR: `asr_partial_emitted=30, memory_sample=30,
  battery_sample=30, thermal_sample=30`; MT swaps in `mt_result_emitted=30`).

### 2.6 Validate one bundle (per candidate)

```sh
# 1. device metadata (strict: physical_device=true, airplane_mode_verified=true)
python3 scripts/validate_device_metadata.py \
  --metadata .../device-metadata.json \
  --expected-device-model "SM-S937B" \
  --expected-os-version "Android 16" \
  --expected-device-tier high

# 2. battery/thermal (strict: thermal_result != not_measured)
python3 scripts/validate_battery_thermal_log.py \
  --log .../battery-thermal-log.json \
  --expected-device-model "SM-S937B" \
  --expected-os-version "Android 16" \
  --expected-thermal-result <measured>

# 3. telemetry (offline + sample sufficiency)
python3 scripts/validate_telemetry_log.py \
  --log .../telemetry.jsonl --offline-no-network \
  --require-event-count asr_partial_emitted=30 \
  --require-event-count memory_sample=30 \
  --require-event-count battery_sample=30 \
  --require-event-count thermal_sample=30

# 4. benchmark result
python3 scripts/validate_benchmark_result.py --type asr --result .../benchmark-result.json

# 5. whole bundle (correlation: model_id↔manifest, runtime_id∈runtime_ids,
#    device fields match metadata, battery/thermal match, evidence_paths complete)
python3 scripts/validate_device_lab_bundle.py --bundle .../evidence.bundle.json
```

A `supported` decision additionally requires `--allow-support-claims` (release/
legal mode only) and, for MT, `legal_review_status: approved_for_distribution`
plus `runtime_conversion_status ∈ {native, converted_and_validated}` and a
shippable `distribution_mode ∈ {bundled, first_run_download}`. M2M-100 is
MIT-licensed and already `approved_for_distribution`; MiLMMT/Gemma-derived needs
its Gemma-terms legal review (§9) before any supported MT row.

---

## 3. Support matrix + gates: turning bundles into evidence-backed rows

### 3.1 Single-command intake

After copying real bundles under `benchmarks/device-lab/results/2026-06-01/sm-s937b/`:

```sh
python3 scripts/validate_device_lab_evidence_root.py \
  --results-root benchmarks/device-lab/results/2026-06-01/sm-s937b \
  --report-json docs/benchmarks/device-lab-intake.generated.json \
  --report-md   docs/benchmarks/device-lab-intake.generated.md
```

This discovers every `*.bundle.json`, runs `validate_device_lab_bundle.py` on
each, refreshes `docs/benchmarks/support-matrix.generated.md`, reruns the
no-false-support guardrail (unless `--allow-support-claims`), and writes the
intake report. With zero real bundles it must still pass conservatively
(`real_bundles_found: 0`, `supported_rows: 0`, status `BLOCKED`).

### 3.2 Generator semantics (conservative by construction)

`generate_support_matrix.py` only renders a `✅ supported` row when the
benchmark-result JSON literally has `support_decision: supported`. `not_claimed`
/ `needs_review` / mock rows are shown as non-support evidence and never
upgraded. So the path to a supported row is: real measurements →
`needs_review` → human release/legal review flips to `supported` under
`--allow-support-claims` → generator renders it → guardrail is intentionally
relaxed only in that approved release context.

### 3.3 Gate thresholds (the bar each supported row must clear)

From `phase-0-contract.md` §7, encoded across the validators:

| Gate | Threshold | Enforced where |
|---|---|---|
| ASR partial p95 (high tier) | ≤ 500 ms | benchmark metric vs. candidate target `p95PartialLatencyMsHighTier: 500` |
| ASR final p95 | reported per language/tier | benchmark summary |
| MT final p95 (supported tiers) | ≤ 1.5 s | candidate target `p95LatencyMs: 1500` |
| UI render overhead p95 | ≤ 100 ms | `ui_*_rendered.payload.render_latency_ms` |
| RTF (sustained ASR) | < 1.0 | `validate_benchmark_result.py` hard-fails supported ASR if `rtf >= 1.0` |
| Sustained session | 30 min (60 min before beta) | telemetry duration + `battery-thermal-log.duration_minutes` |
| Memory | no OS kill; MT ≥20% RAM headroom | `memory_peak_mb` review |
| Thermal | no sustained `critical`; `severe`→recovered only | `validate_benchmark_result.py` blocks supported if thermal `critical`/`not_measured` |
| Battery | ≤12% / 30 min (high tier); mid/low TBD after calibration | `battery_delta_percent_30m` review |
| Legal (MT) | `approved_for_distribution` | `validate_benchmark_result.py` + bundle validator block supported MT otherwise |

**Fail rule (§7):** a pair that passes latency but fails memory/thermal/battery/
crash/privacy/legal/UX is **not supported**, even with good latency. The
validators encode the latency+thermal+legal subset; memory/battery are
review-enforced against the recorded numbers.

### 3.4 Release gate

`check_release_gate.py` runs all scaffold + QA + device-lab + intake validators,
regenerates the support matrix, runs the no-false-support guardrail and
completion audit, then checks Ultragoal status. It stays **BLOCKED** while any
non-superseded goal is failed/pending, while the matrix has no real supported
rows for required launch scope, while `G008`/`G010` proof blockers are
unresolved, or while `G012` final cleanup has not passed. Today
`G008` (ASR real-device proof), `G010` (MT real runtime + legal proof), and
`G012` (final review) are all `failed`, and `G006` is `pending` — so the gate is
correctly blocked and the matrix shows `Supported rows: 0`.

---

## 4. The cleanup link — when demo / `не заявлен` scaffolding may be removed (G008 production-clean transition)

The app currently carries explicit honesty scaffolding (the structural
no-false-claims affordance). The relevant surfaces:

| Surface | File | Current text |
|---|---|---|
| Global banner (every tab) | `apps/android/.../ui/AppScaffold.kt` `DemoBanner()` | `Demo · launch-support не заявлен` |
| Live screen translation badge | `…/ui/screens/LiveScreen.kt:107` | `offline-перевод не заявлен` |
| Live ASR hint | `…/ui/screens/LiveScreen.kt:180,187` | `ASR support пока не заявлен …` / `Текст распознаётся локально (demo, качество не проверено)` |
| Live translation hint | `…/ui/screens/LiveScreen.kt:261` | `(модель …, demo, качество не проверено)` |
| Languages screen badges | `…/ui/screens/LanguagesScreen.kt:84,88` | `offline-ASR: адаптер готов (demo)` / `offline-перевод: не заявлен` |
| Models screen | `…/ui/screens/ModelsScreen.kt:119` | `Готов к локальному распознаванию (demo, качество не проверено)` |
| Diagnostics | `…/ui/screens/DiagnosticsScreen.kt:72` | `asrSupport = не заявлен` |

These are intentionally **scoped per language/pair and per tier** — they are not
one global flag. The transition is therefore **incremental and evidence-gated**.

### 4.1 The rule (what justifies dropping a label)

A demo/`не заявлен` label for **a specific `(model_id, language(_pair),
device_tier)`** may be removed **only when all of the following hold** for that
exact scope:

1. A real SM-S937B (or correct-tier) `evidence.bundle.json` exists and passes
   `validate_device_lab_bundle.py`.
2. Its `benchmark-result.json` has `support_decision: supported` and clears the
   §3.3 gates: RTF < 1.0 (ASR), p95 ≤ 500 ms ASR partial / ≤ 1.5 s MT, thermal
   not `critical`/`not_measured`, memory no-kill + ≥20% headroom (MT), battery
   ≤ tier budget, audio_dropouts within budget, and a defined WER/CER (ASR) or
   quality metric (MT) target that is met.
3. For MT: `legal_review_status: approved_for_distribution` and a shippable
   `distribution_mode`.
4. `generate_support_matrix.py` renders that row as `✅ supported`, and the
   intake/no-false-support guardrail passes in the approved release context.
5. `check_release_gate.py` is no longer blocked on the corresponding proof goal
   (`G008` for ASR scope, `G010` for MT scope) and `G012` final review passes.

When (1)–(5) are true for a scope, the app may, **for that scope only**, replace
the amber `не заявлен`/`demo` badge with a `supported` affordance and drive the
copy from the generated support matrix outcome (a build/config input), not from
the device's own telemetry.

### 4.2 What this looks like in practice

- **`asrSupport` in Diagnostics** flips from `не заявлен` to the proven
  language list once those ASR rows are `supported` (e.g. `ru, en, zh` after
  three passing SM-S937B bundles).
- **`LiveScreen` translation badge** flips from `offline-перевод не заявлен`
  to a supported indicator **only for the directions** whose M2M-100 rows pass
  (e.g. `ru->en`, `en->ru` first; `ru->zh`, `zh->ru` independently).
- **The global `DemoBanner`** is the **last** thing to go: it may only be
  removed when the full required launch scope is supported AND `G012` passes.
  Until then it stays on, even if some individual rows are green, because it is
  the app-wide honesty backstop.

### 4.3 What stays gated when a gate fails

If any gate fails for a scope, that scope **keeps** its demo/`не заявлен` label
and the §1 contract requires the UI to keep showing local transcription with an
explicit unsupported state (`OfflineFirstState.UnsupportedOfflineTranslation`).
Concretely:
- **ASR passes, MT fails** (e.g. M2M-100 quality below target, or MiLMMT thermal
  `critical`): ASR badges may drop demo; the translation badge stays
  `offline-перевод не заявлен` and the UI continues transcription only.
- **High tier passes, mid/low unmeasured**: only the `high`-tier scope drops its
  label; `low`/`mid` stay gated until their own SKUs (currently `TBD` in
  `device-lab-matrix.json`) produce passing bundles.
- **MiLMMT legal review not `approved_for_distribution`**: the MiLMMT row can
  never be `supported` (the validators block it), so its high-tier quality-MT
  claim stays demo regardless of latency — the M2M-100 row carries the launch
  claim instead.
- **Any privacy/no-silent-network regression**: a single
  `network_request_attempted` in an offline run fails telemetry validation,
  fails the bundle, and blocks the release gate — labels stay on.

---

## 5. WS6 implementation checklist (Android, this device)

App (QA build only; production gated behind a build flag):
- [ ] `TelemetryEmitter` writing schema-conformant rows at the §1.3 boundaries.
- [ ] `TelemetrySampler` (memory 1 s / battery 60 s / thermal 60 s + listener).
- [ ] Monotonic per-session clock from `SystemClock.elapsedRealtime()`; new
      `session_id` on model switch.
- [ ] `mode/network_state` derived so offline runs are hard-`offline` with no
      `network_request_attempted`.
- [ ] JSONL sink + adb/`run-as` export path (§1.5).
- [ ] `DiagnosticsScreen` telemetry section shows live counts + export.
- [ ] Numeric metrics in `payload` serialized as numbers (or `payload: Map<String, Any>` in QA build).

Lab (per candidate in §2.1):
- [ ] Corpus filled to §5 composition; WER/CER + MT quality targets defined.
- [ ] Models pushed to internal `files/models/<id>/`; manifests validated.
- [ ] `device-lab-matrix.json` high row = `SM-S937B` / `Android 16`; preflight passes.
- [ ] 30-min airplane-mode session per candidate; bundle created from template.
- [ ] All six per-bundle validators pass (§2.6); `support_decision: needs_review`.
- [ ] Intake refreshes support matrix; release/legal review flips qualifying rows
      to `supported`; release gate unblocks `G008`/`G010` for proven scope.
- [ ] Scoped label removal per §4; global `DemoBanner` removed only at full
      launch scope + `G012` pass.

## 6. Non-negotiable support rule (restated)

If any required field is missing, the result stays `needs_review`/`not_claimed`.
Missing evidence is never converted into a support claim. Telemetry well-formed
≠ supported. A working A2 on-device demo (already captured in
`ws3-a2-device-demo.json`) is explicitly **not** a launch-support claim — WS6
benchmark evidence is what closes that gap.
