#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path

proc = subprocess.run(
    ['python3', 'scripts/validate_device_lab_evidence_root.py', '--include-samples'],
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
)
print(proc.stdout)
if proc.returncode != 0:
    raise SystemExit(f'expected evidence-root intake to pass, got {proc.returncode}')
for token in [
    'Device lab evidence root intake validation: PASS',
    'real_bundles_found: 0',
    'sample_bundles_validated: 2',
    'supported_rows: 0',
    'No false support claims validation: PASS',
]:
    assert token in proc.stdout, token

asr_bundle = Path('benchmarks/device-lab/samples/bundles/asr-not-claimed.bundle.json')
with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    bad_bundle = json.loads(asr_bundle.read_text())
    bad_bundle['expected_support_decision'] = 'supported'
    bad_path = root / 'bad.bundle.json'
    bad_path.write_text(json.dumps(bad_bundle))
    proc = subprocess.run(
        ['python3', 'scripts/validate_device_lab_evidence_root.py', '--results-root', str(root)],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if proc.returncode == 0 or 'support_decision differs from expected_support_decision' not in proc.stdout:
        print(proc.stdout)
        raise SystemExit('expected evidence-root intake to reject inconsistent bundle')

print('Device lab evidence root intake validator validation: PASS')
