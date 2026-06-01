#!/usr/bin/env python3
import subprocess
import sys

checks = [
    ['python3', 'scripts/validate_g002_foundation.py'],
    ['python3', 'scripts/validate_g003_asr_scaffold.py'],
    ['python3', 'scripts/validate_g004_mt_scaffold.py'],
    ['python3', 'scripts/validate_g005_cloud_opt_in.py'],
]
for cmd in checks:
    print('RUN', ' '.join(cmd))
    subprocess.run(cmd, check=True)
print('All scaffold validations: PASS')
