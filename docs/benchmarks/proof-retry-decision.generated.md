# G008/G010 Proof Retry Decision Gate

Generated conservative decision status for whether failed proof goals can be retried or marked complete.

**Aggregate retry decision:** BLOCKED
**Can retry any goal:** false
**Can mark any goal complete:** false

This retry decision gate is not ASR/MT benchmark proof, legal approval, or a support claim; it only blocks or permits a later human retry/review decision.

## Input summary

- device_lab_readiness: BLOCKED
- lab_execution_readiness: BLOCKED
- device_lab_preflight: BLOCKED
- device_lab_intake: BLOCKED
- real_bundles_found: 0
- proof_coverage: BLOCKED
- support_matrix_supported_rows: 0
- support_claims_allowed_by_intake: False
- open_failed_or_pending_ultragoal_stories: G008-offline-asr-real-device-benchmark-pr, G010-offline-translation-real-runtime-and, G012-final-qa-cleanup-and-independent-rev

## Per-goal decisions

| Goal | Ultragoal status | Decision | Can retry | Can mark complete | Proof coverage | Proof-usable rows |
| --- | --- | --- | --- | --- | --- | ---: |
| G008-offline-asr-real-device-benchmark-pr | failed | BLOCKED | false | false | BLOCKED | 0 |
| G010-offline-translation-real-runtime-and | failed | BLOCKED | false | false | BLOCKED | 0 |

## Aggregate blockers

- `one_or_more_proof_goals_not_ready_for_retry`
- `open_failed_or_pending_ultragoal_stories`
- `g008_goal_status_failed`
- `device_lab_readiness_blocked`
- `device_lab_execution_not_ready`
- `release_claim_readiness_blocked`
- `device_lab_preflight_blocked`
- `device_matrix_has_tbd_sku_or_os`
- `no_real_device_lab_bundles_found`
- `device_lab_intake_report_blocked_or_missing`
- `zero_supported_rows`
- `proof_or_final_qa_goals_open`
- `support_claims_not_allowed_by_matrix`
- `device_lab_intake_blocked`
- `support_claims_not_allowed_by_intake`
- `sample_fixtures_only_not_real_evidence`
- `g008_proof_coverage_blocked`
- `g008_has_no_proof_usable_asr_rows`
- `g010_has_no_proof_usable_mt_rows`
- `g010_goal_status_failed`
- `g010_proof_coverage_blocked`

## Per-goal blockers and evidence gaps

### G008-offline-asr-real-device-benchmark-pr

Blockers:
- `g008_goal_status_failed`
- `device_lab_readiness_blocked`
- `device_lab_execution_not_ready`
- `release_claim_readiness_blocked`
- `device_lab_preflight_blocked`
- `device_matrix_has_tbd_sku_or_os`
- `no_real_device_lab_bundles_found`
- `device_lab_intake_report_blocked_or_missing`
- `zero_supported_rows`
- `proof_or_final_qa_goals_open`
- `support_claims_not_allowed_by_matrix`
- `device_lab_intake_blocked`
- `support_claims_not_allowed_by_intake`
- `sample_fixtures_only_not_real_evidence`
- `g008_proof_coverage_blocked`
- `g008_has_no_proof_usable_asr_rows`
- `g010_has_no_proof_usable_mt_rows`

Evidence gaps:
- `g008_real_asr_device_benchmark_proof_missing`
- `ultragoal_proof_story_not_complete`
- `zero_proof_usable_rows`
- `no_real_device_lab_bundles`

Ledger evidence/failure reason: Retry attempt 2 cannot proceed: device-lab preflight remains BLOCKED (TBD device matrix, no connected Android/iOS target, no real model manifests under benchmarks/device-lab/model-artifacts), device-lab results contain only .gitkeep/no real bundles, proof coverage has bundle_count=0/asr_proof_usable_rows=0, and proof retry decision generated during retry remains BLOCKED with G008 can_retry=false/can_mark_complete=false. No ASR support claim created.

### G010-offline-translation-real-runtime-and

Blockers:
- `g010_goal_status_failed`
- `device_lab_readiness_blocked`
- `device_lab_execution_not_ready`
- `release_claim_readiness_blocked`
- `device_lab_preflight_blocked`
- `device_matrix_has_tbd_sku_or_os`
- `no_real_device_lab_bundles_found`
- `device_lab_intake_report_blocked_or_missing`
- `zero_supported_rows`
- `proof_or_final_qa_goals_open`
- `support_claims_not_allowed_by_matrix`
- `device_lab_intake_blocked`
- `support_claims_not_allowed_by_intake`
- `sample_fixtures_only_not_real_evidence`
- `g010_proof_coverage_blocked`
- `g010_has_no_proof_usable_mt_rows`
- `g008_has_no_proof_usable_asr_rows`

Evidence gaps:
- `g010_real_mt_runtime_proof_missing`
- `g010_legal_review_proof_missing`
- `ultragoal_proof_story_not_complete`
- `zero_proof_usable_rows`
- `no_real_device_lab_bundles`

Ledger evidence/failure reason: Real offline translation runtime/legal proof cannot be honestly completed in this environment because no target iOS/Android device/lab run, MiLMMT/GGUF or fallback model artifacts, runtime conversion results, package-size evidence, memory/battery/thermal telemetry, translation quality evaluation, or Gemma/MiLMMT/GGUF legal review are available. Retry this goal when target devices/models and legal review are available; scaffold and run pattern exist in docs/research/translation-runtime-proof.md and benchmarks/mt/README.md.

## Required next actions

- Replace TBD device matrix values with exact iOS/Android SKUs and OS versions.
- Pass scripts/validate_device_lab_preflight.py on the lab machine with connected target devices and real model artifacts.
- Collect real G008 ASR and G010 MT evidence bundles under benchmarks/device-lab/results/.
- Regenerate intake, support matrix, readiness, proof coverage, and this retry decision gate from real bundles.
- Retry/complete G008 and G010 only after this decision gate reports READY_FOR_RETRY_REVIEW and reviewers approve the evidence.

## Commands

```sh
python3 scripts/validate_device_lab_preflight.py
python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/REPLACE_DATE
python3 scripts/validate_device_lab_intake_report.py
python3 scripts/validate_proof_coverage.py
python3 scripts/validate_proof_retry_decision.py
python3 scripts/check_release_gate.py
```

Do not retry or complete G008/G010 from this repository state while the aggregate retry decision is `BLOCKED`.

