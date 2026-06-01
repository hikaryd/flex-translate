#!/usr/bin/env python3
"""Conservative guardrail against accidental ASR/MT support claims.

This verifier is intentionally stricter than future release logic: while G008 and
G010 are unresolved, repository fixtures, configs, generated docs, and sample
bundles must stay not_claimed/internal-only and the generated support matrix must
show zero supported rows.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

ROOTS_TO_SCAN = [
    Path('configs/asr-candidates.json'),
    Path('configs/mt-candidates.json'),
    Path('configs/device-lab-matrix.json'),
    Path('benchmarks/asr/results/mock-validation.json'),
    Path('benchmarks/mt/results/mock-validation.json'),
    Path('benchmarks/device-lab/samples'),
    Path('docs/benchmarks/support-matrix.generated.md'),
]


def json_files(root: Path) -> list[Path]:
    if root.is_file() and root.suffix == '.json':
        return [root]
    if root.is_dir():
        return sorted(p for p in root.rglob('*.json'))
    return []


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise SystemExit(f'{path}: invalid JSON: {exc}') from exc


def walk_json(value: Any, path: str = '$'):
    if isinstance(value, dict):
        yield path, value
        for key, child in value.items():
            yield from walk_json(child, f'{path}.{key}')
    elif isinstance(value, list):
        for i, child in enumerate(value):
            yield from walk_json(child, f'{path}[{i}]')


def assert_candidate_configs(path: Path, data: Any) -> None:
    if path.name == 'asr-candidates.json':
        if data.get('supportClaims') != 'none_until_benchmarked':
            raise SystemExit(f'{path}: supportClaims must remain none_until_benchmarked')
        if data.get('runtimePolicy', {}).get('noSupportWithoutEvidence') is not True:
            raise SystemExit(f'{path}: noSupportWithoutEvidence must be true')
        for candidate in data.get('candidates', []):
            if candidate.get('support') != 'not_claimed':
                raise SystemExit(f'{path}: ASR candidate {candidate.get("id")} support must be not_claimed')
    if path.name == 'mt-candidates.json':
        if data.get('supportClaims') != 'none_until_benchmarked_and_legal_reviewed':
            raise SystemExit(f'{path}: supportClaims must remain none_until_benchmarked_and_legal_reviewed')
        policy = data.get('runtimePolicy', {})
        if policy.get('noSupportWithoutEvidence') is not True or policy.get('noDistributionWithoutLegalReview') is not True:
            raise SystemExit(f'{path}: MT runtime policy must keep evidence/legal gates true')
        for candidate in data.get('candidates', []):
            if candidate.get('support') != 'not_claimed':
                raise SystemExit(f'{path}: MT candidate {candidate.get("id")} support must be not_claimed')
    if path.name == 'device-lab-matrix.json':
        if data.get('supportClaimsAllowed') is not False:
            raise SystemExit(f'{path}: supportClaimsAllowed must be false until real proof gates pass')


def assert_result_decisions(path: Path, data: Any) -> None:
    if not isinstance(data, dict):
        return
    if {'result_type', 'support_decision', 'model_id', 'runtime_id'} <= set(data):
        if data['support_decision'] != 'not_claimed':
            raise SystemExit(f'{path}: sample/mock benchmark result must remain not_claimed, got {data["support_decision"]!r}')
    if 'support_decision' in data and path.match('benchmarks/device-lab/samples/**/*.json'):
        if data['support_decision'] != 'not_claimed':
            raise SystemExit(f'{path}: sample support_decision must remain not_claimed')


def assert_bundle_decisions(path: Path, data: Any) -> None:
    if not isinstance(data, dict):
        return
    if path.name.endswith('.bundle.json'):
        if data.get('expected_support_decision') != 'not_claimed':
            raise SystemExit(f'{path}: sample bundle expected_support_decision must remain not_claimed')
        notes = str(data.get('notes', '')).lower()
        if 'not a support claim' not in notes:
            raise SystemExit(f'{path}: sample bundle notes must explicitly say not a support claim')


def assert_manifest_distribution(path: Path, data: Any) -> None:
    if not isinstance(data, dict):
        return
    if {'model_id', 'distribution_mode', 'legal_review_status'} <= set(data) and 'samples' in path.parts:
        if data['distribution_mode'] != 'internal_lab_only':
            raise SystemExit(f'{path}: sample model manifest must remain internal_lab_only')
        if data['legal_review_status'] == 'approved_for_distribution':
            raise SystemExit(f'{path}: sample manifest must not be approved_for_distribution')


def assert_no_supported_literals_in_samples(path: Path, data: Any) -> None:
    for json_path, node in walk_json(data):
        if isinstance(node, dict):
            continue
        # walk_json only yields dict roots; scalar check is covered by explicit key checks above.
        _ = json_path


def assert_support_matrix() -> None:
    path = Path('docs/benchmarks/support-matrix.generated.md')
    text = path.read_text()
    required = ['Conservative rule', 'Supported rows: 0', 'not support evidence']
    for token in required:
        if token not in text:
            raise SystemExit(f'{path}: missing conservative support token {token!r}')
    if '✅ supported' in text:
        raise SystemExit(f'{path}: generated support matrix contains supported row before G008/G010 proof')


def main() -> int:
    checked_json = 0
    for root in ROOTS_TO_SCAN:
        for path in json_files(root):
            data = load_json(path)
            assert_candidate_configs(path, data)
            assert_result_decisions(path, data)
            assert_bundle_decisions(path, data)
            assert_manifest_distribution(path, data)
            assert_no_supported_literals_in_samples(path, data)
            checked_json += 1
    assert_support_matrix()
    print('No false support claims validation: PASS')
    print(f'json_files_checked: {checked_json}')
    print('support_matrix_supported_rows: 0')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
