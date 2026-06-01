#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path


def load(path: Path):
    return json.loads(path.read_text())


def assert_template_dir(root: Path, kind: str) -> None:
    required = [
        'README.md',
        'benchmark-result.template.json',
        'telemetry.template.jsonl',
        'device-metadata.template.json',
        'battery-thermal-log.template.json',
        'package-evidence.template.json',
        'runtime-conversion-evidence.template.json',
        'legal-review-evidence.template.json',
        'model-manifest.template.json',
        'evidence.bundle.template.json',
    ]
    for name in required:
        if not (root / name).is_file():
            raise SystemExit(f'{root}: missing {name}')
    ingestible = sorted(root.rglob('*.bundle.json'))
    if ingestible:
        raise SystemExit(f'{root}: template generator must not create ingestible bundle files: {ingestible}')

    result = load(root / 'benchmark-result.template.json')
    metadata = load(root / 'device-metadata.template.json')
    battery_thermal = load(root / 'battery-thermal-log.template.json')
    package_evidence = load(root / 'package-evidence.template.json')
    runtime_conversion = load(root / 'runtime-conversion-evidence.template.json')
    legal_review = load(root / 'legal-review-evidence.template.json')
    manifest = load(root / 'model-manifest.template.json')
    bundle = load(root / 'evidence.bundle.template.json')
    readme = (root / 'README.md').read_text()
    telemetry = (root / 'telemetry.template.jsonl').read_text()

    assert result['result_type'] == kind
    assert result['support_decision'] == 'needs_review'
    assert bundle['expected_support_decision'] == 'needs_review'
    assert bundle['offline_no_network'] is True
    assert bundle['device_metadata_path'].endswith('device-metadata.json')
    assert bundle['battery_thermal_log_path'].endswith('battery-thermal-log.json')
    assert metadata['device_model'] == result['device_model']
    assert metadata['os_version'] == result['os_version']
    assert metadata['device_tier'] == result['device_tier']
    assert battery_thermal['device_model'] == result['device_model']
    assert battery_thermal['os_version'] == result['os_version']
    assert manifest['package_evidence_path'].endswith('package-evidence.json')
    assert manifest['runtime_conversion_evidence_path'].endswith('runtime-conversion-evidence.json')
    assert manifest['legal_review_evidence_path'].endswith('legal-review-evidence.json')
    assert manifest['runtime_conversion_status'] == runtime_conversion['runtime_conversion_status']
    assert package_evidence['model_id'] == manifest['model_id']
    assert runtime_conversion['model_id'] == manifest['model_id']
    assert runtime_conversion['runtime_id'] == result['runtime_id']
    assert legal_review['model_id'] == manifest['model_id']
    assert legal_review['legal_review_status'] == manifest['legal_review_status']
    assert any(str(path).endswith('device-metadata.json') for path in result['evidence_paths'])
    assert any(str(path).endswith('battery-thermal-log.json') for path in result['evidence_paths'])
    assert any(str(path).endswith('package-evidence.json') for path in result['evidence_paths'])
    assert any(str(path).endswith('runtime-conversion-evidence.json') for path in result['evidence_paths'])
    assert any(str(path).endswith('legal-review-evidence.json') for path in result['evidence_paths'])
    assert manifest['distribution_mode'] == 'internal_lab_only'
    assert manifest['model_id'] == result['model_id']
    assert result['runtime_id'] in manifest['runtime_ids']
    assert 'not a support claim' in bundle['notes']
    assert 'validate_device_lab_evidence_root.py' in readme
    assert 'REPLACE_' in json.dumps(result) + json.dumps(metadata) + json.dumps(battery_thermal) + json.dumps(package_evidence) + json.dumps(runtime_conversion) + json.dumps(legal_review) + json.dumps(manifest) + json.dumps(bundle) + telemetry
    if kind == 'asr':
        assert result['goal_id'].startswith('G008')
        assert 'asr_partial_emitted' in bundle['telemetry_require_event_counts']
    else:
        assert result['goal_id'].startswith('G010')
        assert 'mt_result_emitted' in bundle['telemetry_require_event_counts']


with tempfile.TemporaryDirectory() as tmp:
    base = Path(tmp)
    asr_dir = base / 'asr'
    mt_dir = base / 'mt'
    asr_proc = subprocess.run([
        'python3', 'scripts/create_device_lab_run_template.py',
        '--type', 'asr',
        '--candidate-id', 'en-zipformer-mid-high-2023-06-26',
        '--device-model', 'Pixel 8 Pro lab',
        '--os-version', 'Android 15 lab',
        '--device-tier', 'high',
        '--language', 'en',
        '--output-dir', str(asr_dir),
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    print(asr_proc.stdout)
    mt_proc = subprocess.run([
        'python3', 'scripts/create_device_lab_run_template.py',
        '--type', 'mt',
        '--candidate-id', 'milmmt-46-4b-q6-gguf-high-tier',
        '--device-model', 'iPhone 15 Pro lab',
        '--os-version', 'iOS 18 lab',
        '--device-tier', 'high',
        '--language-pair', 'en->ru',
        '--runtime-id', 'llama.cpp/GGUF',
        '--output-dir', str(mt_dir),
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    print(mt_proc.stdout)
    assert_template_dir(asr_dir, 'asr')
    assert_template_dir(mt_dir, 'mt')

print('Device-lab run template generator validation: PASS')
