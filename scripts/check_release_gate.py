#!/usr/bin/env python3
from pathlib import Path
import json
import subprocess
import sys


def run(cmd: list[str]) -> None:
    print('RUN', ' '.join(cmd))
    subprocess.run(cmd, check=True)


run(['python3', 'scripts/validate_all_scaffolds.py'])
run(['python3', 'scripts/validate_mobile_mcp_qa.py'])
run(['python3', 'scripts/validate_mobile_mcp_run_artifacts.py', '--run-dir', 'docs/qa/mobile-mcp-samples/android/launch-offline-local-first', '--allow-sample'])
run(['python3', 'scripts/validate_device_lab_kit.py'])
run(['python3', 'scripts/validate_device_lab_preflight.py', '--allow-missing', '--expect-blocked'])
run(['python3', 'scripts/validate_device_lab_evidence_root.py', '--include-samples'])
run(['python3', 'scripts/validate_device_lab_intake_report.py'])
run(['python3', 'scripts/validate_support_matrix_generator.py'])
run(['python3', 'scripts/validate_no_false_support_claims.py'])
run(['python3', 'scripts/validate_device_lab_readiness.py'])
run(['python3', 'scripts/validate_proof_coverage.py'])
run(['python3', 'scripts/validate_proof_retry_decision.py'])
run(['python3', 'scripts/validate_completion_audit.py'])

support_matrix = Path('docs/benchmarks/support-matrix.generated.md').read_text()
if 'Conservative rule' not in support_matrix:
    raise SystemExit('Release gate: missing conservative support-matrix rule')
if 'Supported rows: 0' in support_matrix:
    print('Release gate note: current generated support matrix has zero supported rows')

plan = json.loads(Path('.omx/ultragoal/goals.json').read_text())
failed = [g['id'] for g in plan['goals'] if g['status'] == 'failed']
pending = [g['id'] for g in plan['goals'] if g['status'] == 'pending' and not g.get('steeringStatus') == 'superseded']
in_progress = [g['id'] for g in plan['goals'] if g['status'] == 'in_progress' and not g.get('steeringStatus') == 'superseded']
review_blocked = [g['id'] for g in plan['goals'] if g['status'] == 'review_blocked']

proof_blockers = [gid for gid in failed + pending + in_progress if gid.startswith(('G008', 'G010', 'G012'))]
if failed or pending or in_progress or review_blocked:
    print('Release gate: BLOCKED')
    print('failed:', failed)
    print('pending:', pending)
    print('in_progress:', in_progress)
    print('review_blocked:', review_blocked)
    print('proof_blockers:', proof_blockers)
    sys.exit(2)
print('Release gate: PASS')
