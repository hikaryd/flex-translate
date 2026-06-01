#!/usr/bin/env python3
from pathlib import Path
import subprocess

required = [
    'docs/qa/release-gate.md',
    'scripts/validate_all_scaffolds.py',
    'scripts/check_release_gate.py',
    'configs/mobile-mcp-scenarios.json',
    'docs/qa/mobile-mcp-automation.md',
    'scripts/validate_mobile_mcp_qa.py',
    'scripts/validate_mobile_mcp_run_artifacts.py',
    'docs/qa/mobile-mcp-samples/android/launch-offline-local-first/mobile-mcp-session.json',
]
missing = [p for p in required if not Path(p).is_file()]
if missing:
    raise SystemExit(f'missing: {missing}')
text = Path('docs/qa/release-gate.md').read_text()
for token in ['mobile-mcp', 'validate_mobile_mcp_qa.py', 'Native performance', 'no-silent-cloud', 'ai-slop-cleaner', 'independent code review']:
    assert token in text, token
subprocess.run(['python3', 'scripts/validate_all_scaffolds.py'], check=True)
subprocess.run(['python3', 'scripts/validate_mobile_mcp_qa.py'], check=True)
subprocess.run(['python3', 'scripts/validate_mobile_mcp_run_artifacts.py', '--run-dir', 'docs/qa/mobile-mcp-samples/android/launch-offline-local-first', '--allow-sample'], check=True)
print('G006 QA scaffold validation: PASS')
