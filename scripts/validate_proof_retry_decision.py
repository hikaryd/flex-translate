#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

# Refresh upstream generated gates so the retry decision is based on current repo state.
for cmd in [
    ['python3', 'scripts/validate_device_lab_intake_report.py'],
    ['python3', 'scripts/validate_device_lab_readiness.py'],
    ['python3', 'scripts/validate_proof_coverage.py'],
]:
    proc = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    print(proc.stdout)

proc = subprocess.run(
    ['python3', 'scripts/generate_proof_retry_decision.py'],
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    check=True,
)
print(proc.stdout)
md = Path('docs/benchmarks/proof-retry-decision.generated.md')
js = Path('docs/benchmarks/proof-retry-decision.generated.json')
if not md.is_file() or not js.is_file():
    raise SystemExit('proof retry decision report was not generated')
text = md.read_text()
report = json.loads(js.read_text())

assert report['schema_version'] == 1
assert report['aggregate_retry_decision'] == 'BLOCKED'
assert report['can_retry_any_goal'] is False
assert report['can_mark_any_goal_complete'] is False
assert 'one_or_more_proof_goals_not_ready_for_retry' in report['aggregate_blockers']
assert 'open_failed_or_pending_ultragoal_stories' in report['aggregate_blockers']
for blocker in [
    'no_real_device_lab_bundles_found',
    'device_matrix_has_tbd_sku_or_os',
    'device_lab_preflight_blocked',
    'device_lab_intake_blocked',
    'zero_supported_rows',
    'proof_or_final_qa_goals_open',
]:
    assert blocker in report['aggregate_blockers'], blocker

decisions = {d['goal_key']: d for d in report['target_decisions']}
for key in ['G008', 'G010']:
    decision = decisions[key]
    assert decision['decision_status'] == 'BLOCKED'
    assert decision['can_retry_goal'] is False
    assert decision['can_mark_complete'] is False
    assert decision['goal_status'] == 'failed'
    assert f'{key.lower()}_goal_status_failed' in decision['blockers']
    assert f'{key.lower()}_proof_coverage_blocked' in decision['blockers']
    assert 'no_real_device_lab_bundles_found' in decision['blockers']
    assert 'zero_supported_rows' in decision['blockers']
    assert 'device_lab_intake_blocked' in decision['blockers']
    assert 'device_lab_preflight_blocked' in decision['blockers']
    assert 'zero_proof_usable_rows' in decision['evidence_gaps']
assert 'g008_has_no_proof_usable_asr_rows' in decisions['G008']['blockers']
assert 'g010_has_no_proof_usable_mt_rows' in decisions['G010']['blockers']
assert 'g010_legal_review_proof_missing' in decisions['G010']['evidence_gaps']

for token in [
    'Aggregate retry decision:** BLOCKED',
    'Can retry any goal:** false',
    'Can mark any goal complete:** false',
    'G008-offline-asr-real-device-benchmark-pr',
    'G010-offline-translation-real-runtime-and',
    'not ASR/MT benchmark proof',
    'device_lab_preflight_blocked',
    'device_lab_intake_blocked',
    'g008_has_no_proof_usable_asr_rows',
    'g010_has_no_proof_usable_mt_rows',
    'g010_legal_review_proof_missing',
    'python3 scripts/check_release_gate.py',
]:
    assert token in text, token
print('Proof retry decision validation: PASS')
