#!/usr/bin/env python3
"""Generate a conservative, machine-readable readiness report for device-lab proof.

The report is intentionally not evidence. It summarizes whether the project is
ready to execute G008/G010 lab runs and whether any support/release claim can be
made from current artifacts.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path
from typing import Any

DEFAULT_MD = Path('docs/benchmarks/device-lab-readiness.generated.md')
DEFAULT_JSON = Path('docs/benchmarks/device-lab-readiness.generated.json')
DEFAULT_RESULTS_ROOT = Path('benchmarks/device-lab/results')
SUPPORT_MATRIX = Path('docs/benchmarks/support-matrix.generated.md')
INTAKE_REPORT = Path('docs/benchmarks/device-lab-intake.generated.json')
GOALS_FILE = Path('.omx/ultragoal/goals.json')
MATRIX_VALIDATOR = Path('scripts/validate_device_lab_matrix.py')
PREFLIGHT_VALIDATOR = Path('scripts/validate_device_lab_preflight.py')


def load_json(path: str | Path) -> dict[str, Any]:
    data = json.loads(Path(path).read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def tier_platform_entries(matrix: dict[str, Any]) -> list[dict[str, str]]:
    entries: list[dict[str, str]] = []
    for tier in matrix.get('deviceTiers', []):
        tier_name = str(tier.get('tier', 'unknown'))
        for platform in ['android', 'ios']:
            device = tier.get(platform, {}) if isinstance(tier.get(platform), dict) else {}
            entries.append({
                'tier': tier_name,
                'platform': platform,
                'sku': str(device.get('sku', 'TBD')),
                'os_version': str(device.get('osVersion', 'TBD')),
            })
    return entries


def tbd_device_fields(matrix: dict[str, Any]) -> list[str]:
    missing: list[str] = []
    for row in tier_platform_entries(matrix):
        prefix = f'{row["tier"]}/{row["platform"]}'
        if row['sku'] == 'TBD' or not row['sku'].strip():
            missing.append(f'{prefix}.sku')
        if row['os_version'] == 'TBD' or not row['os_version'].strip():
            missing.append(f'{prefix}.osVersion')
    return missing


def discover_real_bundles(root: Path) -> list[Path]:
    if root.is_file():
        return [root] if root.name.endswith('.bundle.json') and not root.name.endswith('.template.bundle.json') else []
    if not root.exists():
        return []
    return sorted(path for path in root.rglob('*.bundle.json') if path.is_file() and '.template.' not in path.name)


def parse_support_matrix(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {'status': 'missing', 'evidence_files_scanned': None, 'supported_rows': None}
    text = path.read_text()
    evidence_match = re.search(r'- Evidence files scanned:\s*(\d+)', text)
    supported_match = re.search(r'- Supported rows:\s*(\d+)', text)
    conservative = 'Conservative rule' in text
    return {
        'status': 'present_conservative' if conservative else 'present_without_conservative_rule',
        'evidence_files_scanned': int(evidence_match.group(1)) if evidence_match else None,
        'supported_rows': int(supported_match.group(1)) if supported_match else None,
    }



def parse_intake_report(path: Path = INTAKE_REPORT) -> dict[str, Any]:
    if not path.is_file():
        return {'status': 'missing', 'intake_status': 'missing', 'real_bundles_found': None, 'supported_rows': None, 'blockers': ['device_lab_intake_report_missing']}
    try:
        report = load_json(path)
    except Exception as exc:  # noqa: BLE001
        return {'status': 'invalid', 'intake_status': 'invalid', 'real_bundles_found': None, 'supported_rows': None, 'blockers': [f'device_lab_intake_report_invalid:{exc}']}
    return {
        'status': 'present',
        'intake_status': report.get('intake_status'),
        'real_bundles_found': report.get('real_bundles_found'),
        'sample_bundles_validated': report.get('sample_bundles_validated'),
        'supported_rows': report.get('supported_rows'),
        'allow_support_claims': report.get('allow_support_claims'),
        'blockers': report.get('blockers', []),
        'path': str(path),
        'non_evidence_notice': report.get('non_evidence_notice'),
    }

def open_ultragoal_blockers(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return [{'id': 'missing-goals-json', 'status': 'missing', 'title': 'Missing .omx/ultragoal/goals.json'}]
    goals = load_json(path).get('goals', [])
    blockers: list[dict[str, str]] = []
    for goal in goals:
        if goal.get('steeringStatus') == 'superseded':
            continue
        status = str(goal.get('status'))
        if status in {'failed', 'pending', 'in_progress', 'review_blocked', 'needs_user_decision'}:
            blockers.append({
                'id': str(goal.get('id')),
                'status': status,
                'title': str(goal.get('title', '')),
            })
    return blockers


def candidate_counts() -> dict[str, int]:
    asr = load_json('configs/asr-candidates.json')
    mt = load_json('configs/mt-candidates.json')
    mt_runs = 0
    for candidate in mt.get('candidates', []):
        mt_runs += len(candidate.get('targetTiers', [])) * len(candidate.get('languagePairs', [])) * len(candidate.get('runtimeCandidates', [])) * 2
    asr_runs = 0
    for candidate in asr.get('candidates', []):
        asr_runs += len(candidate.get('tiers', [])) * 2
    return {
        'asr_candidate_count': len(asr.get('candidates', [])),
        'mt_candidate_count': len(mt.get('candidates', [])),
        'planned_asr_runs_minimum': asr_runs,
        'planned_mt_runtime_pair_runs_minimum': mt_runs,
    }


def device_matrix_validation() -> dict[str, Any]:
    if not MATRIX_VALIDATOR.is_file():
        return {
            'validation_status': 'FAIL',
            'execution_readiness': 'BLOCKED_PLANNING_ONLY',
            'errors': [f'missing {MATRIX_VALIDATOR}'],
            'missing_device_fields': [],
        }
    proc = subprocess.run(
        ['python3', str(MATRIX_VALIDATOR), '--allow-tbd', '--json'],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    try:
        report = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {
            'validation_status': 'FAIL',
            'execution_readiness': 'BLOCKED_PLANNING_ONLY',
            'errors': [f'matrix validator returned non-JSON output: {proc.stdout.strip()}'],
            'missing_device_fields': [],
        }
    if proc.returncode != 0:
        report.setdefault('errors', []).append(f'matrix validator exited {proc.returncode}')
    return report



def device_lab_preflight() -> dict[str, Any]:
    if not PREFLIGHT_VALIDATOR.is_file():
        return {
            'status': 'BLOCKED',
            'blockers': [{'code': 'preflight_validator_missing', 'detail': f'missing {PREFLIGHT_VALIDATOR}', 'action': 'Restore device-lab preflight validator.'}],
            'preflight_authority': 'execution_readiness_only_not_support_evidence',
        }
    proc = subprocess.run(
        ['python3', str(PREFLIGHT_VALIDATOR), '--allow-missing', '--json'],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    try:
        report = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return {
            'status': 'BLOCKED',
            'blockers': [{'code': 'preflight_non_json', 'detail': proc.stdout.strip(), 'action': 'Fix preflight validator output.'}],
            'preflight_authority': 'execution_readiness_only_not_support_evidence',
        }
    if proc.returncode != 0:
        report.setdefault('blockers', []).append({'code': 'preflight_validator_failed', 'detail': f'exit {proc.returncode}', 'action': 'Fix preflight validator before lab execution.'})
        report['status'] = 'BLOCKED'
    return report

def build_report(results_root: Path) -> dict[str, Any]:
    matrix = load_json('configs/device-lab-matrix.json')
    matrix_report = device_matrix_validation()
    missing_device_fields = tbd_device_fields(matrix)
    real_bundles = discover_real_bundles(results_root)
    support = parse_support_matrix(SUPPORT_MATRIX)
    intake = parse_intake_report()
    blockers = open_ultragoal_blockers(GOALS_FILE)
    preflight = device_lab_preflight()
    goal_blocker_ids = [b['id'] for b in blockers]

    lab_blockers: list[str] = []
    if matrix_report.get('validation_status') == 'FAIL':
        lab_blockers.append('device_matrix_validation_failed')
    if missing_device_fields:
        lab_blockers.append('device_matrix_has_tbd_sku_or_os')
    if preflight.get('status') == 'BLOCKED':
        lab_blockers.append('device_lab_preflight_blocked')

    evidence_blockers: list[str] = []
    if not real_bundles:
        evidence_blockers.append('no_real_device_lab_bundles_found')
    if intake.get('intake_status') != 'READY_FOR_PROOF_REVIEW':
        evidence_blockers.append('device_lab_intake_report_blocked_or_missing')
    if support.get('supported_rows') in (None, 0):
        evidence_blockers.append('zero_supported_rows')
    if any(gid.startswith(('G008', 'G010', 'G012')) for gid in goal_blocker_ids):
        evidence_blockers.append('proof_or_final_qa_goals_open')
    if matrix.get('supportClaimsAllowed') is not True:
        evidence_blockers.append('support_claims_not_allowed_by_matrix')

    lab_execution_readiness = 'BLOCKED' if lab_blockers else 'READY_TO_COLLECT_EVIDENCE'
    release_claim_readiness = 'BLOCKED' if evidence_blockers else 'READY_FOR_RELEASE_REVIEW'
    aggregate_readiness = 'BLOCKED' if lab_blockers or evidence_blockers else 'READY'

    report: dict[str, Any] = {
        'schema_version': 1,
        'aggregate_readiness': aggregate_readiness,
        'lab_execution_readiness': lab_execution_readiness,
        'release_claim_readiness': release_claim_readiness,
        'support_claims_allowed': bool(matrix.get('supportClaimsAllowed', False)),
        'results_root': str(results_root),
        'real_bundles_found': len(real_bundles),
        'real_bundle_paths': [str(p) for p in real_bundles],
        'device_matrix_validation': matrix_report,
        'device_lab_preflight': preflight,
        'missing_device_fields': missing_device_fields,
        'lab_blockers': lab_blockers,
        'evidence_blockers': evidence_blockers,
        'open_goal_blockers': blockers,
        'support_matrix': support,
        'device_lab_intake': intake,
        'candidate_counts': candidate_counts(),
        'required_next_actions': [
            'Fill configs/device-lab-matrix.json with exact iOS/Android SKUs and OS versions.',
            'Run python3 scripts/validate_device_lab_matrix.py without --allow-tbd before executing lab runs.',
            'Generate per-run templates from docs/benchmarks/device-lab-run-plan.generated.md.',
            'Run ASR/MT benchmarks on physical target devices with real model artifacts and offline telemetry.',
            'Place completed evidence.bundle.json files under benchmarks/device-lab/results/<date>/...',
            'Run python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/<date>.',
            'Run python3 scripts/check_release_gate.py and keep it blocked until G008/G010/G012 are legitimately complete.',
        ],
        'non_evidence_notice': 'This readiness report is a gate/status document only; it creates no ASR/MT support claim.',
    }
    return report


def render_markdown(report: dict[str, Any]) -> str:
    support = report['support_matrix']
    lines: list[str] = [
        '# Device Lab Readiness Gate',
        '',
        'Generated conservative status for external G008/G010 evidence collection and release-claim safety.',
        '',
        f'**Aggregate readiness:** {report["aggregate_readiness"]}',
        f'**Lab execution readiness:** {report["lab_execution_readiness"]}',
        f'**Release claim readiness:** {report["release_claim_readiness"]}',
        f'**Support claims allowed:** {str(report["support_claims_allowed"]).lower()}',
        f'**Real bundles found:** {report["real_bundles_found"]}',
        f'**Supported rows:** {support.get("supported_rows")}',
        f'**Device matrix validation:** {report["device_matrix_validation"].get("validation_status")}',
        f'**Device-lab preflight:** {report["device_lab_preflight"].get("status")}',
        f'**Device-lab intake:** {report["device_lab_intake"].get("intake_status")}',
        '',
        report['non_evidence_notice'],
        '',
        '## Current blockers',
        '',
    ]
    if not report['lab_blockers'] and not report['evidence_blockers'] and not report['open_goal_blockers']:
        lines.append('- None detected by this generated gate; final human release review is still required.')
    for blocker in report['lab_blockers']:
        lines.append(f'- Lab blocker: `{blocker}`')
    for field in report['missing_device_fields']:
        lines.append(f'  - Missing device field: `{field}`')
    for error in report['device_matrix_validation'].get('errors', []):
        lines.append(f'  - Device matrix validation error: `{error}`')
    for blocker in report['device_lab_preflight'].get('blockers', []):
        lines.append(f'  - Preflight blocker: `{blocker.get("code")}` — {blocker.get("detail")}')
    for blocker in report['evidence_blockers']:
        lines.append(f'- Evidence/release blocker: `{blocker}`')
    for blocker in report['device_lab_intake'].get('blockers', []):
        lines.append(f'  - Intake blocker: `{blocker}`')
    for goal in report['open_goal_blockers']:
        lines.append(f'- Open Ultragoal: `{goal["id"]}` ({goal["status"]}) — {goal["title"]}')

    counts = report['candidate_counts']
    lines.extend([
        '',
        '## Planned proof scope',
        '',
        f'- ASR candidates: {counts["asr_candidate_count"]}',
        f'- MT candidates: {counts["mt_candidate_count"]}',
        f'- Planned ASR runs minimum: {counts["planned_asr_runs_minimum"]}',
        f'- Planned MT runtime/pair runs minimum: {counts["planned_mt_runtime_pair_runs_minimum"]}',
        '',
        '## Required next actions',
        '',
    ])
    for action in report['required_next_actions']:
        lines.append(f'- {action}')
    lines.extend([
        '',
        '## Commands',
        '',
        '```sh',
        'python3 scripts/generate_device_lab_run_plan.py',
        'python3 scripts/validate_device_lab_matrix.py --allow-tbd',
        'python3 scripts/validate_device_lab_matrix.py',
        'python3 scripts/validate_device_lab_preflight.py',
        'python3 scripts/create_device_lab_run_template.py --help',
        'python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/REPLACE_DATE',
        'python3 scripts/check_release_gate.py',
        '```',
        '',
        'Do not call `update_goal complete` while this report is `BLOCKED` or while G008/G010/G012 remain open.',
        '',
    ])
    return '\n'.join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--results-root', default=str(DEFAULT_RESULTS_ROOT))
    ap.add_argument('--output-md', default=str(DEFAULT_MD))
    ap.add_argument('--output-json', default=str(DEFAULT_JSON))
    args = ap.parse_args()

    report = build_report(Path(args.results_root))
    md_path = Path(args.output_md)
    json_path = Path(args.output_json)
    md_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    md_path.write_text(render_markdown(report) + '\n')
    json_path.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')

    print(f'wrote: {md_path}')
    print(f'wrote: {json_path}')
    print(f'aggregate_readiness: {report["aggregate_readiness"]}')
    print(f'lab_execution_readiness: {report["lab_execution_readiness"]}')
    print(f'release_claim_readiness: {report["release_claim_readiness"]}')
    print(f'real_bundles_found: {report["real_bundles_found"]}')
    print(f'supported_rows: {report["support_matrix"].get("supported_rows")}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
