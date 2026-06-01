# Device Lab Readiness Gate

Generated conservative status for external G008/G010 evidence collection and release-claim safety.

**Aggregate readiness:** BLOCKED
**Lab execution readiness:** BLOCKED
**Release claim readiness:** BLOCKED
**Support claims allowed:** false
**Real bundles found:** 0
**Supported rows:** 0
**Device matrix validation:** PASS_WITH_TBD_PLANNING_ONLY
**Device-lab preflight:** BLOCKED
**Device-lab intake:** BLOCKED

This readiness report is a gate/status document only; it creates no ASR/MT support claim.

## Current blockers

- Lab blocker: `device_matrix_has_tbd_sku_or_os`
- Lab blocker: `device_lab_preflight_blocked`
  - Missing device field: `low/android.sku`
  - Missing device field: `low/android.osVersion`
  - Missing device field: `low/ios.sku`
  - Missing device field: `low/ios.osVersion`
  - Missing device field: `mid/android.sku`
  - Missing device field: `mid/android.osVersion`
  - Missing device field: `mid/ios.sku`
  - Missing device field: `mid/ios.osVersion`
  - Missing device field: `high/android.sku`
  - Missing device field: `high/android.osVersion`
  - Missing device field: `high/ios.sku`
  - Missing device field: `high/ios.osVersion`
  - Preflight blocker: `device_matrix_not_ready` — strict matrix validation is not PASS; missing=['low/android.sku', 'low/android.osVersion', 'low/ios.sku', 'low/ios.osVersion', 'mid/android.sku', 'mid/android.osVersion', 'mid/ios.sku', 'mid/ios.osVersion', 'high/android.sku', 'high/android.osVersion', 'high/ios.sku', 'high/ios.osVersion']; errors=['deviceTiers[low].android.sku: must be a concrete non-placeholder value', 'deviceTiers[low].android.osVersion: must be a concrete non-placeholder value', 'deviceTiers[low].ios.sku: must be a concrete non-placeholder value', 'deviceTiers[low].ios.osVersion: must be a concrete non-placeholder value', 'deviceTiers[mid].android.sku: must be a concrete non-placeholder value', 'deviceTiers[mid].android.osVersion: must be a concrete non-placeholder value', 'deviceTiers[mid].ios.sku: must be a concrete non-placeholder value', 'deviceTiers[mid].ios.osVersion: must be a concrete non-placeholder value', 'deviceTiers[high].android.sku: must be a concrete non-placeholder value', 'deviceTiers[high].android.osVersion: must be a concrete non-placeholder value', 'deviceTiers[high].ios.sku: must be a concrete non-placeholder value', 'deviceTiers[high].ios.osVersion: must be a concrete non-placeholder value']
  - Preflight blocker: `android_device_not_connected` — adb reports no connected Android device in device state
  - Preflight blocker: `ios_device_not_listed` — xcrun xctrace lists no iOS device/simulator entries
  - Preflight blocker: `model_artifacts_missing` — no model-manifest.json files found under benchmarks/device-lab/model-artifacts
- Evidence/release blocker: `no_real_device_lab_bundles_found`
- Evidence/release blocker: `device_lab_intake_report_blocked_or_missing`
- Evidence/release blocker: `zero_supported_rows`
- Evidence/release blocker: `proof_or_final_qa_goals_open`
- Evidence/release blocker: `support_claims_not_allowed_by_matrix`
  - Intake blocker: `no_real_device_lab_bundles_found`
  - Intake blocker: `zero_supported_rows`
  - Intake blocker: `sample_fixtures_only_not_real_evidence`
- Open Ultragoal: `G008-offline-asr-real-device-benchmark-pr` (failed) — Offline ASR real-device benchmark proof
- Open Ultragoal: `G010-offline-translation-real-runtime-and` (failed) — Offline translation real runtime and legal proof
- Open Ultragoal: `G012-final-qa-cleanup-and-independent-rev` (failed) — Final QA, cleanup, and independent review gate

## Planned proof scope

- ASR candidates: 5
- MT candidates: 4
- Planned ASR runs minimum: 22
- Planned MT runtime/pair runs minimum: 72

## Required next actions

- Fill configs/device-lab-matrix.json with exact iOS/Android SKUs and OS versions.
- Run python3 scripts/validate_device_lab_matrix.py without --allow-tbd before executing lab runs.
- Generate per-run templates from docs/benchmarks/device-lab-run-plan.generated.md.
- Run ASR/MT benchmarks on physical target devices with real model artifacts and offline telemetry.
- Place completed evidence.bundle.json files under benchmarks/device-lab/results/<date>/...
- Run python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/<date>.
- Run python3 scripts/check_release_gate.py and keep it blocked until G008/G010/G012 are legitimately complete.

## Commands

```sh
python3 scripts/generate_device_lab_run_plan.py
python3 scripts/validate_device_lab_matrix.py --allow-tbd
python3 scripts/validate_device_lab_matrix.py
python3 scripts/validate_device_lab_preflight.py
python3 scripts/create_device_lab_run_template.py --help
python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/REPLACE_DATE
python3 scripts/check_release_gate.py
```

Do not call `update_goal complete` while this report is `BLOCKED` or while G008/G010/G012 remain open.

