#!/usr/bin/env python3
"""Validate the device-lab matrix before G008/G010 evidence collection.

Default mode is strict and rejects placeholder device targets. Use --allow-tbd
only for repository planning/gate validation while support claims remain blocked.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

DEFAULT_MATRIX = Path('configs/device-lab-matrix.json')
EXPECTED_TIERS = ['low', 'mid', 'high']
EXPECTED_RUN_PROFILES = [
    'cold_start_airplane_mode',
    'warm_start_airplane_mode',
    'thirty_minute_sustained_offline_asr',
    'thirty_minute_sustained_offline_mt_if_candidate_loads',
    'flaky_network_cloud_disabled',
    'cloud_opt_in_online',
]
EXPECTED_ARTIFACTS = [
    'benchmark_result_json',
    'telemetry_jsonl',
    'device_metadata_json',
    'model_manifest_json',
    'battery_thermal_log',
    'screen_recording_or_mobile_mcp_trace',
]
PLACEHOLDER_RE = re.compile(r'^(?:|tbd|todo|replace(?:[_ -].*)?|unknown|n/a|null)$', re.IGNORECASE)
ANDROID_OS_RE = re.compile(r'^(?:Android\s*)?\d+(?:\.\d+)?(?:\s*\([^)]*\))?$|^API\s*\d+$', re.IGNORECASE)
IOS_OS_RE = re.compile(r'^(?:iOS\s*)?\d+(?:\.\d+){0,2}(?:\s*\([^)]*\))?$', re.IGNORECASE)


def load_json(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text())
    except FileNotFoundError as exc:
        raise SystemExit(f'{path}: missing device-lab matrix') from exc
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def is_placeholder(value: Any) -> bool:
    return not isinstance(value, str) or bool(PLACEHOLDER_RE.match(value.strip()))


def exact_list_errors(name: str, actual: Any, expected: list[str]) -> list[str]:
    if not isinstance(actual, list) or not all(isinstance(x, str) for x in actual):
        return [f'{name}: expected list of strings']
    errors: list[str] = []
    missing = [x for x in expected if x not in actual]
    extra = [x for x in actual if x not in expected]
    duplicate = sorted({x for x in actual if actual.count(x) > 1})
    if missing:
        errors.append(f'{name}: missing {missing}')
    if extra:
        errors.append(f'{name}: unexpected {extra}')
    if duplicate:
        errors.append(f'{name}: duplicate {duplicate}')
    if actual != expected and not missing and not extra and not duplicate:
        errors.append(f'{name}: order must match canonical lab order')
    return errors


def validate_platform(
    *,
    tier: str,
    platform: str,
    data: Any,
    allow_tbd: bool,
    missing_device_fields: list[str],
    warnings: list[str],
) -> list[str]:
    prefix = f'deviceTiers[{tier}].{platform}'
    errors: list[str] = []
    if not isinstance(data, dict):
        return [f'{prefix}: expected object']

    for field in ['sku', 'osVersion']:
        value = data.get(field)
        if is_placeholder(value):
            missing_device_fields.append(f'{tier}/{platform}.{field}')
            if allow_tbd:
                warnings.append(f'{prefix}.{field}: placeholder allowed only for planning mode')
            else:
                errors.append(f'{prefix}.{field}: must be a concrete non-placeholder value')
        elif len(str(value).strip()) < 3:
            errors.append(f'{prefix}.{field}: value is too short to identify a real lab target')

    os_value = str(data.get('osVersion', '')).strip()
    if not is_placeholder(os_value):
        pattern = ANDROID_OS_RE if platform == 'android' else IOS_OS_RE
        if not pattern.match(os_value):
            errors.append(f'{prefix}.osVersion: expected concrete {platform} OS version, got {os_value!r}')

    if platform == 'android':
        ram = data.get('minRamGb')
        if not isinstance(ram, (int, float)) or ram <= 0:
            errors.append(f'{prefix}.minRamGb: expected positive number')
    else:
        klass = data.get('class')
        if is_placeholder(klass):
            errors.append(f'{prefix}.class: expected non-placeholder iOS device class')
    return errors


def validate_matrix(matrix: dict[str, Any], *, allow_tbd: bool, allow_support_claims_enabled: bool) -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []
    missing_device_fields: list[str] = []

    if matrix.get('schemaVersion') != 1:
        errors.append('schemaVersion: expected 1')
    if matrix.get('status') != 'execution_required':
        errors.append('status: expected execution_required')
    if not isinstance(matrix.get('supportClaimsAllowed'), bool):
        errors.append('supportClaimsAllowed: expected boolean')
    elif matrix.get('supportClaimsAllowed') is True and not allow_support_claims_enabled:
        errors.append('supportClaimsAllowed: true requires explicit --allow-support-claims-enabled review mode')

    errors.extend(exact_list_errors('runProfiles', matrix.get('runProfiles'), EXPECTED_RUN_PROFILES))
    errors.extend(exact_list_errors('requiredArtifactsPerRun', matrix.get('requiredArtifactsPerRun'), EXPECTED_ARTIFACTS))

    tiers = matrix.get('deviceTiers')
    if not isinstance(tiers, list):
        errors.append('deviceTiers: expected list')
        tiers = []
    tier_names = [t.get('tier') if isinstance(t, dict) else None for t in tiers]
    if tier_names != EXPECTED_TIERS:
        errors.append(f'deviceTiers: expected tier order {EXPECTED_TIERS}, got {tier_names}')

    for tier_obj in tiers:
        if not isinstance(tier_obj, dict):
            errors.append('deviceTiers[]: expected object')
            continue
        tier = str(tier_obj.get('tier', 'unknown'))
        claim = tier_obj.get('intendedClaim')
        if is_placeholder(claim):
            errors.append(f'deviceTiers[{tier}].intendedClaim: expected non-placeholder claim intent')
        for platform in ['android', 'ios']:
            errors.extend(validate_platform(
                tier=tier,
                platform=platform,
                data=tier_obj.get(platform),
                allow_tbd=allow_tbd,
                missing_device_fields=missing_device_fields,
                warnings=warnings,
            ))

    execution_readiness = 'READY_FOR_LAB_EXECUTION' if not errors and not missing_device_fields else 'BLOCKED_PLANNING_ONLY'
    if errors:
        validation_status = 'FAIL'
    elif missing_device_fields:
        validation_status = 'PASS_WITH_TBD_PLANNING_ONLY'
    else:
        validation_status = 'PASS'

    return {
        'schema_version': 1,
        'validation_status': validation_status,
        'execution_readiness': execution_readiness,
        'allow_tbd': allow_tbd,
        'support_claims_allowed': bool(matrix.get('supportClaimsAllowed', False)),
        'tier_count': len(tiers),
        'platform_target_count': len(tiers) * 2,
        'run_profile_count': len(matrix.get('runProfiles', [])) if isinstance(matrix.get('runProfiles'), list) else 0,
        'required_artifact_count': len(matrix.get('requiredArtifactsPerRun', [])) if isinstance(matrix.get('requiredArtifactsPerRun'), list) else 0,
        'missing_device_fields': missing_device_fields,
        'warnings': warnings,
        'errors': errors,
        'non_evidence_notice': 'A valid device matrix is a lab prerequisite only; it is not ASR/MT benchmark evidence, legal approval, or support-claim approval.',
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--matrix', default=str(DEFAULT_MATRIX))
    ap.add_argument('--allow-tbd', action='store_true', help='Planning mode: allow placeholder SKU/OS values but report BLOCKED_PLANNING_ONLY.')
    ap.add_argument('--allow-support-claims-enabled', action='store_true', help='Explicit review mode for matrices with supportClaimsAllowed=true.')
    ap.add_argument('--json', action='store_true')
    args = ap.parse_args()

    report = validate_matrix(
        load_json(Path(args.matrix)),
        allow_tbd=args.allow_tbd,
        allow_support_claims_enabled=args.allow_support_claims_enabled,
    )
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print('Device lab matrix validation:', report['validation_status'])
        print('execution_readiness:', report['execution_readiness'])
        print('allow_tbd:', report['allow_tbd'])
        print('support_claims_allowed:', report['support_claims_allowed'])
        print('tier_count:', report['tier_count'])
        print('platform_target_count:', report['platform_target_count'])
        print('missing_device_fields:', len(report['missing_device_fields']))
        for item in report['missing_device_fields']:
            print('missing:', item)
        for item in report['warnings']:
            print('warning:', item)
        for item in report['errors']:
            print('error:', item)
        print(report['non_evidence_notice'])
    return 0 if not report['errors'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
