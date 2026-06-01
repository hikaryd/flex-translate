#!/usr/bin/env python3
"""Validate a complete device-lab evidence bundle.

A bundle ties together benchmark-result JSON, telemetry JSONL, device metadata,
battery/thermal logs, and model manifest provenance/legal metadata. Passing this validator means the evidence package is
internally consistent and suitable for release-gate consideration; it does not
create a support claim unless --allow-support-claims is explicitly provided and
all lower-level validators also pass.
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any

REQUIRED = [
    'schema_version',
    'bundle_id',
    'result_type',
    'expected_support_decision',
    'benchmark_result_path',
    'telemetry_log_path',
    'device_metadata_path',
    'battery_thermal_log_path',
    'model_manifest_path',
]


def run(cmd: list[str]) -> None:
    print('RUN', ' '.join(cmd))
    subprocess.run(cmd, check=True)


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def resolve_evidence_path(bundle_path: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute() or path.is_file():
        return path
    bundle_relative = bundle_path.parent / path
    if bundle_relative.is_file():
        return bundle_relative
    return path


def validate_bundle(bundle_path: Path, allow_support_claims: bool) -> None:
    bundle = load_json(bundle_path)
    missing = [key for key in REQUIRED if key not in bundle or bundle[key] in (None, '')]
    if missing:
        raise SystemExit(f'{bundle_path}: missing required bundle fields: {missing}')
    if bundle['schema_version'] != 1:
        raise SystemExit(f'{bundle_path}: schema_version must be 1')
    if bundle['result_type'] not in {'asr', 'mt'}:
        raise SystemExit(f'{bundle_path}: result_type must be asr or mt')

    result_path = resolve_evidence_path(bundle_path, bundle['benchmark_result_path'])
    telemetry_path = resolve_evidence_path(bundle_path, bundle['telemetry_log_path'])
    metadata_path = resolve_evidence_path(bundle_path, bundle['device_metadata_path'])
    battery_thermal_path = resolve_evidence_path(bundle_path, bundle['battery_thermal_log_path'])
    manifest_path = resolve_evidence_path(bundle_path, bundle['model_manifest_path'])
    for path in [result_path, telemetry_path, metadata_path, battery_thermal_path, manifest_path]:
        if not path.is_file():
            raise SystemExit(f'{bundle_path}: referenced evidence file does not exist: {path}')

    result = load_json(result_path)
    manifest = load_json(manifest_path)

    if result.get('result_type') != bundle['result_type']:
        raise SystemExit(f'{bundle_path}: result_type mismatch between bundle and benchmark result')
    if result.get('support_decision') != bundle['expected_support_decision']:
        raise SystemExit(f'{bundle_path}: support_decision differs from expected_support_decision')
    if result.get('model_id') != manifest.get('model_id'):
        raise SystemExit(f'{bundle_path}: benchmark model_id does not match manifest model_id')
    if result.get('runtime_id') not in manifest.get('runtime_ids', []):
        raise SystemExit(f'{bundle_path}: benchmark runtime_id is not listed in manifest runtime_ids')
    if result.get('support_decision') == 'supported':
        if float(result.get('package_size_mb', -1)) < float(manifest.get('artifact_size_mb', 0)):
            raise SystemExit(f'{bundle_path}: supported benchmark package_size_mb must be >= manifest artifact_size_mb')
        if result.get('result_type') == 'mt' and manifest.get('runtime_conversion_status') not in {'native', 'converted_and_validated'}:
            raise SystemExit(f'{bundle_path}: supported MT evidence requires native or converted_and_validated runtime_conversion_status')

    evidence_paths = set(result.get('evidence_paths', []))
    for raw_key, resolved_path in [
        ('telemetry_log_path', telemetry_path),
        ('device_metadata_path', metadata_path),
        ('battery_thermal_log_path', battery_thermal_path),
        ('model_manifest_path', manifest_path),
    ]:
        raw_path = str(bundle[raw_key])
        accepted_paths = {raw_path, str(resolved_path)}
        if not accepted_paths & evidence_paths:
            raise SystemExit(f'{bundle_path}: benchmark evidence_paths must include one of {sorted(accepted_paths)}')
    for manifest_key in ['package_evidence_path', 'runtime_conversion_evidence_path', 'legal_review_evidence_path']:
        raw_path = str(manifest.get(manifest_key, ''))
        if not raw_path:
            raise SystemExit(f'{bundle_path}: model manifest must include {manifest_key}')
        resolved_path = resolve_evidence_path(manifest_path, raw_path)
        accepted_paths = {raw_path, str(resolved_path)}
        if not accepted_paths & evidence_paths:
            raise SystemExit(f'{bundle_path}: benchmark evidence_paths must include one of {sorted(accepted_paths)}')

    support_decision = result.get('support_decision')
    if support_decision == 'supported' and not allow_support_claims:
        raise SystemExit(f'{bundle_path}: supported evidence requires --allow-support-claims')
    if support_decision == 'supported':
        if manifest.get('legal_review_status') != 'approved_for_distribution':
            raise SystemExit(f'{bundle_path}: supported evidence requires approved_for_distribution model manifest')
        if manifest.get('distribution_mode') not in {'bundled', 'first_run_download'}:
            raise SystemExit(f'{bundle_path}: supported evidence requires shippable distribution_mode')

    run(['python3', 'scripts/validate_benchmark_result.py', '--type', bundle['result_type'], '--result', str(result_path)])

    telemetry_cmd = ['python3', 'scripts/validate_telemetry_log.py', '--log', str(telemetry_path)]
    if bundle.get('offline_no_network', False):
        telemetry_cmd.append('--offline-no-network')
    for event_type, count in sorted(bundle.get('telemetry_require_event_counts', {}).items()):
        telemetry_cmd.extend(['--require-event-count', f'{event_type}={count}'])
    run(telemetry_cmd)

    metadata_cmd = [
        'python3', 'scripts/validate_device_metadata.py',
        '--metadata', str(metadata_path),
        '--expected-device-model', str(result.get('device_model')),
        '--expected-os-version', str(result.get('os_version')),
        '--expected-device-tier', str(result.get('device_tier')),
    ]
    if support_decision != 'supported':
        metadata_cmd.append('--allow-placeholder')
    run(metadata_cmd)

    battery_cmd = [
        'python3', 'scripts/validate_battery_thermal_log.py',
        '--log', str(battery_thermal_path),
        '--expected-device-model', str(result.get('device_model')),
        '--expected-os-version', str(result.get('os_version')),
        '--expected-battery-delta-percent-30m', str(result.get('battery_delta_percent_30m')),
        '--expected-thermal-result', str(result.get('thermal_result')),
    ]
    if support_decision != 'supported':
        battery_cmd.append('--allow-not-measured')
    run(battery_cmd)

    manifest_cmd = ['python3', 'scripts/validate_model_manifest.py', '--manifest', str(manifest_path)]
    if support_decision == 'supported' and allow_support_claims:
        manifest_cmd.append('--allow-distribution')
    run(manifest_cmd)

    print('Device lab bundle validation: PASS')
    print(f'bundle_id: {bundle["bundle_id"]}')
    print(f'support_decision: {support_decision}')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--bundle', required=True)
    ap.add_argument('--allow-support-claims', action='store_true')
    args = ap.parse_args()
    validate_bundle(Path(args.bundle), args.allow_support_claims)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
