#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

REPORT_JSON = Path('docs/benchmarks/device-lab-intake.generated.json')
REPORT_MD = Path('docs/benchmarks/device-lab-intake.generated.md')

proc = subprocess.run(
    [
        'python3',
        'scripts/validate_device_lab_evidence_root.py',
        '--include-samples',
        '--report-json', str(REPORT_JSON),
        '--report-md', str(REPORT_MD),
    ],
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    check=True,
)
print(proc.stdout)
if not REPORT_JSON.is_file() or not REPORT_MD.is_file():
    raise SystemExit('device-lab intake reports were not generated')
report = json.loads(REPORT_JSON.read_text())
text = REPORT_MD.read_text()
assert report['schema_version'] == 1
assert report['intake_status'] == 'BLOCKED'
assert report['allow_support_claims'] is False
assert report['real_bundles_found'] == 0
assert report['sample_bundles_validated'] == 2
assert report['supported_rows'] == 0
for blocker in ['no_real_device_lab_bundles_found', 'zero_supported_rows', 'sample_fixtures_only_not_real_evidence']:
    assert blocker in report['blockers'], blocker
for path in [
    'benchmarks/device-lab/samples/bundles/asr-not-claimed.bundle.json',
    'benchmarks/device-lab/samples/bundles/mt-not-claimed.bundle.json',
]:
    assert path in report['sample_bundle_paths'], path
for token in [
    'Device Lab Evidence Intake Report',
    'Intake status:** BLOCKED',
    'Allow support claims:** false',
    'Real bundles found:** 0',
    'Supported rows:** 0',
    'not ASR/MT benchmark proof',
    'Do not retry/close G008 or G010',
]:
    assert token in text, token
print('Device lab intake report validation: PASS')
