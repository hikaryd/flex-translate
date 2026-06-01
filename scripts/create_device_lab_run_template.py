#!/usr/bin/env python3
"""Create safe device-lab run templates for real ASR/MT proof attempts.

The generated files are intentionally non-ingestible templates: the bundle file is
named `evidence.bundle.template.json`, not `*.bundle.json`, so the evidence-root
intake command will not treat it as proof until a human/lab runner fills the
placeholders, renames the final files, and runs validation.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

ASR_EVENTS = {
    'asr_partial_emitted': 30,
    'memory_sample': 30,
    'battery_sample': 30,
    'thermal_sample': 30,
}
MT_EVENTS = {
    'mt_result_emitted': 30,
    'memory_sample': 30,
    'battery_sample': 30,
    'thermal_sample': 30,
}


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def find_candidate(kind: str, candidate_id: str | None) -> dict[str, Any]:
    path = Path('configs/asr-candidates.json' if kind == 'asr' else 'configs/mt-candidates.json')
    config = load_json(path)
    candidates = config.get('candidates', [])
    if not candidates:
        raise SystemExit(f'{path}: no candidates configured')
    if candidate_id:
        for candidate in candidates:
            if candidate.get('id') == candidate_id:
                return candidate
        raise SystemExit(f'{path}: unknown candidate id {candidate_id!r}')
    return candidates[0]


def default_runtime(kind: str, candidate: dict[str, Any]) -> str:
    if kind == 'asr':
        return str(candidate.get('runtime', 'REPLACE_RUNTIME_ID'))
    runtimes = candidate.get('runtimeCandidates') or ['REPLACE_RUNTIME_ID']
    return str(runtimes[0])


def default_language(kind: str, candidate: dict[str, Any], language: str | None, language_pair: str | None) -> tuple[str | None, str | None]:
    if kind == 'asr':
        return language or str(candidate.get('language', 'REPLACE_LANGUAGE')), None
    pairs = candidate.get('languagePairs') or ['REPLACE_LANGUAGE_PAIR']
    return None, language_pair or str(pairs[0])


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')


def render_readme(kind: str, candidate_id: str, output_dir: Path) -> str:
    return f'''# Device-lab run template: {kind.upper()} / {candidate_id}

These files are templates for real G008/G010 proof collection. They are not proof,
not benchmark results, and not support claims until every `REPLACE_*` value is
filled from a real iOS/Android device run and validation passes.

## Fill and rename

1. Replace all placeholders in `benchmark-result.template.json`, `telemetry.template.jsonl`, and `model-manifest.template.json` with real lab values.
2. Save final files as:
   - `benchmark-result.json`
   - `telemetry.jsonl`
   - `device-metadata.json`
   - `battery-thermal-log.json`
   - `package-evidence.json`
   - `runtime-conversion-evidence.json`
   - `legal-review-evidence.json`
   - `model-manifest.json`
   - `evidence.bundle.json` copied from `evidence.bundle.template.json`
3. Keep `support_decision: needs_review` until release/legal review explicitly approves `supported`.
4. Validate locally:

```sh
python3 scripts/validate_device_lab_bundle.py --bundle {output_dir}/evidence.bundle.json
python3 scripts/validate_device_lab_evidence_root.py --results-root {output_dir}
```

Use `--allow-support-claims` only in the release/legal review path after real
device metrics, telemetry, model provenance, generated support matrix, and legal
review are all approved.
'''


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--type', choices=['asr', 'mt'], required=True)
    ap.add_argument('--output-dir', required=True)
    ap.add_argument('--candidate-id')
    ap.add_argument('--device-model', default='REPLACE_DEVICE_MODEL')
    ap.add_argument('--os-version', default='REPLACE_OS_VERSION')
    ap.add_argument('--device-tier', choices=['low', 'mid', 'high'], default='high')
    ap.add_argument('--language')
    ap.add_argument('--language-pair')
    ap.add_argument('--runtime-id')
    args = ap.parse_args()

    out = Path(args.output_dir)
    out.mkdir(parents=True, exist_ok=True)
    candidate = find_candidate(args.type, args.candidate_id)
    candidate_id = str(candidate['id'])
    runtime_id = args.runtime_id or default_runtime(args.type, candidate)
    language, language_pair = default_language(args.type, candidate, args.language, args.language_pair)

    result: dict[str, Any] = {
        'goal_id': 'G008-offline-asr-real-device-benchmark-pr' if args.type == 'asr' else 'G010-offline-translation-real-runtime-and',
        'result_type': args.type,
        'support_decision': 'needs_review',
        'device_model': args.device_model,
        'os_version': args.os_version,
        'device_tier': args.device_tier,
        'model_id': candidate_id,
        'runtime_id': runtime_id,
        'package_size_mb': 'REPLACE_PACKAGE_SIZE_MB_NUMBER',
        'memory_peak_mb': 'REPLACE_MEMORY_PEAK_MB_NUMBER',
        'battery_delta_percent_30m': 'REPLACE_BATTERY_DELTA_PERCENT_30M_NUMBER',
        'thermal_result': 'REPLACE_nominal_fair_serious_recovered_or_critical',
        'evidence_paths': [
            str(out / 'telemetry.jsonl'),
            str(out / 'device-metadata.json'),
            str(out / 'battery-thermal-log.json'),
            str(out / 'package-evidence.json'),
            str(out / 'runtime-conversion-evidence.json'),
            str(out / 'legal-review-evidence.json'),
            str(out / 'model-manifest.json'),
            str(out / 'mobile-mcp-or-screen-recording.txt'),
        ],
        'notes': 'Template only; replace every REPLACE_* value with real device evidence before validation.',
    }
    if args.type == 'asr':
        result.update({
            'language': language,
            'wer': 'REPLACE_WER_NUMBER',
            'cer': 'REPLACE_CER_NUMBER',
            'rtf': 'REPLACE_RTF_NUMBER',
            'p95_partial_latency_ms': 'REPLACE_P95_PARTIAL_LATENCY_MS_NUMBER',
            'audio_dropouts': 'REPLACE_AUDIO_DROPOUTS_INTEGER',
        })
    else:
        result.update({
            'language_pair': language_pair,
            'quality_metric': 'REPLACE_QUALITY_METRIC_NAME',
            'quality_score': 'REPLACE_QUALITY_SCORE_NUMBER',
            'p95_translation_latency_ms': 'REPLACE_P95_TRANSLATION_LATENCY_MS_NUMBER',
            'legal_review_status': 'not_reviewed',
        })

    manifest = {
        'schema_version': 1,
        'model_id': candidate_id,
        'model_name': str(candidate.get('model', candidate_id)),
        'model_version': 'REPLACE_MODEL_VERSION',
        'model_family': 'sherpa-onnx' if args.type == 'asr' else ('milmmt' if 'milmmt' in candidate_id else 'dedicated-mt'),
        'artifact_format': 'onnx' if args.type == 'asr' else 'gguf',
        'artifact_source_url': 'REPLACE_ARTIFACT_SOURCE_URL',
        'license_url': 'REPLACE_LICENSE_URL',
        'sha256': 'REPLACE_64_HEX_SHA256',
        'artifact_size_mb': 'REPLACE_ARTIFACT_SIZE_MB_NUMBER',
        'package_evidence_path': str(out / 'package-evidence.json'),
        'runtime_ids': [runtime_id],
        'runtime_conversion_status': 'native' if args.type == 'asr' else 'REPLACE_native_converted_and_validated_or_blocked',
        'runtime_conversion_evidence_path': str(out / 'runtime-conversion-evidence.json'),
        'runtime_conversion_notes': 'REPLACE_RUNTIME_CONVERSION_NOTES',
        'legal_review_evidence_path': str(out / 'legal-review-evidence.json'),
        'intended_use': args.type,
        'distribution_mode': 'internal_lab_only',
        'legal_review_status': 'not_reviewed' if args.type == 'asr' else 'internal_only',
        'review_owner': 'REPLACE_REVIEW_OWNER',
        'review_date': 'REPLACE_YYYY-MM-DD',
        'commercial_restrictions_reviewed': False,
        'privacy_impact_reviewed': False,
        'app_store_policy_reviewed': False,
        'notes': 'Template only; internal lab use until legal/release review approves distribution.',
    }

    package_evidence = {
        'schema_version': 1,
        'model_id': candidate_id,
        'sha256': 'REPLACE_64_HEX_SHA256',
        'artifact_size_mb': 'REPLACE_ARTIFACT_SIZE_MB_NUMBER',
        'package_size_mb': 'REPLACE_PACKAGE_SIZE_MB_NUMBER',
        'measurement_method': 'REPLACE_PACKAGE_SIZE_MEASUREMENT_METHOD',
        'package_files': [
            {
                'path': 'REPLACE_PACKAGE_FILE_PATH',
                'size_mb': 'REPLACE_PACKAGE_FILE_SIZE_MB_NUMBER',
            },
        ],
        'notes': 'Template only; replace with real package-size evidence before validation.',
    }

    runtime_conversion = {
        'schema_version': 1,
        'model_id': candidate_id,
        'runtime_id': runtime_id,
        'runtime_conversion_status': manifest['runtime_conversion_status'],
        'conversion_tool': 'REPLACE_CONVERSION_TOOL_OR_NATIVE',
        'source_format': 'REPLACE_SOURCE_FORMAT',
        'target_format': manifest['artifact_format'],
        'validation_status': 'REPLACE_not_validated_or_validated',
        'notes': 'Template only; replace with real runtime conversion/load validation evidence before validation.',
    }

    legal_review = {
        'schema_version': 1,
        'model_id': candidate_id,
        'legal_review_status': manifest['legal_review_status'],
        'distribution_mode': manifest['distribution_mode'],
        'reviewer': manifest['review_owner'],
        'review_date': manifest['review_date'],
        'artifact_source_url': manifest['artifact_source_url'],
        'license_url': manifest['license_url'],
        'commercial_restrictions_reviewed': manifest['commercial_restrictions_reviewed'],
        'privacy_impact_reviewed': manifest['privacy_impact_reviewed'],
        'app_store_policy_reviewed': manifest['app_store_policy_reviewed'],
        'decision': manifest['legal_review_status'],
        'sensitive_family_reviewed': 'REPLACE_TRUE_IF_MILMMT_GEMMA_OR_COMMUNITY_QUANT_APPROVED',
        'sensitive_family_notes': 'REPLACE_SENSITIVE_FAMILY_LEGAL_NOTES_OR_NOT_APPLICABLE',
        'notes': 'Template only; replace with real legal/release review evidence before any distribution approval.',
    }

    metadata = {
        'schema_version': 1,
        'platform': 'REPLACE_ios_or_android',
        'device_model': args.device_model,
        'device_sku': 'REPLACE_DEVICE_SKU',
        'device_tier': args.device_tier,
        'os_version': args.os_version,
        'physical_device': 'REPLACE_TRUE_AFTER_REAL_DEVICE_RUN',
        'airplane_mode_verified': 'REPLACE_TRUE_AFTER_AIRPLANE_MODE_CHECK',
        'telemetry_export_enabled': True,
        'app_build_id': 'REPLACE_APP_BUILD_ID',
        'notes': 'Template only; replace with real iOS/Android device metadata before validation.',
    }

    battery_thermal = {
        'schema_version': 1,
        'session_id': 'REPLACE_SESSION_ID',
        'device_model': args.device_model,
        'os_version': args.os_version,
        'duration_minutes': 'REPLACE_DURATION_MINUTES_NUMBER',
        'battery_start_percent': 'REPLACE_BATTERY_START_PERCENT_NUMBER',
        'battery_end_percent': 'REPLACE_BATTERY_END_PERCENT_NUMBER',
        'battery_delta_percent_30m': 'REPLACE_BATTERY_DELTA_PERCENT_30M_NUMBER',
        'thermal_result': 'REPLACE_nominal_fair_serious_recovered_or_critical',
        'samples': [
            {
                'minute': 0,
                'battery_percent': 'REPLACE_BATTERY_PERCENT_NUMBER',
                'thermal_state': 'REPLACE_THERMAL_STATE',
            },
        ],
        'notes': 'Template only; export real battery/thermal samples from the target device before validation.',
    }

    bundle = {
        'schema_version': 1,
        'bundle_id': f'REPLACE_BUNDLE_ID_{args.type}_{candidate_id}',
        'result_type': args.type,
        'expected_support_decision': 'needs_review',
        'benchmark_result_path': str(out / 'benchmark-result.json'),
        'telemetry_log_path': str(out / 'telemetry.jsonl'),
        'device_metadata_path': str(out / 'device-metadata.json'),
        'battery_thermal_log_path': str(out / 'battery-thermal-log.json'),
        'model_manifest_path': str(out / 'model-manifest.json'),
        'telemetry_require_event_counts': ASR_EVENTS if args.type == 'asr' else MT_EVENTS,
        'offline_no_network': True,
        'notes': 'Template only; not a real device proof and not a support claim until filled, renamed, and validated.',
    }

    telemetry_event = {
        'schema_version': 1,
        'timestamp_ms': 0,
        'session_id': 'REPLACE_SESSION_ID',
        'event_type': 'REPLACE_REAL_EVENT_TYPE',
        'device_model': args.device_model,
        'os_version': args.os_version,
        'model_id': candidate_id,
        'runtime_id': runtime_id,
        'language': language or language_pair,
        'network_state': 'offline',
        'build_id': 'REPLACE_BUILD_ID',
        'value': 'REPLACE_EVENT_VALUE',
        'notes': 'Template JSONL row; export real telemetry from the app/device instead of using this row.',
    }

    write_json(out / 'benchmark-result.template.json', result)
    write_json(out / 'device-metadata.template.json', metadata)
    write_json(out / 'battery-thermal-log.template.json', battery_thermal)
    write_json(out / 'package-evidence.template.json', package_evidence)
    write_json(out / 'runtime-conversion-evidence.template.json', runtime_conversion)
    write_json(out / 'legal-review-evidence.template.json', legal_review)
    write_json(out / 'model-manifest.template.json', manifest)
    write_json(out / 'evidence.bundle.template.json', bundle)
    (out / 'telemetry.template.jsonl').write_text(json.dumps(telemetry_event, ensure_ascii=False) + '\n')
    (out / 'README.md').write_text(render_readme(args.type, candidate_id, out))

    print('Device-lab run template creation: PASS')
    print(f'output_dir: {out}')
    print(f'type: {args.type}')
    print(f'candidate_id: {candidate_id}')
    print('ingestible_bundle_files_created: 0')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
