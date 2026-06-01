# Model Distribution Legal/Compliance Checklist

Status: checklist for G010 retry and beta readiness. This is not legal advice and not an approval.

## Required before beta distribution or external model downloads

For every model artifact:

- [ ] Model name and exact version recorded.
- [ ] Source URL recorded.
- [ ] License/terms URL recorded.
- [ ] SHA256 checksum recorded.
- [ ] Artifact size recorded.
- [ ] Distribution mode selected: bundled, first-run download, internal-lab-only, or blocked.
- [ ] App-store policy risk reviewed.
- [ ] Privacy/data handling impact reviewed.
- [ ] Commercial restrictions reviewed.
- [ ] Reviewer/owner/date recorded.
- [ ] Machine-readable `legal-review-evidence.json` recorded and referenced by `model-manifest.json` as `legal_review_evidence_path`.

## Gemma/MiLMMT/GGUF special gate

MiLMMT/Gemma-derived or community quantized artifacts must remain **internal-lab-only** unless legal/compliance explicitly marks them `approved_for_distribution`.

## Decision values

- `approved_for_distribution`
- `internal_only`
- `blocked`
- `not_reviewed`


## Machine-readable model manifest gate

Every real ASR/VAD/MT artifact used for G008/G010 proof must include a `model-manifest.json` and, for any approved distribution, a separate `legal-review-evidence.json` that validates through:

```sh
python3 scripts/validate_model_manifest.py --manifest <model-manifest.json>
```

Use `--allow-distribution` only in a legal/release-review context after the reviewer has explicitly approved external distribution. Lab/internal manifests should normally remain `internal_lab_only` with `not_reviewed` or `internal_only`. MiLMMT/Gemma-derived or community GGUF artifacts must remain `internal_lab_only` unless `legal_review_status` is `approved_for_distribution` and all commercial, privacy, and app-store policy checks are true.

Required manifest/legal evidence:

- exact model id/name/version/family and runtime ids;
- source URL, license URL, SHA256, artifact size, and artifact format;
- intended use: ASR, VAD, MT, or baseline;
- distribution mode and legal review status;
- review owner/date plus commercial, privacy, and app-store policy review booleans;
- `legal_review_evidence_path` pointing to `legal-review-evidence.json`;
- legal review evidence with reviewer/date, source/license URL match, distribution decision, commercial/privacy/app-store decisions, and sensitive-family review notes for MiLMMT/Gemma-derived/community quantized artifacts.
