#!/usr/bin/env python3
from pathlib import Path
import subprocess

out = 'docs/benchmarks/support-matrix.generated.md'
subprocess.run([
    'python3', 'scripts/generate_support_matrix.py',
    '--include-samples',
    '--output', out,
], check=True)
text = Path(out).read_text()
for token in ['Generated Support Matrix', 'Conservative rule', 'Evidence files scanned: 2', 'Supported rows: 0', 'not claimed']:
    assert token in text, token
assert '✅ supported' not in text, 'sample not_claimed evidence must not become supported'
print('Support matrix generator validation: PASS')
print(f'output: {out}')
