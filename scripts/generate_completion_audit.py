#!/usr/bin/env python3
"""Generate an explicit Ultragoal completion audit.

The audit is intentionally conservative: it distinguishes completed scaffolding from
real proof, records the evidence sources that would be needed for final completion,
and refuses to call the aggregate complete while failed/pending non-superseded
Ultragoal stories remain.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

PLAN_PATH = Path('.omx/ultragoal/goals.json')
SUPPORT_MATRIX_PATH = Path('docs/benchmarks/support-matrix.generated.md')
PROOF_RETRY_DECISION_PATH = Path('docs/benchmarks/proof-retry-decision.generated.json')
DEFAULT_OUTPUT = Path('docs/qa/completion-audit.generated.md')

FINAL_REQUIREMENTS = [
    {
        'id': 'R1-no-open-ultragoal-stories',
        'title': 'No failed/pending/in-progress/review-blocked non-superseded Ultragoal stories',
        'evidence': '.omx/ultragoal/goals.json',
    },
    {
        'id': 'R2-real-asr-proof',
        'title': 'G008 real offline ASR proof complete with device/model/perf/thermal evidence',
        'goal_prefix': 'G008',
        'evidence': 'validated real device-lab ASR evidence bundle(s), not samples/templates',
    },
    {
        'id': 'R3-real-mt-legal-proof',
        'title': 'G010 real offline translation runtime and legal proof complete',
        'goal_prefix': 'G010',
        'evidence': 'validated real MT evidence bundle(s), model manifest legal approval, support matrix review',
    },
    {
        'id': 'R4-final-qa-review',
        'title': 'G012 final QA, ai-slop-cleaner/no-op, verification rerun, and independent review complete',
        'goal_prefix': 'G012',
        'evidence': 'final release gate output plus independent APPROVE/CLEAR review',
    },
    {
        'id': 'R5-no-false-support-claims',
        'title': 'No ASR/MT support claim without real evidence and explicit release approval',
        'evidence': 'docs/benchmarks/support-matrix.generated.md and scripts/validate_no_false_support_claims.py',
    },
    {
        'id': 'R6-proof-retry-decision',
        'title': 'G008/G010 proof retry decision gate is not blocking completion',
        'evidence': 'docs/benchmarks/proof-retry-decision.generated.json and .md',
    },
]


def load_plan() -> dict[str, Any]:
    if not PLAN_PATH.is_file():
        raise SystemExit(f'missing plan: {PLAN_PATH}')
    return json.loads(PLAN_PATH.read_text())


def non_superseded(goals: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [g for g in goals if g.get('steeringStatus') != 'superseded']


def find_goal(goals: list[dict[str, Any]], prefix: str) -> dict[str, Any] | None:
    for goal in goals:
        if goal.get('id', '').startswith(prefix):
            return goal
    return None


def support_matrix_status() -> tuple[str, int | None]:
    if not SUPPORT_MATRIX_PATH.is_file():
        return 'missing', None
    text = SUPPORT_MATRIX_PATH.read_text()
    rows = None
    for line in text.splitlines():
        if line.startswith('- Supported rows:'):
            try:
                rows = int(line.split(':', 1)[1].strip())
            except ValueError:
                rows = None
    if 'Conservative rule' not in text:
        return 'missing_conservative_rule', rows
    if '✅ supported' in text and rows == 0:
        return 'contradictory_supported_marker', rows
    return 'present_conservative', rows



def proof_retry_decision_status() -> tuple[str, str]:
    if not PROOF_RETRY_DECISION_PATH.is_file():
        return 'missing', f'Missing {PROOF_RETRY_DECISION_PATH}.'
    try:
        report = json.loads(PROOF_RETRY_DECISION_PATH.read_text())
    except json.JSONDecodeError as exc:
        return 'invalid', f'Invalid proof retry decision JSON: {exc}'
    status = str(report.get('aggregate_retry_decision', 'missing'))
    blockers = ', '.join(report.get('aggregate_blockers', [])) or 'none'
    if status == 'READY_FOR_RETRY_REVIEW':
        return 'needs-release-review', 'Retry decision permits evidence review; final completion still requires G008/G010/G012 to be proven.'
    return 'blocked', f'Proof retry decision is {status}; blockers: {blockers}'

def requirement_status(req: dict[str, Any], goals: list[dict[str, Any]]) -> tuple[str, str]:
    active = non_superseded(goals)
    if req['id'] == 'R1-no-open-ultragoal-stories':
        open_goals = [g for g in active if g.get('status') in {'failed', 'pending', 'in_progress', 'review_blocked', 'needs_user_decision'}]
        if open_goals:
            ids = ', '.join(g['id'] for g in open_goals)
            return 'blocked', f'Open non-superseded goals: {ids}'
        return 'proven', 'All non-superseded goals are closed.'
    if req['id'] == 'R6-proof-retry-decision':
        return proof_retry_decision_status()
    if req['id'] == 'R5-no-false-support-claims':
        matrix_status, rows = support_matrix_status()
        if matrix_status != 'present_conservative':
            return 'blocked', f'Support matrix status is {matrix_status}.'
        if rows == 0:
            return 'proven-for-current-fixtures', 'Generated matrix is conservative with zero supported rows; this preserves no-claim state but does not prove launch support.'
        return 'needs-release-review', f'Generated matrix has {rows} supported row(s); require explicit support-claim review.'
    goal = find_goal(goals, req.get('goal_prefix', ''))
    if not goal:
        return 'missing', 'Referenced goal not found.'
    status = goal.get('status')
    if status == 'complete':
        return 'proven', f'{goal["id"]} is complete in Ultragoal ledger.'
    return 'blocked', f'{goal["id"]} status is {status}; evidence: {goal.get("failureReason") or goal.get("evidence", "missing")}'


def render(plan: dict[str, Any]) -> tuple[str, bool]:
    goals = plan.get('goals', [])
    active = non_superseded(goals)
    failed = [g['id'] for g in active if g.get('status') == 'failed']
    pending = [g['id'] for g in active if g.get('status') == 'pending']
    in_progress = [g['id'] for g in active if g.get('status') == 'in_progress']
    review_blocked = [g['id'] for g in active if g.get('status') == 'review_blocked']
    req_rows = []
    all_proven = True
    for req in FINAL_REQUIREMENTS:
        status, detail = requirement_status(req, goals)
        if status != 'proven':
            all_proven = False
        req_rows.append((req, status, detail))
    aggregate_status = 'PASS' if all_proven else 'BLOCKED'
    matrix_status, supported_rows = support_matrix_status()
    retry_status, retry_detail = proof_retry_decision_status()

    lines = [
        '# Ultragoal Completion Audit',
        '',
        'Generated from `.omx/ultragoal/goals.json` and current release evidence.',
        '',
        f'**Aggregate completion status:** {aggregate_status}',
        '',
        f'- Total goals: {len(goals)}',
        f'- Non-superseded goals: {len(active)}',
        f'- Failed non-superseded goals: {len(failed)}',
        f'- Pending non-superseded goals: {len(pending)}',
        f'- In-progress non-superseded goals: {len(in_progress)}',
        f'- Review-blocked goals: {len(review_blocked)}',
        f'- Support matrix status: {matrix_status}',
        f'- Supported rows: {supported_rows if supported_rows is not None else "unknown"}',
        f'- Proof retry decision: {retry_status} — {retry_detail}',
        f'- Proof retry decision source: {PROOF_RETRY_DECISION_PATH}',
        '',
        '## Final requirements',
        '',
        '| Requirement | Status | Evidence / blocker |',
        '| --- | --- | --- |',
    ]
    for req, status, detail in req_rows:
        safe_detail = str(detail).replace('\n', ' ')
        lines.append(f'| {req["id"]}: {req["title"]} | {status} | {safe_detail} |')

    lines.extend([
        '',
        '## Current external blockers',
        '',
    ])
    blockers = failed + pending + in_progress + review_blocked
    if blockers:
        for gid in blockers:
            goal = find_goal(goals, gid.split('-', 1)[0]) or next((g for g in active if g['id'] == gid), None)
            if goal:
                reason = goal.get('failureReason') or goal.get('evidence') or 'No evidence recorded.'
                lines.append(f'- `{gid}`: {reason}')
    else:
        lines.append('- None recorded in the Ultragoal ledger.')

    lines.extend([
        '',
        '## Interpretation',
        '',
        '- Scaffold, harness, validator, template, and no-false-claim work can be complete without proving launch support.',
        '- Final aggregate completion requires real validated iOS/Android device evidence for G008/G010 and final G012 QA/review.',
        '- Do not call `update_goal complete` unless every requirement above is `proven` and the release gate passes.',
        '',
    ])
    return '\n'.join(lines), all_proven


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--output', default=str(DEFAULT_OUTPUT))
    ap.add_argument('--fail-if-complete', action='store_true', help='Return non-zero if the audit says aggregate completion is already proven.')
    args = ap.parse_args()
    text, all_proven = render(load_plan())
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text + '\n')
    print(f'wrote: {output}')
    print(f'aggregate_completion_status: {"PASS" if all_proven else "BLOCKED"}')
    if args.fail_if_complete and all_proven:
        raise SystemExit('aggregate completion unexpectedly proven')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
