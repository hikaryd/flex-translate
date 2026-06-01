#!/usr/bin/env python3
from pathlib import Path
import subprocess

proc = subprocess.run(['python3', 'scripts/check_release_gate.py'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
print(proc.stdout)
if proc.returncode != 2:
    raise SystemExit(f'expected blocked release gate exit 2, got {proc.returncode}')
for token in [
    'RUN python3 scripts/validate_device_lab_kit.py',
    'RUN python3 scripts/validate_device_lab_preflight.py --allow-missing --expect-blocked',
    'Device-lab execution preflight: BLOCKED',
    'RUN python3 scripts/validate_device_lab_evidence_root.py --include-samples',
    'RUN python3 scripts/validate_device_lab_intake_report.py',
    'Device lab intake report validation: PASS',
    'Device lab evidence root intake validation: PASS',
    'RUN python3 scripts/validate_support_matrix_generator.py',
    'RUN python3 scripts/validate_no_false_support_claims.py',
    'RUN python3 scripts/validate_proof_retry_decision.py',
    'Proof retry decision validation: PASS',
    'RUN python3 scripts/validate_completion_audit.py',
    'No false support claims validation: PASS',
    'Completion audit validation: PASS',
    'Release gate: BLOCKED',
    'G008-offline-asr-real-device-benchmark-pr',
    'G010-offline-translation-real-runtime-and',
    'G012-final-qa-cleanup-and-independent-rev',
]:
    assert token in proc.stdout, token
text = Path('docs/qa/release-gate.md').read_text()
for token in ['Automated release-gate script', 'support-matrix generator', 'proof retry decision', 'not_claimed', 'intentionally conservative']:
    assert token in text, token
print('Release gate integration validation: PASS')
