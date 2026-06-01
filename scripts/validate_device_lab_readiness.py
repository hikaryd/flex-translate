#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

proc = subprocess.run(
    ['python3', 'scripts/generate_device_lab_readiness.py'],
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    check=True,
)
print(proc.stdout)
md = Path('docs/benchmarks/device-lab-readiness.generated.md')
js = Path('docs/benchmarks/device-lab-readiness.generated.json')
if not md.is_file() or not js.is_file():
    raise SystemExit('device-lab readiness report was not generated')
text = md.read_text()
report = json.loads(js.read_text())

assert report['schema_version'] == 1
assert report['aggregate_readiness'] == 'BLOCKED'
assert report['lab_execution_readiness'] == 'BLOCKED'
assert report['release_claim_readiness'] == 'BLOCKED'
assert report['support_claims_allowed'] is False
assert report['real_bundles_found'] == 0
assert report['support_matrix']['supported_rows'] == 0
assert report['device_lab_intake']['intake_status'] == 'BLOCKED'
assert report['device_lab_intake']['supported_rows'] == 0
assert report['device_matrix_validation']['validation_status'] == 'PASS_WITH_TBD_PLANNING_ONLY'
assert report['device_matrix_validation']['execution_readiness'] == 'BLOCKED_PLANNING_ONLY'
assert report['device_lab_preflight']['status'] == 'BLOCKED'
assert report['device_lab_preflight']['preflight_authority'] == 'execution_readiness_only_not_support_evidence'
assert len(report['device_matrix_validation']['missing_device_fields']) == 12
assert 'device_matrix_has_tbd_sku_or_os' in report['lab_blockers']
assert 'device_lab_preflight_blocked' in report['lab_blockers']
for blocker in [
    'no_real_device_lab_bundles_found',
    'zero_supported_rows',
    'proof_or_final_qa_goals_open',
    'support_claims_not_allowed_by_matrix',
    'device_lab_intake_report_blocked_or_missing',
]:
    assert blocker in report['evidence_blockers'], blocker
open_ids = [g['id'] for g in report['open_goal_blockers']]
for prefix in ['G008', 'G010', 'G012']:
    assert any(gid.startswith(prefix) for gid in open_ids), prefix
for token in [
    'Aggregate readiness:** BLOCKED',
    'Lab execution readiness:** BLOCKED',
    'Release claim readiness:** BLOCKED',
    'Support claims allowed:** false',
    'Real bundles found:** 0',
    'Supported rows:** 0',
    'Device matrix validation:** PASS_WITH_TBD_PLANNING_ONLY',
    'Device-lab preflight:** BLOCKED',
    'Device-lab intake:** BLOCKED',
    'python3 scripts/validate_device_lab_preflight.py',
    'G008',
    'G010',
    'G012',
    'python3 scripts/validate_device_lab_matrix.py',
    'python3 scripts/generate_device_lab_run_plan.py',
    'python3 scripts/validate_device_lab_evidence_root.py',
    'python3 scripts/check_release_gate.py',
    'This readiness report is a gate/status document only; it creates no ASR/MT support claim.',
]:
    assert token in text, token
print('Device lab readiness validation: PASS')
