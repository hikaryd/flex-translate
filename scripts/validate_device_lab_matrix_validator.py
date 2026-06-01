#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path


def run(cmd: list[str], *, expect: int = 0) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(cmd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print('RUN', ' '.join(cmd))
    print(proc.stdout)
    if proc.returncode != expect:
        raise SystemExit(f'expected exit {expect}, got {proc.returncode}')
    return proc


# Current repository matrix must fail strict mode because SKU/OS fields are still TBD.
strict = run(['python3', 'scripts/validate_device_lab_matrix.py', '--json'], expect=2)
strict_report = json.loads(strict.stdout)
assert strict_report['validation_status'] == 'FAIL'
assert strict_report['execution_readiness'] == 'BLOCKED_PLANNING_ONLY'
assert len(strict_report['missing_device_fields']) == 12
assert 'low/android.sku' in strict_report['missing_device_fields']

# Planning mode is allowed for the current conservative repo state, but still not executable evidence.
planning = run(['python3', 'scripts/validate_device_lab_matrix.py', '--allow-tbd', '--json'])
planning_report = json.loads(planning.stdout)
assert planning_report['validation_status'] == 'PASS_WITH_TBD_PLANNING_ONLY'
assert planning_report['execution_readiness'] == 'BLOCKED_PLANNING_ONLY'
assert planning_report['support_claims_allowed'] is False
assert 'not ASR/MT benchmark evidence' in planning_report['non_evidence_notice']

base = json.loads(Path('configs/device-lab-matrix.json').read_text())
filled_skus = {
    ('low', 'android'): ('Pixel 6a lab-001', 'Android 15'),
    ('low', 'ios'): ('iPhone 13 lab-001', 'iOS 18.5'),
    ('mid', 'android'): ('Pixel 8 lab-001', 'Android 15'),
    ('mid', 'ios'): ('iPhone 15 lab-001', 'iOS 18.5'),
    ('high', 'android'): ('Galaxy S24 Ultra lab-001', 'Android 15'),
    ('high', 'ios'): ('iPhone 15 Pro lab-001', 'iOS 18.5'),
}
for tier in base['deviceTiers']:
    tier_name = tier['tier']
    for platform in ['android', 'ios']:
        sku, os_version = filled_skus[(tier_name, platform)]
        tier[platform]['sku'] = sku
        tier[platform]['osVersion'] = os_version

with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / 'device-lab-matrix.filled.json'
    path.write_text(json.dumps(base, indent=2) + '\n')
    positive = run(['python3', 'scripts/validate_device_lab_matrix.py', '--matrix', str(path), '--json'])
    positive_report = json.loads(positive.stdout)
    assert positive_report['validation_status'] == 'PASS'
    assert positive_report['execution_readiness'] == 'READY_FOR_LAB_EXECUTION'
    assert positive_report['missing_device_fields'] == []
    assert positive_report['platform_target_count'] == 6

    bad = json.loads(path.read_text())
    bad['requiredArtifactsPerRun'].remove('battery_thermal_log')
    bad_path = Path(tmp) / 'device-lab-matrix.bad.json'
    bad_path.write_text(json.dumps(bad, indent=2) + '\n')
    bad_proc = run(['python3', 'scripts/validate_device_lab_matrix.py', '--matrix', str(bad_path), '--json'], expect=2)
    bad_report = json.loads(bad_proc.stdout)
    assert any('requiredArtifactsPerRun' in err and 'battery_thermal_log' in err for err in bad_report['errors'])

print('Device lab matrix validator validation: PASS')
