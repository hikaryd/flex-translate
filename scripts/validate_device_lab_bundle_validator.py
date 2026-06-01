#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path

ASR_BUNDLE = Path('benchmarks/device-lab/samples/bundles/asr-not-claimed.bundle.json')
MT_BUNDLE = Path('benchmarks/device-lab/samples/bundles/mt-not-claimed.bundle.json')

subprocess.run(['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(ASR_BUNDLE)], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(MT_BUNDLE)], check=True)

with tempfile.TemporaryDirectory() as tmp:
    tmp_path = Path(tmp)
    broken = json.loads(ASR_BUNDLE.read_text())
    broken['expected_support_decision'] = 'supported'
    broken_path = tmp_path / 'broken-expected-support.bundle.json'
    broken_path.write_text(json.dumps(broken))
    proc = subprocess.run(['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(broken_path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'support_decision differs from expected_support_decision' not in proc.stdout:
        raise SystemExit('expected bundle support decision mismatch rejection')

    missing_manifest = json.loads(ASR_BUNDLE.read_text())
    missing_manifest['model_manifest_path'] = 'benchmarks/device-lab/samples/missing-model-manifest.json'
    missing_path = tmp_path / 'missing-manifest.bundle.json'
    missing_path.write_text(json.dumps(missing_manifest))
    proc = subprocess.run(['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(missing_path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'referenced evidence file does not exist' not in proc.stdout:
        raise SystemExit('expected missing manifest rejection')

    missing_device_metadata = json.loads(ASR_BUNDLE.read_text())
    missing_device_metadata.pop('device_metadata_path')
    missing_metadata_path = tmp_path / 'missing-device-metadata.bundle.json'
    missing_metadata_path.write_text(json.dumps(missing_device_metadata))
    proc = subprocess.run(['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(missing_metadata_path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'missing required bundle fields' not in proc.stdout:
        raise SystemExit('expected missing device metadata path rejection')

    missing_battery = json.loads(ASR_BUNDLE.read_text())
    missing_battery['battery_thermal_log_path'] = 'benchmarks/device-lab/samples/missing-battery-thermal-log.json'
    missing_battery_path = tmp_path / 'missing-battery.bundle.json'
    missing_battery_path.write_text(json.dumps(missing_battery))
    proc = subprocess.run(['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(missing_battery_path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'referenced evidence file does not exist' not in proc.stdout:
        raise SystemExit('expected missing battery/thermal log rejection')

print('Device lab bundle validator validation: PASS')
