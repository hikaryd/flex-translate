#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
from pathlib import Path

proc = subprocess.run(
    ['python3', 'scripts/generate_proof_coverage.py'],
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    check=True,
)
print(proc.stdout)
md = Path('docs/benchmarks/proof-coverage.generated.md')
js = Path('docs/benchmarks/proof-coverage.generated.json')
if not md.is_file() or not js.is_file():
    raise SystemExit('proof coverage report was not generated')
text = md.read_text()
report = json.loads(js.read_text())

assert report['schema_version'] == 1
assert report['aggregate_proof_coverage'] == 'BLOCKED'
assert report['g008_asr_proof_coverage'] == 'BLOCKED'
assert report['g010_mt_proof_coverage'] == 'BLOCKED'
assert report['bundle_count'] == 0
assert report['asr']['proof_usable_rows'] == 0
assert report['mt']['proof_usable_rows'] == 0
for blocker in [
    'device_matrix_has_tbd_sku_or_os',
    'no_real_device_lab_bundles_found',
    'g008_has_no_proof_usable_asr_rows',
    'g010_has_no_proof_usable_mt_rows',
]:
    assert blocker in report['blockers'], blocker
for token in [
    'Aggregate proof coverage:** BLOCKED',
    'G008 ASR proof coverage:** BLOCKED',
    'G010 MT proof coverage:** BLOCKED',
    'Real bundle count:** 0',
    'No real device-lab bundles found under the results root.',
    'This proof coverage report audits evidence completeness only; it does not create support claims or close G008/G010.',
    'python3 scripts/validate_device_lab_evidence_root.py',
    'python3 scripts/generate_proof_coverage.py',
    'python3 scripts/check_release_gate.py',
]:
    assert token in text, token
print('Proof coverage validation: PASS')
