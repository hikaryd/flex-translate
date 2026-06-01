# ASR/MT Proof Coverage Gate

Generated conservative coverage status for retrying G008 and G010 from real device-lab bundles.

**Aggregate proof coverage:** BLOCKED
**G008 ASR proof coverage:** BLOCKED
**G010 MT proof coverage:** BLOCKED
**Real bundle count:** 0

This proof coverage report audits evidence completeness only; it does not create support claims or close G008/G010.

## Expected scope from configs

- asr_candidate_count: 5
- mt_candidate_count: 4
- asr_planned_device_rows: 22
- mt_planned_runtime_pair_device_rows: 72

## Current blockers

- `device_matrix_has_tbd_sku_or_os`
- `no_real_device_lab_bundles_found`
- `g008_has_no_proof_usable_asr_rows`
- `g010_has_no_proof_usable_mt_rows`

## Coverage summary

| Goal | Bundles | Proof-usable rows | Supported rows | Unsupported rows | Blocked rows |
| --- | ---: | ---: | ---: | ---: | ---: |
| G008 ASR | 0 | 0 | 0 | 0 | 0 |
| G010 MT | 0 | 0 | 0 | 0 | 0 |

## Bundle diagnostics

No real device-lab bundles found under the results root.

## Required command sequence

```sh
python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/REPLACE_DATE
python3 scripts/generate_proof_coverage.py --results-root benchmarks/device-lab/results/REPLACE_DATE
python3 scripts/validate_proof_coverage.py
python3 scripts/check_release_gate.py
```

G008/G010 must not be re-checkpointed as complete while this report is `BLOCKED`.

