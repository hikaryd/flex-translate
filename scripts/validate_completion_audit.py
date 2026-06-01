#!/usr/bin/env python3
from __future__ import annotations

import subprocess
from pathlib import Path

proc = subprocess.run(
    ['python3', 'scripts/generate_completion_audit.py', '--fail-if-complete'],
    text=True,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    check=True,
)
print(proc.stdout)
report = Path('docs/qa/completion-audit.generated.md')
if not report.is_file():
    raise SystemExit('completion audit report was not generated')
text = report.read_text()
for token in [
    'Aggregate completion status:** BLOCKED',
    'R1-no-open-ultragoal-stories',
    'R2-real-asr-proof',
    'R3-real-mt-legal-proof',
    'R4-final-qa-review',
    'R5-no-false-support-claims',
    'R6-proof-retry-decision',
    'G008-offline-asr-real-device-benchmark-pr',
    'G010-offline-translation-real-runtime-and',
    'G012-final-qa-cleanup-and-independent-rev',
    'Supported rows: 0',
    'Proof retry decision: blocked',
    'proof-retry-decision.generated.json',
    'Do not call `update_goal complete`',
]:
    assert token in text, token
print('Completion audit validation: PASS')
