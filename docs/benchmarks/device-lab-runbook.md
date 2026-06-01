# Device Lab Evidence Runbook

Status: proof-kit for retrying G008 and G010. This runbook makes the failed proof goals actionable but does not itself prove support.

## Purpose

G008 and G010 require real target-device evidence. The repository can prepare harnesses and schemas, but final support requires actual runs on selected iOS/Android devices with real model artifacts and native telemetry.

## Before running

1. Fill `configs/device-lab-matrix.json` with exact device SKUs and OS versions.
2. Validate the matrix strictly with `python3 scripts/validate_device_lab_matrix.py`.
3. Download or build model artifacts in an internal lab location.
4. Record model source URL, checksum, license, package size, package evidence, runtime conversion path/status, and legal/release review evidence in `model-manifest.json`, `package-evidence.json`, `runtime-conversion-evidence.json`, and `legal-review-evidence.json`.
5. Validate every manifest with `scripts/validate_model_manifest.py --manifest <model-manifest.json>` before running benchmarks.
6. Confirm legal review status before any external/beta model distribution; use `--allow-distribution` only after explicit legal/release approval.
7. Install QA build with telemetry export enabled.

## Device-matrix validation gate

`configs/device-lab-matrix.json` is the authoritative target-device list for
G008/G010 runs. Validate it before creating executable lab commands:

```sh
python3 scripts/validate_device_lab_matrix.py
```

The default mode rejects placeholder `TBD`/`REPLACE_*` device SKUs and OS
versions, malformed iOS/Android fields, missing run profiles, and missing
required artifacts. The repository's current planning state is intentionally not
strictly executable; CI uses explicit planning mode instead:

```sh
python3 scripts/validate_device_lab_matrix.py --allow-tbd
python3 scripts/validate_device_lab_matrix_validator.py
```

Planning mode is only a conservative scaffold check. A filled matrix is
necessary for lab execution, but it is not ASR/MT benchmark evidence, model legal
approval, support-matrix approval, or permission to close G008/G010.


## Execution preflight gate

Before retrying G008/G010 on a lab machine, run the conservative preflight:

```sh
python3 scripts/validate_device_lab_preflight.py
```

The command checks strict device-matrix readiness, required local tooling
(`adb`, `xcrun`/`xcodebuild`, `npx`), connected/declarable iOS and Android
targets, model artifact manifests under `benchmarks/device-lab/model-artifacts`,
and a writable evidence output root. In the repository planning state, CI uses
the non-authoritative blocked-mode assertion:

```sh
python3 scripts/validate_device_lab_preflight.py --allow-missing --expect-blocked
```

A passing preflight only means the machine is ready to collect real evidence. It
is not ASR/MT benchmark evidence, legal approval, battery/thermal proof, or a
support claim, and it must not close G008/G010 without validated real bundles.

## ASR proof run

Primary ASR runtime evidence targets sherpa-onnx with Silero VAD candidates.

For every language/model/tier candidate in `configs/asr-candidates.json`:

1. Run corpus through `benchmarks/asr/run_asr_benchmark.py` using real `sherpa_cli` or platform integration wrapper.
2. Export telemetry JSONL from the app/device.
3. Record package size, memory peak, battery delta over 30 minutes, thermal result, and audio dropouts.
4. Record `device-metadata.json` and validate it with `scripts/validate_device_metadata.py --metadata <device-metadata.json>`.
5. Record `battery-thermal-log.json` and validate it with `scripts/validate_battery_thermal_log.py --log <battery-thermal-log.json>`.
6. Record `package-evidence.json`, `runtime-conversion-evidence.json`, and `legal-review-evidence.json`; ASR normally uses `runtime_conversion_status: native`.
7. Validate telemetry with `scripts/validate_telemetry_log.py --log <telemetry.jsonl> --offline-no-network --require-event-count asr_partial_emitted=<N> --require-event-count memory_sample=<N> --require-event-count battery_sample=<N> --require-event-count thermal_sample=<N>`.
8. Merge/validate result with `scripts/validate_benchmark_result.py --type asr --result <path>`.
9. Only mark `supported` if all gates pass.

## MT proof run

For every model/runtime/language-pair/tier candidate in `configs/mt-candidates.json`:

1. Run corpus through `benchmarks/mt/run_mt_benchmark.py` using real runtime command or platform wrapper.
2. Export telemetry JSONL from the app/device.
3. Record package size, memory peak, battery delta over 30 minutes, thermal result, quality metric, and legal review status.
4. Record `device-metadata.json` and validate it with `scripts/validate_device_metadata.py --metadata <device-metadata.json>`.
5. Record `battery-thermal-log.json` and validate it with `scripts/validate_battery_thermal_log.py --log <battery-thermal-log.json>`.
6. Record `package-evidence.json`, `runtime-conversion-evidence.json`, and `legal-review-evidence.json`; supported MT requires `runtime_conversion_status: native` or `converted_and_validated` plus approved legal review evidence.
7. Validate telemetry with `scripts/validate_telemetry_log.py --log <telemetry.jsonl> --offline-no-network --require-event-count mt_result_emitted=<N> --require-event-count memory_sample=<N> --require-event-count battery_sample=<N> --require-event-count thermal_sample=<N>`.
8. Merge/validate result with `scripts/validate_benchmark_result.py --type mt --result <path>`.
9. Only mark `supported` if performance and legal gates pass.


## Telemetry validation gate

`scripts/validate_telemetry_log.py` rejects evidence that cannot support latency or offline guarantees:

- missing session, device, model, runtime, build, or language correlation fields;
- non-monotonic timestamps inside one session;
- mixed model/runtime/device identifiers inside one session;
- insufficient event samples for p95/coverage checks requested with `--require-event-count`;
- `network_request_attempted` or non-offline network state during `--offline-no-network` runs.

Passing telemetry validation is necessary but not sufficient for support. Benchmark metrics, legal review, and release-gate status still decide support.

## Evidence storage convention

```text
benchmarks/device-lab/results/<date>/<device>/<candidate>/benchmark-result.json
benchmarks/device-lab/results/<date>/<device>/<candidate>/telemetry.jsonl
benchmarks/device-lab/results/<date>/<device>/<candidate>/device-metadata.json
benchmarks/device-lab/results/<date>/<device>/<candidate>/battery-thermal-log.json
benchmarks/device-lab/results/<date>/<device>/<candidate>/package-evidence.json
benchmarks/device-lab/results/<date>/<device>/<candidate>/runtime-conversion-evidence.json
benchmarks/device-lab/results/<date>/<device>/<candidate>/legal-review-evidence.json
benchmarks/device-lab/results/<date>/<device>/<candidate>/model-manifest.json
```

## Creating a safe run template

Before a real lab run, create a non-ingestible template directory so the runner
knows exactly which files to fill:

First generate the full run plan:

```sh
python3 scripts/generate_device_lab_run_plan.py
python3 scripts/generate_device_lab_readiness.py
python3 scripts/generate_proof_coverage.py
python3 scripts/generate_proof_retry_decision.py
```

The generated `docs/benchmarks/device-lab-run-plan.generated.md` expands the
device tiers and ASR/MT candidate configs into concrete template commands. It is
not evidence and must remain blocked while device SKUs or OS versions are `TBD`.
The generated `docs/benchmarks/device-lab-readiness.generated.md` and
`docs/benchmarks/device-lab-readiness.generated.json` are machine-readable gates
for lab/release readiness. They are also not evidence: while they report
`BLOCKED`, do not retry/close G008 or G010 as proven and do not make ASR/MT
support claims.
The generated `docs/benchmarks/proof-coverage.generated.md` and
`docs/benchmarks/proof-coverage.generated.json` audit whether real device-lab
bundles contain proof-usable ASR/MT rows for G008/G010. They do not close proof
goals by themselves; they provide the coverage evidence reviewers must inspect
before retrying those goals.

The generated `docs/benchmarks/proof-retry-decision.generated.md` and
`docs/benchmarks/proof-retry-decision.generated.json` combine Ultragoal status,
readiness, intake, proof coverage, and support-matrix state into the single
proof retry decision for G008/G010. In the repository planning state this gate
must remain `BLOCKED`; it is not ASR/MT benchmark proof, legal approval, or a
support claim.

For CI coverage of the eventual happy path, run:

```sh
python3 scripts/validate_proof_coverage_transition.py
```

That self-test creates temporary synthetic release-review fixtures only, validates
them with the bundle validator, and proves the coverage gate can report
`READY_FOR_GOAL_REVIEW` without writing persistent support evidence into the
repository.

```sh
python3 scripts/create_device_lab_run_template.py \
  --type asr \
  --candidate-id en-zipformer-mid-high-2023-06-26 \
  --device-model "Pixel 8 Pro lab" \
  --os-version "Android 15 lab" \
  --device-tier high \
  --language en \
  --output-dir benchmarks/device-lab/results/<date>/<device>/<candidate>
```

For MiLMMT/high-tier translation:

```sh
python3 scripts/create_device_lab_run_template.py \
  --type mt \
  --candidate-id milmmt-46-4b-q6-gguf-high-tier \
  --runtime-id "llama.cpp/GGUF" \
  --language-pair "en->ru" \
  --device-model "iPhone 15 Pro lab" \
  --os-version "iOS 18.5" \
  --device-tier high \
  --output-dir benchmarks/device-lab/results/<date>/<device>/<candidate>
```

The generator writes `*.template.json` and `evidence.bundle.template.json`; these
are deliberately ignored by the intake scanner. Fill every `REPLACE_*` value from
real device output, rename to `benchmark-result.json`, `telemetry.jsonl`,
`device-metadata.json`, `battery-thermal-log.json`, `package-evidence.json`,
`runtime-conversion-evidence.json`, `legal-review-evidence.json`, `model-manifest.json`, and
`evidence.bundle.json`, then run the bundle/intake validators. Do not use the
generated templates as evidence.

## Evidence bundle validation gate

After each ASR or MT run, create a bundle descriptor that points at the benchmark result JSON, telemetry JSONL, and model manifest. Validate it with:

```sh
python3 scripts/validate_device_lab_bundle.py --bundle <bundle.json>
```

Use `--allow-support-claims` only in a release/legal review context after real device evidence, telemetry, model manifest approval, and support matrix review are complete. The sample bundles under `benchmarks/device-lab/samples/bundles/` are fixtures only and must remain `not_claimed`.

A valid bundle proves evidence correlation only: benchmark `model_id` must match the manifest, benchmark `runtime_id` must be in manifest `runtime_ids`, benchmark `package_size_mb` must be compatible with manifest/package evidence for supported rows, device metadata must match benchmark device fields, battery/thermal logs must match benchmark battery and thermal fields, MT supported rows must have native or validated runtime conversion evidence, and benchmark `evidence_paths` must include telemetry, device metadata, battery/thermal log, package evidence, runtime-conversion evidence, legal-review evidence, and model manifest paths.


## Durable intake report

For every external lab drop, persist an intake report next to the support-matrix
refresh so G008/G010 reviewers can cite structured evidence instead of stdout:

```sh
python3 scripts/validate_device_lab_evidence_root.py \
  --results-root benchmarks/device-lab/results/<date> \
  --report-json docs/benchmarks/device-lab-intake.generated.json \
  --report-md docs/benchmarks/device-lab-intake.generated.md
```

The repository validation command is:

```sh
python3 scripts/validate_device_lab_intake_report.py
```

The generated `device-lab-intake.generated.json` records results roots, real and
sample bundle counts, validated bundle paths, supported rows, allow-support-claim
mode, blockers, and a non-evidence notice. A BLOCKED intake report is expected
while there are zero real bundles; it is not benchmark proof or legal approval.

## Single-command evidence intake

Use the intake wrapper after copying real device-lab bundles into `benchmarks/device-lab/results/`:

```sh
python3 scripts/validate_device_lab_evidence_root.py --include-samples
```

The command discovers `*.bundle.json` files under the results root, validates each bundle, refreshes `docs/benchmarks/support-matrix.generated.md`, and reruns `scripts/validate_no_false_support_claims.py` when support claims are not explicitly allowed. With no real result bundles it must still pass conservatively with `real_bundles_found: 0`, sample bundles validated, and `supported_rows: 0`.

Use custom roots for lab drops:

```sh
python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/2026-06-01/pixel-8
```

`--allow-support-claims` is a release/legal-review mode only. Do not use it for normal lab intake; it permits `supported` bundle decisions only after real benchmark, telemetry, manifest, support-matrix, and legal gates have already passed.
For positive-path CI coverage without mutating the repository support matrix, the
intake wrapper also supports a temporary output override:

```sh
python3 scripts/validate_device_lab_evidence_intake_transition.py
```

That self-test creates temporary synthetic supported bundles, runs
`validate_device_lab_evidence_root.py --allow-support-claims --support-matrix-output <temp-file>`,
asserts the temporary support matrix has supported rows, and verifies
`docs/benchmarks/support-matrix.generated.md` remains conservative.

## Non-negotiable support rule

If any required field is missing, the result remains `needs_review` or `not_claimed`. Do not convert missing evidence into a support claim.

## Sample non-claim manifests

The files `benchmarks/device-lab/samples/model-manifest.asr.internal.json` and `benchmarks/device-lab/samples/model-manifest.milmmt.internal.json` are validator fixtures only. They are intentionally internal/not-reviewed and must not be treated as support or distribution approval.

## Proof retry decision gate

Run `python3 scripts/validate_proof_retry_decision.py` before retrying G008/G010. The generated artifacts are decision gates only, not ASR/MT benchmark proof, legal approval, or support claims.
