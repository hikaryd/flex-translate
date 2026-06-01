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


run([
    'python3', 'scripts/validate_device_metadata.py',
    '--metadata', 'benchmarks/device-lab/samples/device-metadata.asr.fixture.json',
    '--expected-device-model', 'lab-device-tbd',
    '--expected-os-version', 'tbd',
    '--expected-device-tier', 'high',
    '--allow-placeholder',
])
strict_meta = run([
    'python3', 'scripts/validate_device_metadata.py',
    '--metadata', 'benchmarks/device-lab/samples/device-metadata.asr.fixture.json',
    '--expected-device-model', 'lab-device-tbd',
    '--expected-os-version', 'tbd',
    '--expected-device-tier', 'high',
], expect=1)
assert 'physical_device=true' in strict_meta.stdout or 'must be concrete' in strict_meta.stdout

run([
    'python3', 'scripts/validate_battery_thermal_log.py',
    '--log', 'benchmarks/device-lab/samples/battery-thermal.asr.fixture.json',
    '--expected-device-model', 'lab-device-tbd',
    '--expected-os-version', 'tbd',
    '--expected-battery-delta-percent-30m', '0',
    '--expected-thermal-result', 'not_measured',
    '--allow-not-measured',
])
strict_battery = run([
    'python3', 'scripts/validate_battery_thermal_log.py',
    '--log', 'benchmarks/device-lab/samples/battery-thermal.asr.fixture.json',
    '--expected-device-model', 'lab-device-tbd',
    '--expected-os-version', 'tbd',
    '--expected-battery-delta-percent-30m', '0',
    '--expected-thermal-result', 'not_measured',
], expect=1)
assert 'not_measured' in strict_battery.stdout

with tempfile.TemporaryDirectory() as tmp:
    tmp_path = Path(tmp)
    metadata = {
        'schema_version': 1,
        'platform': 'ios',
        'device_model': 'iPhone 15 Pro lab-001',
        'device_sku': 'A3102-lab-001',
        'device_tier': 'high',
        'os_version': 'iOS 18.5',
        'physical_device': True,
        'airplane_mode_verified': True,
        'telemetry_export_enabled': True,
        'app_build_id': 'FlexTranslate-2026.06.01-lab',
    }
    metadata_path = tmp_path / 'device-metadata.json'
    metadata_path.write_text(json.dumps(metadata, indent=2) + '\n')
    run([
        'python3', 'scripts/validate_device_metadata.py',
        '--metadata', str(metadata_path),
        '--expected-device-model', 'iPhone 15 Pro lab-001',
        '--expected-os-version', 'iOS 18.5',
        '--expected-device-tier', 'high',
    ])

    battery = {
        'schema_version': 1,
        'session_id': 'strict-positive-session',
        'device_model': 'iPhone 15 Pro lab-001',
        'os_version': 'iOS 18.5',
        'duration_minutes': 30,
        'battery_start_percent': 90,
        'battery_end_percent': 84,
        'battery_delta_percent_30m': 6,
        'thermal_result': 'nominal',
        'samples': [
            {'minute': 0, 'battery_percent': 90, 'thermal_state': 'nominal'},
            {'minute': 30, 'battery_percent': 84, 'thermal_state': 'nominal'},
        ],
    }
    battery_path = tmp_path / 'battery-thermal-log.json'
    battery_path.write_text(json.dumps(battery, indent=2) + '\n')
    run([
        'python3', 'scripts/validate_battery_thermal_log.py',
        '--log', str(battery_path),
        '--expected-device-model', 'iPhone 15 Pro lab-001',
        '--expected-os-version', 'iOS 18.5',
        '--expected-battery-delta-percent-30m', '6',
        '--expected-thermal-result', 'nominal',
    ])

print('Device artifact validators validation: PASS')
