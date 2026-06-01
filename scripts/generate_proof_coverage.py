#!/usr/bin/env python3
"""Generate conservative ASR/MT proof coverage status from real device-lab bundles.

This report answers a narrower question than readiness: if real lab bundles are
present, do they cover the proof obligations needed to retry/close G008/G010?
It never promotes evidence to support by itself.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

DEFAULT_RESULTS_ROOT = Path('benchmarks/device-lab/results')
DEFAULT_MD = Path('docs/benchmarks/proof-coverage.generated.md')
DEFAULT_JSON = Path('docs/benchmarks/proof-coverage.generated.json')

COMMON_REQUIRED = [
    'goal_id', 'result_type', 'support_decision', 'device_model', 'os_version', 'device_tier',
    'model_id', 'runtime_id', 'package_size_mb', 'memory_peak_mb',
    'battery_delta_percent_30m', 'thermal_result', 'evidence_paths',
]
ASR_REQUIRED = ['language', 'wer', 'cer', 'rtf', 'p95_partial_latency_ms', 'audio_dropouts']
MT_REQUIRED = ['language_pair', 'quality_metric', 'quality_score', 'p95_translation_latency_ms', 'legal_review_status']
NON_PROOF_DECISIONS = {'not_claimed', 'needs_review'}
PLACEHOLDERS = {'', 'TBD', 'tbd', 'REPLACE_DEVICE_MODEL', 'REPLACE_OS_VERSION', 'lab-device-tbd'}
PLACEHOLDER_QUALITY_METRICS = {'', 'TBD', 'token_overlap_placeholder'}


def load_json(path: str | Path) -> dict[str, Any]:
    data = json.loads(Path(path).read_text())
    if not isinstance(data, dict):
        raise ValueError(f'{path}: expected JSON object')
    return data


def discover_bundles(root: Path) -> list[Path]:
    if root.is_file():
        return [root] if root.name.endswith('.bundle.json') and '.template.' not in root.name else []
    if not root.exists():
        return []
    return sorted(path for path in root.rglob('*.bundle.json') if path.is_file() and '.template.' not in path.name)


def resolve_evidence_path(bundle_path: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute() or path.is_file():
        return path
    bundle_relative = bundle_path.parent / path
    if bundle_relative.is_file():
        return bundle_relative
    return path


def device_matrix_has_tbd(matrix_path: Path = Path('configs/device-lab-matrix.json')) -> bool:
    matrix = load_json(matrix_path)
    for tier in matrix.get('deviceTiers', []):
        for platform in ['android', 'ios']:
            device = tier.get(platform, {})
            if device.get('sku') in PLACEHOLDERS or device.get('osVersion') in PLACEHOLDERS:
                return True
    return False


def expected_scope(asr_candidates_path: Path = Path('configs/asr-candidates.json'), mt_candidates_path: Path = Path('configs/mt-candidates.json')) -> dict[str, Any]:
    asr = load_json(asr_candidates_path)
    mt = load_json(mt_candidates_path)
    asr_rows = 0
    for candidate in asr.get('candidates', []):
        asr_rows += len(candidate.get('tiers', [])) * 2
    mt_rows = 0
    for candidate in mt.get('candidates', []):
        mt_rows += len(candidate.get('targetTiers', [])) * len(candidate.get('languagePairs', [])) * len(candidate.get('runtimeCandidates', [])) * 2
    return {
        'asr_candidate_count': len(asr.get('candidates', [])),
        'mt_candidate_count': len(mt.get('candidates', [])),
        'asr_planned_device_rows': asr_rows,
        'mt_planned_runtime_pair_device_rows': mt_rows,
    }


def classify_result(result: dict[str, Any], result_type: str) -> list[str]:
    reasons: list[str] = []
    required = COMMON_REQUIRED + (ASR_REQUIRED if result_type == 'asr' else MT_REQUIRED)
    missing = [key for key in required if key not in result or result[key] is None]
    if missing:
        reasons.append('missing_required_fields:' + ','.join(missing))
    if result.get('result_type') != result_type:
        reasons.append('result_type_mismatch')
    if str(result.get('device_model', '')) in PLACEHOLDERS or str(result.get('os_version', '')) in PLACEHOLDERS:
        reasons.append('placeholder_device_or_os')
    if float(result.get('package_size_mb') or 0) <= 0:
        reasons.append('package_size_not_measured')
    if float(result.get('memory_peak_mb') or 0) <= 0:
        reasons.append('memory_not_measured')
    if result.get('thermal_result') in {None, 'not_measured', 'critical'}:
        reasons.append('thermal_not_passing_or_unmeasured')
    decision = str(result.get('support_decision', ''))
    if decision in NON_PROOF_DECISIONS:
        reasons.append(f'non_proof_decision:{decision}')
    if result_type == 'asr':
        if result.get('rtf') is None or float(result.get('rtf') or 999) >= 1.0:
            reasons.append('asr_rtf_not_passing')
        if result.get('audio_dropouts') is None or int(result.get('audio_dropouts') or 0) != 0:
            reasons.append('asr_audio_dropouts_not_zero')
        if str(result.get('device_tier')) == 'high' and (result.get('p95_partial_latency_ms') is None or float(result.get('p95_partial_latency_ms') or 999999) > 500):
            reasons.append('asr_high_tier_p95_over_500ms')
        # WER/CER thresholds are still corpus-specific, so only require measured values here.
        if result.get('wer') is None or result.get('cer') is None:
            reasons.append('asr_accuracy_not_measured')
    else:
        if str(result.get('legal_review_status')) != 'approved_for_distribution' and decision == 'supported':
            reasons.append('mt_supported_without_approved_legal_review')
        if str(result.get('quality_metric', '')) in PLACEHOLDER_QUALITY_METRICS:
            reasons.append('mt_quality_metric_placeholder')
        if result.get('quality_score') is None:
            reasons.append('mt_quality_not_measured')
        if result.get('p95_translation_latency_ms') is None or float(result.get('p95_translation_latency_ms') or 999999) > 1500:
            reasons.append('mt_p95_translation_over_1500ms')
    return reasons


def bundle_record(bundle_path: Path) -> dict[str, Any]:
    record: dict[str, Any] = {'bundle_path': str(bundle_path), 'valid_bundle_shape': False, 'result_type': 'unknown', 'proof_usable': False, 'reasons': []}
    try:
        bundle = load_json(bundle_path)
        result_type = str(bundle.get('result_type', 'unknown'))
        record['result_type'] = result_type
        for key in ['schema_version', 'bundle_id', 'benchmark_result_path', 'telemetry_log_path', 'model_manifest_path', 'expected_support_decision']:
            if key not in bundle or bundle[key] in (None, ''):
                record['reasons'].append(f'missing_bundle_field:{key}')
        if result_type not in {'asr', 'mt'}:
            record['reasons'].append('invalid_bundle_result_type')
            return record
        result_path = resolve_evidence_path(bundle_path, str(bundle.get('benchmark_result_path', '')))
        telemetry_path = resolve_evidence_path(bundle_path, str(bundle.get('telemetry_log_path', '')))
        manifest_path = resolve_evidence_path(bundle_path, str(bundle.get('model_manifest_path', '')))
        missing_paths = [str(p) for p in [result_path, telemetry_path, manifest_path] if not p.is_file()]
        if missing_paths:
            record['reasons'].append('missing_referenced_paths:' + ','.join(missing_paths))
            return record
        result = load_json(result_path)
        manifest = load_json(manifest_path)
        record.update({
            'valid_bundle_shape': True,
            'goal_id': result.get('goal_id'),
            'support_decision': result.get('support_decision'),
            'model_id': result.get('model_id'),
            'runtime_id': result.get('runtime_id'),
            'device_model': result.get('device_model'),
            'os_version': result.get('os_version'),
            'device_tier': result.get('device_tier'),
            'language': result.get('language'),
            'language_pair': result.get('language_pair'),
        })
        if result.get('support_decision') != bundle.get('expected_support_decision'):
            record['reasons'].append('support_decision_mismatch')
        if result.get('model_id') != manifest.get('model_id'):
            record['reasons'].append('manifest_model_id_mismatch')
        if result.get('runtime_id') not in manifest.get('runtime_ids', []):
            record['reasons'].append('manifest_runtime_missing')
        evidence_paths = set(result.get('evidence_paths', []))
        for raw_key, resolved_path in [('telemetry_log_path', telemetry_path), ('model_manifest_path', manifest_path)]:
            raw_path = str(bundle.get(raw_key, ''))
            if not ({raw_path, str(resolved_path)} & evidence_paths):
                record['reasons'].append(f'evidence_paths_missing:{raw_key}')
        record['reasons'].extend(classify_result(result, result_type))
        record['proof_usable'] = not record['reasons']
        return record
    except Exception as exc:  # noqa: BLE001 - report must capture malformed evidence, not crash silently.
        record['reasons'].append(f'parse_error:{exc}')
        return record


def summarize(records: list[dict[str, Any]], result_type: str) -> dict[str, Any]:
    typed = [r for r in records if r.get('result_type') == result_type]
    usable = [r for r in typed if r.get('proof_usable')]
    supported = [r for r in typed if r.get('support_decision') == 'supported']
    unsupported = [r for r in typed if r.get('support_decision') == 'unsupported']
    return {
        'bundle_count': len(typed),
        'proof_usable_rows': len(usable),
        'supported_rows': len(supported),
        'unsupported_rows': len(unsupported),
        'blocked_rows': len(typed) - len(usable),
    }


def build_report(
    results_root: Path,
    matrix_path: Path = Path('configs/device-lab-matrix.json'),
    asr_candidates_path: Path = Path('configs/asr-candidates.json'),
    mt_candidates_path: Path = Path('configs/mt-candidates.json'),
) -> dict[str, Any]:
    bundles = discover_bundles(results_root)
    records = [bundle_record(path) for path in bundles]
    asr = summarize(records, 'asr')
    mt = summarize(records, 'mt')
    matrix_tbd = device_matrix_has_tbd(matrix_path)
    blockers: list[str] = []
    if matrix_tbd:
        blockers.append('device_matrix_has_tbd_sku_or_os')
    if not bundles:
        blockers.append('no_real_device_lab_bundles_found')
    if asr['proof_usable_rows'] == 0:
        blockers.append('g008_has_no_proof_usable_asr_rows')
    if mt['proof_usable_rows'] == 0:
        blockers.append('g010_has_no_proof_usable_mt_rows')
    if any(not r.get('proof_usable') for r in records):
        blockers.append('one_or_more_bundles_not_proof_usable')
    return {
        'schema_version': 1,
        'results_root': str(results_root),
        'aggregate_proof_coverage': 'BLOCKED' if blockers else 'READY_FOR_GOAL_REVIEW',
        'g008_asr_proof_coverage': 'BLOCKED' if matrix_tbd or asr['proof_usable_rows'] == 0 else 'READY_FOR_GOAL_REVIEW',
        'g010_mt_proof_coverage': 'BLOCKED' if matrix_tbd or mt['proof_usable_rows'] == 0 else 'READY_FOR_GOAL_REVIEW',
        'blockers': blockers,
        'matrix_path': str(matrix_path),
        'asr_candidates_path': str(asr_candidates_path),
        'mt_candidates_path': str(mt_candidates_path),
        'expected_scope': expected_scope(asr_candidates_path, mt_candidates_path),
        'bundle_count': len(bundles),
        'asr': asr,
        'mt': mt,
        'records': records,
        'non_evidence_notice': 'This proof coverage report audits evidence completeness only; it does not create support claims or close G008/G010.',
    }


def render_markdown(report: dict[str, Any]) -> str:
    lines: list[str] = [
        '# ASR/MT Proof Coverage Gate',
        '',
        'Generated conservative coverage status for retrying G008 and G010 from real device-lab bundles.',
        '',
        f'**Aggregate proof coverage:** {report["aggregate_proof_coverage"]}',
        f'**G008 ASR proof coverage:** {report["g008_asr_proof_coverage"]}',
        f'**G010 MT proof coverage:** {report["g010_mt_proof_coverage"]}',
        f'**Real bundle count:** {report["bundle_count"]}',
        '',
        report['non_evidence_notice'],
        '',
        '## Expected scope from configs',
        '',
    ]
    for key, value in report['expected_scope'].items():
        lines.append(f'- {key}: {value}')
    lines.extend(['', '## Current blockers', ''])
    if report['blockers']:
        for blocker in report['blockers']:
            lines.append(f'- `{blocker}`')
    else:
        lines.append('- None detected by this generated coverage gate; human release review is still required.')
    lines.extend([
        '',
        '## Coverage summary',
        '',
        '| Goal | Bundles | Proof-usable rows | Supported rows | Unsupported rows | Blocked rows |',
        '| --- | ---: | ---: | ---: | ---: | ---: |',
        f'| G008 ASR | {report["asr"]["bundle_count"]} | {report["asr"]["proof_usable_rows"]} | {report["asr"]["supported_rows"]} | {report["asr"]["unsupported_rows"]} | {report["asr"]["blocked_rows"]} |',
        f'| G010 MT | {report["mt"]["bundle_count"]} | {report["mt"]["proof_usable_rows"]} | {report["mt"]["supported_rows"]} | {report["mt"]["unsupported_rows"]} | {report["mt"]["blocked_rows"]} |',
        '',
        '## Bundle diagnostics',
        '',
    ])
    if not report['records']:
        lines.append('No real device-lab bundles found under the results root.')
    else:
        lines.extend(['| Bundle | Type | Decision | Proof usable | Reasons |', '| --- | --- | --- | --- | --- |'])
        for rec in report['records']:
            reasons = ', '.join(rec.get('reasons') or ['none'])
            lines.append(f'| {rec.get("bundle_path")} | {rec.get("result_type")} | {rec.get("support_decision", "unknown")} | {str(rec.get("proof_usable")).lower()} | {reasons} |')
    lines.extend([
        '',
        '## Required command sequence',
        '',
        '```sh',
        'python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/REPLACE_DATE',
        'python3 scripts/generate_proof_coverage.py --results-root benchmarks/device-lab/results/REPLACE_DATE',
        'python3 scripts/validate_proof_coverage.py',
        'python3 scripts/check_release_gate.py',
        '```',
        '',
        'G008/G010 must not be re-checkpointed as complete while this report is `BLOCKED`.',
        '',
    ])
    return '\n'.join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--results-root', default=str(DEFAULT_RESULTS_ROOT))
    ap.add_argument('--device-matrix', default='configs/device-lab-matrix.json')
    ap.add_argument('--asr-candidates', default='configs/asr-candidates.json')
    ap.add_argument('--mt-candidates', default='configs/mt-candidates.json')
    ap.add_argument('--output-md', default=str(DEFAULT_MD))
    ap.add_argument('--output-json', default=str(DEFAULT_JSON))
    args = ap.parse_args()

    report = build_report(
        Path(args.results_root),
        matrix_path=Path(args.device_matrix),
        asr_candidates_path=Path(args.asr_candidates),
        mt_candidates_path=Path(args.mt_candidates),
    )
    md = Path(args.output_md)
    js = Path(args.output_json)
    md.parent.mkdir(parents=True, exist_ok=True)
    js.parent.mkdir(parents=True, exist_ok=True)
    md.write_text(render_markdown(report) + '\n')
    js.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(f'wrote: {md}')
    print(f'wrote: {js}')
    print(f'aggregate_proof_coverage: {report["aggregate_proof_coverage"]}')
    print(f'g008_asr_proof_coverage: {report["g008_asr_proof_coverage"]}')
    print(f'g010_mt_proof_coverage: {report["g010_mt_proof_coverage"]}')
    print(f'bundle_count: {report["bundle_count"]}')
    print(f'asr_proof_usable_rows: {report["asr"]["proof_usable_rows"]}')
    print(f'mt_proof_usable_rows: {report["mt"]["proof_usable_rows"]}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
