#!/usr/bin/env python3
from __future__ import annotations

import subprocess
from pathlib import Path

proc = subprocess.run(
    ['python3', 'scripts/generate_device_lab_run_plan.py'],
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    check=True,
)
print(proc.stdout)
report = Path('docs/benchmarks/device-lab-run-plan.generated.md')
if not report.is_file():
    raise SystemExit('device-lab run plan was not generated')
text = report.read_text()
for token in [
    'Execution readiness:** BLOCKED',
    'Support claims allowed:** false',
    'G008',
    'G010',
    'Planned ASR runs:',
    'Planned MT runtime/pair runs:',
    'python3 scripts/create_device_lab_run_template.py',
    'python3 scripts/validate_device_lab_evidence_root.py',
    'python3 scripts/check_release_gate.py',
    'This plan is not evidence and creates no ASR/MT support claim.',
    'Device SKUs and/or OS versions',
]:
    assert token in text, token
if 'support_decision: supported' in text or '✅ supported' in text:
    raise SystemExit('run plan must not contain support claims')
print('Device lab run plan validation: PASS')
