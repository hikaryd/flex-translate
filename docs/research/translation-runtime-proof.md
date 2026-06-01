# Offline Translation Runtime Proof Plan

Status: G004 scaffold/evidence plan. No offline translation support is claimed until model/runtime/device/legal evidence exists.

## Decision boundary

- MiLMMT Q6/GGUF is a **high-tier experimental candidate**, not a default.
- Smaller quantized LLM MT, dedicated MT models, and commercial/on-device SDKs are mandatory fallback lanes.
- Runtime is evaluated independently from model quality: llama.cpp/GGUF vs MLC LLM vs LiteRT-LM vs SDK paths.
- Legal review is a release blocker before beta distribution or external model downloads.

## Candidate matrix source

Machine-readable candidate list: `configs/mt-candidates.json`.

## Required evidence

| Field | Required reason |
|---|---|
| device_model / os_version / device_tier | Prevents desktop-only evidence from becoming mobile support claims |
| language_pair | Support is pair-specific |
| model_id / runtime_id / package_size_mb | Tracks exact artifact and runtime storage cost |
| quality_score / quality_metric | Prevents fast but bad translation from passing |
| p95_translation_latency_ms | Live translation responsiveness |
| memory_peak_mb / battery_delta_percent_30m / thermal_result | Sustained mobile viability |
| legal_review_status | Required for Gemma/MiLMMT/GGUF and other model distribution |
| support_decision | Explicit pass/fail/no-claim marker |

## Support decision rules

A translation candidate remains `not_claimed` unless:

- language pair is in matrix;
- quality metric and threshold are defined and met;
- p95 final translation latency ≤ 1.5s for declared tier;
- memory headroom ≥ 20% or platform-equivalent safe margin;
- thermal and battery gates pass;
- package size and model download UX are acceptable;
- legal review is `approved_for_distribution` or candidate is internal-only with no user-facing support claim.

## Benchmark harness

`benchmarks/mt/run_mt_benchmark.py` supports:

- `mock` engine for deterministic report validation only;
- `command` engine wrapper for later runtime-specific CLIs.

The mock engine is not support evidence.

## Current support status

No offline translation model/language/device tier is marked supported yet.
