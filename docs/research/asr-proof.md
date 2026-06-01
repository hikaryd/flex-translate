# Offline ASR Proof Plan

Status: G003 scaffold/evidence plan. No ASR support is claimed until real model/device runs fill the evidence matrix.

## Decision boundary

- Runtime under proof: **sherpa-onnx**.
- VAD under proof: **Silero VAD** via sherpa-onnx-compatible path.
- Fallback/baseline only: Whisper through whisper.cpp or sherpa-onnx Whisper path.
- Support decision is per language + model + device tier.

## Candidate matrix source

Machine-readable candidate list: `configs/asr-candidates.json`.

## Evidence requirements

Each benchmark result must include:

| Field | Why it is required |
|---|---|
| device_model / os_version / device_tier | Prevents desktop/emulator evidence from becoming mobile support claims |
| model_id / package_size_mb | Tracks exact artifact and storage budget |
| WER/CER | Accuracy gate against corpus ground truth |
| RTF | Proves local sustained recognition can keep up with speech |
| p50/p95 partial latency | Proves live caption responsiveness |
| memory_peak_mb | Proves app remains resident |
| battery_delta_percent_30m | Proves sustained use budget |
| thermal_result | Prevents support if critical/severe thermal state occurs |
| audio_dropouts | Catches platform audio capture instability |
| support_decision | Explicit pass/fail/no-claim marker |

## Support decision rules

A candidate remains `not_claimed` unless all required fields are present and:

- RTF < 1.0.
- p95 partial latency ≤ 500 ms for high-tier support claims.
- No critical/severe thermal state.
- No OS kill or sustained audio capture dropout.
- WER/CER threshold is defined and met for the final corpus.
- Battery budget is defined and met for the declared tier.

If any non-latency gate fails, the language/device pair is unsupported even if latency looks good.

## Benchmark harness

`benchmarks/asr/run_asr_benchmark.py` supports:

- `mock` engine for deterministic harness verification.
- `sherpa_cli` engine wrapper for later device/lab integration.

The mock engine is not support evidence. It only validates metric computation and report format.

## Current support status

No ASR model/language/device tier is marked supported yet. G003 cannot be fully closed as real ASR proof until real-device or at least target-platform benchmark artifacts exist.
