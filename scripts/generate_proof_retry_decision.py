#!/usr/bin/env python3
"""Generate a conservative G008/G010 proof retry decision gate.

This gate joins Ultragoal status, device-lab readiness, intake, proof coverage,
and support-matrix state. It is deliberately not evidence: it only says whether
failed proof goals are safe to retry/review/complete from current artifacts.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

GOALS_PATH = Path('.omx/ultragoal/goals.json')
READINESS_PATH = Path('docs/benchmarks/device-lab-readiness.generated.json')
INTAKE_PATH = Path('docs/benchmarks/device-lab-intake.generated.json')
PROOF_COVERAGE_PATH = Path('docs/benchmarks/proof-coverage.generated.json')
SUPPORT_MATRIX_PATH = Path('docs/benchmarks/support-matrix.generated.md')
DEFAULT_JSON = Path('docs/benchmarks/proof-retry-decision.generated.json')
DEFAULT_MD = Path('docs/benchmarks/proof-retry-decision.generated.md')

TARGETS = {
    'G008': {
        'goal_prefix': 'G008',
        'label': 'G008 offline ASR proof',
        'proof_status_key': 'g008_asr_proof_coverage',
        'proof_rows_path': ('asr', 'proof_usable_rows'),
        'missing_rows_blocker': 'g008_has_no_proof_usable_asr_rows',
        'domain_blockers': ['g008_real_asr_device_benchmark_proof_missing'],
    },
    'G010': {
        'goal_prefix': 'G010',
        'label': 'G010 offline MT/runtime/legal proof',
        'proof_status_key': 'g010_mt_proof_coverage',
        'proof_rows_path': ('mt', 'proof_usable_rows'),
        'missing_rows_blocker': 'g010_has_no_proof_usable_mt_rows',
        'domain_blockers': [
            'g010_real_mt_runtime_proof_missing',
            'g010_legal_review_proof_missing',
        ],
    },
}


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def parse_support_matrix(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {
            'path': str(path),
            'status': 'missing',
            'evidence_files_scanned': None,
            'supported_rows': None,
            'conservative_rule_present': False,
        }
    text = path.read_text()
    evidence_match = re.search(r'- Evidence files scanned:\s*(\d+)', text)
    supported_match = re.search(r'- Supported rows:\s*(\d+)', text)
    conservative = 'Conservative rule' in text
    return {
        'path': str(path),
        'status': 'present_conservative' if conservative else 'present_without_conservative_rule',
        'evidence_files_scanned': int(evidence_match.group(1)) if evidence_match else None,
        'supported_rows': int(supported_match.group(1)) if supported_match else None,
        'conservative_rule_present': conservative,
    }


def find_goal(goals: list[dict[str, Any]], prefix: str) -> dict[str, Any] | None:
    for goal in goals:
        if str(goal.get('id', '')).startswith(prefix):
            return goal
    return None


def get_nested(data: dict[str, Any], path: tuple[str, ...]) -> Any:
    value: Any = data
    for key in path:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


def add_unique(items: list[str], values: list[str]) -> None:
    for value in values:
        if value and value not in items:
            items.append(value)


def build_goal_decision(
    goal_key: str,
    goal: dict[str, Any] | None,
    readiness: dict[str, Any],
    intake: dict[str, Any],
    proof: dict[str, Any],
    support: dict[str, Any],
) -> dict[str, Any]:
    target = TARGETS[goal_key]
    blockers: list[str] = []
    evidence_gaps: list[str] = []

    if goal is None:
        blockers.append(f'{goal_key.lower()}_goal_missing')
        goal_status = 'missing'
    else:
        goal_status = str(goal.get('status'))
        if goal_status == 'failed':
            blockers.append(f'{goal_key.lower()}_goal_status_failed')
        elif goal_status != 'complete':
            blockers.append(f'{goal_key.lower()}_goal_status_{goal_status}')

    if readiness.get('aggregate_readiness') != 'READY':
        blockers.append('device_lab_readiness_blocked')
    if readiness.get('lab_execution_readiness') != 'READY_TO_COLLECT_EVIDENCE':
        blockers.append('device_lab_execution_not_ready')
    if readiness.get('release_claim_readiness') != 'READY_FOR_RELEASE_REVIEW':
        blockers.append('release_claim_readiness_blocked')
    if readiness.get('device_lab_preflight', {}).get('status') == 'BLOCKED':
        blockers.append('device_lab_preflight_blocked')
    add_unique(blockers, list(readiness.get('lab_blockers', [])))
    add_unique(blockers, list(readiness.get('evidence_blockers', [])))

    if intake.get('intake_status') != 'READY_FOR_PROOF_REVIEW':
        blockers.append('device_lab_intake_blocked')
    if intake.get('real_bundles_found') in (None, 0):
        blockers.append('no_real_device_lab_bundles_found')
    if intake.get('supported_rows') in (None, 0):
        blockers.append('zero_supported_rows')
    if intake.get('allow_support_claims') is not True:
        blockers.append('support_claims_not_allowed_by_intake')
    add_unique(blockers, list(intake.get('blockers', [])))

    proof_status = proof.get(str(target['proof_status_key']))
    proof_rows = get_nested(proof, target['proof_rows_path'])
    if proof_status != 'READY_FOR_GOAL_REVIEW':
        blockers.append(f'{goal_key.lower()}_proof_coverage_blocked')
    if proof_rows in (None, 0):
        blockers.append(str(target['missing_rows_blocker']))
    if proof.get('bundle_count') in (None, 0):
        blockers.append('no_real_device_lab_bundles_found')
    add_unique(blockers, list(proof.get('blockers', [])))

    if support.get('supported_rows') in (None, 0):
        blockers.append('zero_supported_rows')
    if not support.get('conservative_rule_present'):
        blockers.append('support_matrix_conservative_rule_missing')

    add_unique(evidence_gaps, list(target['domain_blockers']))
    if goal_status != 'complete':
        add_unique(evidence_gaps, ['ultragoal_proof_story_not_complete'])
    if proof_rows in (None, 0):
        add_unique(evidence_gaps, ['zero_proof_usable_rows'])
    if intake.get('real_bundles_found') in (None, 0):
        add_unique(evidence_gaps, ['no_real_device_lab_bundles'])

    blockers = list(dict.fromkeys(blockers))
    status = 'BLOCKED' if blockers else 'READY_FOR_RETRY_REVIEW'
    return {
        'goal_key': goal_key,
        'goal_id': goal.get('id') if goal else None,
        'label': target['label'],
        'goal_status': goal_status,
        'decision_status': status,
        'can_retry_goal': status == 'READY_FOR_RETRY_REVIEW',
        'can_mark_complete': status == 'READY_FOR_RETRY_REVIEW' and goal_status == 'complete',
        'proof_coverage_status': proof_status,
        'proof_usable_rows': proof_rows,
        'blockers': blockers,
        'evidence_gaps': evidence_gaps,
        'goal_evidence': (goal or {}).get('failureReason') or (goal or {}).get('evidence'),
    }


def build_report(
    goals_path: Path = GOALS_PATH,
    readiness_path: Path = READINESS_PATH,
    intake_path: Path = INTAKE_PATH,
    proof_path: Path = PROOF_COVERAGE_PATH,
    support_path: Path = SUPPORT_MATRIX_PATH,
) -> dict[str, Any]:
    plan = load_json(goals_path)
    readiness = load_json(readiness_path)
    intake = load_json(intake_path)
    proof = load_json(proof_path)
    support = parse_support_matrix(support_path)
    goals = plan.get('goals', [])
    if not isinstance(goals, list):
        raise SystemExit(f'{goals_path}: goals must be a list')

    target_decisions = []
    for key in ['G008', 'G010']:
        target_decisions.append(
            build_goal_decision(
                key,
                find_goal(goals, key),
                readiness,
                intake,
                proof,
                support,
            )
        )

    non_superseded = [g for g in goals if g.get('steeringStatus') != 'superseded']
    open_failed_or_pending = [
        str(g.get('id'))
        for g in non_superseded
        if g.get('status') in {'failed', 'pending', 'in_progress', 'review_blocked', 'needs_user_decision'}
    ]
    all_ready = all(d['decision_status'] == 'READY_FOR_RETRY_REVIEW' for d in target_decisions)
    aggregate_status = 'READY_FOR_RETRY_REVIEW' if all_ready and not open_failed_or_pending else 'BLOCKED'
    aggregate_blockers: list[str] = []
    if not all_ready:
        aggregate_blockers.append('one_or_more_proof_goals_not_ready_for_retry')
    if open_failed_or_pending:
        aggregate_blockers.append('open_failed_or_pending_ultragoal_stories')
    for decision in target_decisions:
        add_unique(aggregate_blockers, decision['blockers'])

    return {
        'schema_version': 1,
        'aggregate_retry_decision': aggregate_status,
        'can_retry_any_goal': any(d['can_retry_goal'] for d in target_decisions),
        'can_mark_any_goal_complete': any(d['can_mark_complete'] for d in target_decisions),
        'source_paths': {
            'goals': str(goals_path),
            'device_lab_readiness': str(readiness_path),
            'device_lab_intake': str(intake_path),
            'proof_coverage': str(proof_path),
            'support_matrix': str(support_path),
        },
        'summary_inputs': {
            'device_lab_readiness': readiness.get('aggregate_readiness'),
            'lab_execution_readiness': readiness.get('lab_execution_readiness'),
            'device_lab_preflight': readiness.get('device_lab_preflight', {}).get('status'),
            'device_lab_intake': intake.get('intake_status'),
            'real_bundles_found': intake.get('real_bundles_found'),
            'proof_coverage': proof.get('aggregate_proof_coverage'),
            'support_matrix_supported_rows': support.get('supported_rows'),
            'support_claims_allowed_by_intake': intake.get('allow_support_claims'),
            'open_failed_or_pending_ultragoal_stories': open_failed_or_pending,
        },
        'aggregate_blockers': aggregate_blockers,
        'target_decisions': target_decisions,
        'non_evidence_notice': 'This retry decision gate is not ASR/MT benchmark proof, legal approval, or a support claim; it only blocks or permits a later human retry/review decision.',
        'required_next_actions': [
            'Replace TBD device matrix values with exact iOS/Android SKUs and OS versions.',
            'Pass scripts/validate_device_lab_preflight.py on the lab machine with connected target devices and real model artifacts.',
            'Collect real G008 ASR and G010 MT evidence bundles under benchmarks/device-lab/results/.',
            'Regenerate intake, support matrix, readiness, proof coverage, and this retry decision gate from real bundles.',
            'Retry/complete G008 and G010 only after this decision gate reports READY_FOR_RETRY_REVIEW and reviewers approve the evidence.',
        ],
    }


def render_markdown(report: dict[str, Any]) -> str:
    inputs = report['summary_inputs']
    lines = [
        '# G008/G010 Proof Retry Decision Gate',
        '',
        'Generated conservative decision status for whether failed proof goals can be retried or marked complete.',
        '',
        f'**Aggregate retry decision:** {report["aggregate_retry_decision"]}',
        f'**Can retry any goal:** {str(report["can_retry_any_goal"]).lower()}',
        f'**Can mark any goal complete:** {str(report["can_mark_any_goal_complete"]).lower()}',
        '',
        report['non_evidence_notice'],
        '',
        '## Input summary',
        '',
    ]
    for key, value in inputs.items():
        if isinstance(value, list):
            lines.append(f'- {key}: {", ".join(value) if value else "none"}')
        else:
            lines.append(f'- {key}: {value}')

    lines.extend(['', '## Per-goal decisions', ''])
    lines.extend([
        '| Goal | Ultragoal status | Decision | Can retry | Can mark complete | Proof coverage | Proof-usable rows |',
        '| --- | --- | --- | --- | --- | --- | ---: |',
    ])
    for decision in report['target_decisions']:
        lines.append(
            f'| {decision["goal_id"] or decision["goal_key"]} | {decision["goal_status"]} | '
            f'{decision["decision_status"]} | {str(decision["can_retry_goal"]).lower()} | '
            f'{str(decision["can_mark_complete"]).lower()} | {decision["proof_coverage_status"]} | '
            f'{decision["proof_usable_rows"]} |'
        )

    lines.extend(['', '## Aggregate blockers', ''])
    if report['aggregate_blockers']:
        for blocker in report['aggregate_blockers']:
            lines.append(f'- `{blocker}`')
    else:
        lines.append('- None detected by this generated gate; human proof/release review is still required.')

    lines.extend(['', '## Per-goal blockers and evidence gaps', ''])
    for decision in report['target_decisions']:
        lines.append(f'### {decision["goal_id"] or decision["goal_key"]}')
        lines.append('')
        lines.append('Blockers:')
        for blocker in decision['blockers']:
            lines.append(f'- `{blocker}`')
        lines.append('')
        lines.append('Evidence gaps:')
        for gap in decision['evidence_gaps']:
            lines.append(f'- `{gap}`')
        if decision.get('goal_evidence'):
            lines.append('')
            lines.append(f'Ledger evidence/failure reason: {decision["goal_evidence"]}')
        lines.append('')

    lines.extend([
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
        'python3 scripts/validate_device_lab_preflight.py',
        'python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/REPLACE_DATE',
        'python3 scripts/validate_device_lab_intake_report.py',
        'python3 scripts/validate_proof_coverage.py',
        'python3 scripts/validate_proof_retry_decision.py',
        'python3 scripts/check_release_gate.py',
        '```',
        '',
        'Do not retry or complete G008/G010 from this repository state while the aggregate retry decision is `BLOCKED`.',
        '',
    ])
    return '\n'.join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--goals', default=str(GOALS_PATH))
    ap.add_argument('--readiness-json', default=str(READINESS_PATH))
    ap.add_argument('--intake-json', default=str(INTAKE_PATH))
    ap.add_argument('--proof-coverage-json', default=str(PROOF_COVERAGE_PATH))
    ap.add_argument('--support-matrix', default=str(SUPPORT_MATRIX_PATH))
    ap.add_argument('--output-json', default=str(DEFAULT_JSON))
    ap.add_argument('--output-md', default=str(DEFAULT_MD))
    args = ap.parse_args()

    report = build_report(
        goals_path=Path(args.goals),
        readiness_path=Path(args.readiness_json),
        intake_path=Path(args.intake_json),
        proof_path=Path(args.proof_coverage_json),
        support_path=Path(args.support_matrix),
    )
    output_json = Path(args.output_json)
    output_md = Path(args.output_md)
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    output_md.write_text(render_markdown(report) + '\n')
    print(f'wrote: {output_json}')
    print(f'wrote: {output_md}')
    print(f'aggregate_retry_decision: {report["aggregate_retry_decision"]}')
    for decision in report['target_decisions']:
        print(
            f'{decision["goal_key"].lower()}_decision_status: {decision["decision_status"]}; '
            f'can_retry={str(decision["can_retry_goal"]).lower()}; '
            f'can_mark_complete={str(decision["can_mark_complete"]).lower()}'
        )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
