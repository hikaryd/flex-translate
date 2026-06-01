#!/usr/bin/env python3
"""Self-test the proof coverage gate's positive transition path.

This uses only temporary synthetic release-review fixtures. It must not write
anything under benchmarks/device-lab/results and must not create repository
support claims.
"""
from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path
from typing import Any


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + '\n')


def write_telemetry(path: Path, *, session: str, runtime: str, model: str, pair: str, event_types: list[str]) -> None:
    rows = []
    for index, event_type in enumerate(event_types):
        rows.append({
            'session_id': session,
            'monotonic_ts_ms': index * 100,
            'event_type': event_type,
            'device_tier': 'high',
            'device_model': 'synthetic-device-not-a-claim',
            'os_version': 'synthetic-os',
            'runtime_id': runtime,
            'model_id': model,
            'language_pair': pair,
            'mode': 'offline',
            'network_state': 'offline',
            'app_build': 'synthetic-build',
            'payload': {},
        })
    path.write_text('\n'.join(json.dumps(row, sort_keys=True) for row in rows) + '\n')


def approved_manifest(*, model_id: str, runtime_id: str, family: str, fmt: str, intended_use: str, sha_char: str, conversion_status: str) -> dict[str, Any]:
    return {
        'schema_version': 1,
        'model_id': model_id,
        'model_name': f'Synthetic {model_id}',
        'model_version': 'synthetic-positive-transition-only',
        'model_family': family,
        'artifact_format': fmt,
        'artifact_source_url': f'https://example.invalid/synthetic/{model_id}',
        'license_url': 'https://example.invalid/synthetic-license',
        'sha256': sha_char * 64,
        'artifact_size_mb': 123.0,
        'package_evidence_path': 'package-evidence.json',
        'runtime_ids': [runtime_id],
        'runtime_conversion_status': conversion_status,
        'runtime_conversion_evidence_path': 'runtime-conversion-evidence.json',
        'runtime_conversion_notes': 'Temporary synthetic runtime/package evidence for proof coverage transition validation only.',
        'legal_review_evidence_path': 'legal-review-evidence.json',
        'intended_use': intended_use,
        'distribution_mode': 'bundled',
        'legal_review_status': 'approved_for_distribution',
        'review_owner': 'synthetic-release-review@example.invalid',
        'review_date': '2026-06-01',
        'commercial_restrictions_reviewed': True,
        'privacy_impact_reviewed': True,
        'app_store_policy_reviewed': True,
        'notes': 'Temporary synthetic fixture for proof coverage transition validation only; not repository evidence.',
    }


def write_legal_review_evidence(run_dir: Path, *, model_id: str, source_url: str, license_url: str) -> None:
    write_json(run_dir / 'legal-review-evidence.json', {
        'schema_version': 1,
        'model_id': model_id,
        'legal_review_status': 'approved_for_distribution',
        'distribution_mode': 'bundled',
        'reviewer': 'synthetic-release-review@example.invalid',
        'review_date': '2026-06-01',
        'artifact_source_url': source_url,
        'license_url': license_url,
        'commercial_restrictions_reviewed': True,
        'privacy_impact_reviewed': True,
        'app_store_policy_reviewed': True,
        'decision': 'approved_for_distribution',
        'sensitive_family_reviewed': False,
        'sensitive_family_notes': 'Not a sensitive-family synthetic fixture; release review still human-gated.',
        'notes': 'Temporary synthetic legal evidence for transition validation only; not repository evidence.',
    })


def write_model_package_evidence(run_dir: Path, *, model_id: str, runtime_id: str, sha_char: str, conversion_status: str) -> None:
    write_json(run_dir / 'package-evidence.json', {
        'schema_version': 1,
        'model_id': model_id,
        'sha256': sha_char * 64,
        'artifact_size_mb': 123.0,
        'package_size_mb': 123.0,
        'measurement_method': 'synthetic package-size measurement for temp transition test',
        'package_files': [{'path': f'{model_id}.synthetic-package', 'size_mb': 123.0}],
        'notes': 'Temporary synthetic package evidence for proof coverage transition validation only; not repository evidence.',
    })
    write_json(run_dir / 'runtime-conversion-evidence.json', {
        'schema_version': 1,
        'model_id': model_id,
        'runtime_id': runtime_id,
        'runtime_conversion_status': conversion_status,
        'conversion_tool': 'synthetic',
        'source_format': 'synthetic',
        'target_format': 'synthetic',
        'validation_status': 'validated' if conversion_status == 'converted_and_validated' else 'not_applicable',
        'notes': 'Temporary synthetic runtime conversion evidence for proof coverage transition validation only; not repository evidence.',
    })


def write_device_artifacts(run_dir: Path, *, session: str, battery_delta: int, thermal_result: str) -> None:
    write_json(run_dir / 'device-metadata.json', {
        'schema_version': 1,
        'platform': 'android',
        'device_model': 'synthetic-device-not-a-claim',
        'device_sku': 'synthetic-device-sku',
        'device_tier': 'high',
        'os_version': 'Android 15',
        'physical_device': True,
        'airplane_mode_verified': True,
        'telemetry_export_enabled': True,
        'app_build_id': 'synthetic-build',
        'notes': 'Temporary synthetic fixture for proof coverage transition validation only; not repository evidence.',
    })
    write_json(run_dir / 'battery-thermal-log.json', {
        'schema_version': 1,
        'session_id': session,
        'device_model': 'synthetic-device-not-a-claim',
        'os_version': 'Android 15',
        'duration_minutes': 30,
        'battery_start_percent': 90,
        'battery_end_percent': 90 - battery_delta,
        'battery_delta_percent_30m': battery_delta,
        'thermal_result': thermal_result,
        'samples': [
            {'minute': 0, 'battery_percent': 90, 'thermal_state': 'nominal'},
            {'minute': 30, 'battery_percent': 90 - battery_delta, 'thermal_state': thermal_result},
        ],
        'notes': 'Temporary synthetic fixture for proof coverage transition validation only; not repository evidence.',
    })


def create_bundle(root: Path, *, result_type: str) -> Path:
    run_dir = root / result_type
    run_dir.mkdir(parents=True, exist_ok=True)
    telemetry_path = run_dir / 'telemetry.jsonl'
    manifest_path = run_dir / 'model-manifest.json'
    result_path = run_dir / 'benchmark-result.json'
    bundle_path = run_dir / 'evidence.bundle.json'

    if result_type == 'asr':
        model_id = 'synthetic-asr-model-ready'
        runtime_id = 'sherpa-onnx'
        write_telemetry(
            telemetry_path,
            session='synthetic-asr-ready',
            runtime=runtime_id,
            model=model_id,
            pair='none',
            event_types=[
                'audio_callback_received',
                'vad_speech_start',
                'asr_partial_emitted',
                'asr_partial_emitted',
                'asr_partial_emitted',
                'asr_final_emitted',
                'ui_transcript_rendered',
                'memory_sample',
                'battery_sample',
                'thermal_sample',
            ],
        )
        write_device_artifacts(run_dir, session='synthetic-asr-ready', battery_delta=6, thermal_result='nominal')
        write_model_package_evidence(run_dir, model_id=model_id, runtime_id=runtime_id, sha_char='b', conversion_status='native')
        write_legal_review_evidence(run_dir, model_id=model_id, source_url=f'https://example.invalid/synthetic/{model_id}', license_url='https://example.invalid/synthetic-license')
        write_json(manifest_path, approved_manifest(model_id=model_id, runtime_id=runtime_id, family='sherpa-onnx', fmt='onnx', intended_use='asr', sha_char='b', conversion_status='native'))
        write_json(result_path, {
            'goal_id': 'G008-offline-asr-real-device-benchmark-pr',
            'result_type': 'asr',
            'support_decision': 'supported',
            'device_model': 'synthetic-device-not-a-claim',
            'os_version': 'Android 15',
            'device_tier': 'high',
            'language': 'en',
            'model_id': model_id,
            'runtime_id': runtime_id,
            'package_size_mb': 123.0,
            'wer': 0.04,
            'cer': 0.02,
            'rtf': 0.35,
            'p95_partial_latency_ms': 320,
            'memory_peak_mb': 512,
            'battery_delta_percent_30m': 6,
            'thermal_result': 'nominal',
            'audio_dropouts': 0,
            'evidence_paths': ['telemetry.jsonl', 'device-metadata.json', 'battery-thermal-log.json', 'package-evidence.json', 'runtime-conversion-evidence.json', 'legal-review-evidence.json', 'model-manifest.json'],
        })
        counts = {'asr_partial_emitted': 3, 'memory_sample': 1, 'battery_sample': 1, 'thermal_sample': 1}
    else:
        model_id = 'synthetic-mt-model-ready'
        runtime_id = 'llama.cpp/GGUF'
        write_telemetry(
            telemetry_path,
            session='synthetic-mt-ready',
            runtime=runtime_id,
            model=model_id,
            pair='en->ru',
            event_types=[
                'mt_request_started',
                'mt_result_emitted',
                'ui_translation_rendered',
                'memory_sample',
                'battery_sample',
                'thermal_sample',
            ],
        )
        write_device_artifacts(run_dir, session='synthetic-mt-ready', battery_delta=8, thermal_result='nominal')
        write_model_package_evidence(run_dir, model_id=model_id, runtime_id=runtime_id, sha_char='c', conversion_status='converted_and_validated')
        write_legal_review_evidence(run_dir, model_id=model_id, source_url=f'https://example.invalid/synthetic/{model_id}', license_url='https://example.invalid/synthetic-license')
        write_json(manifest_path, approved_manifest(model_id=model_id, runtime_id=runtime_id, family='dedicated-mt', fmt='gguf', intended_use='mt', sha_char='c', conversion_status='converted_and_validated'))
        write_json(result_path, {
            'goal_id': 'G010-offline-translation-real-runtime-and',
            'result_type': 'mt',
            'support_decision': 'supported',
            'device_model': 'synthetic-device-not-a-claim',
            'os_version': 'Android 15',
            'device_tier': 'high',
            'language_pair': 'en->ru',
            'model_id': model_id,
            'runtime_id': runtime_id,
            'package_size_mb': 123.0,
            'quality_metric': 'synthetic_chrf',
            'quality_score': 0.75,
            'p95_translation_latency_ms': 900,
            'memory_peak_mb': 1024,
            'battery_delta_percent_30m': 8,
            'thermal_result': 'nominal',
            'legal_review_status': 'approved_for_distribution',
            'evidence_paths': ['telemetry.jsonl', 'device-metadata.json', 'battery-thermal-log.json', 'package-evidence.json', 'runtime-conversion-evidence.json', 'legal-review-evidence.json', 'model-manifest.json'],
        })
        counts = {'mt_result_emitted': 1, 'memory_sample': 1, 'battery_sample': 1, 'thermal_sample': 1}

    write_json(bundle_path, {
        'schema_version': 1,
        'bundle_id': f'synthetic-{result_type}-ready-bundle',
        'result_type': result_type,
        'expected_support_decision': 'supported',
        'benchmark_result_path': 'benchmark-result.json',
        'telemetry_log_path': 'telemetry.jsonl',
        'device_metadata_path': 'device-metadata.json',
        'battery_thermal_log_path': 'battery-thermal-log.json',
        'model_manifest_path': 'model-manifest.json',
        'offline_no_network': True,
        'telemetry_require_event_counts': counts,
    })
    return bundle_path


with tempfile.TemporaryDirectory() as tmp:
    tmp_path = Path(tmp)
    results_root = tmp_path / 'results'
    matrix_path = tmp_path / 'device-lab-matrix.ready.json'
    matrix = json.loads(Path('configs/device-lab-matrix.json').read_text())
    matrix['supportClaimsAllowed'] = True
    for tier in matrix['deviceTiers']:
        tier_name = tier['tier']
        tier['android']['sku'] = f'synthetic-android-{tier_name}'
        tier['android']['osVersion'] = 'Synthetic Android 16'
        tier['ios']['sku'] = f'synthetic-ios-{tier_name}'
        tier['ios']['osVersion'] = 'Synthetic iOS 19'
    write_json(matrix_path, matrix)

    asr_bundle = create_bundle(results_root, result_type='asr')
    mt_bundle = create_bundle(results_root, result_type='mt')

    subprocess.run(['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(asr_bundle), '--allow-support-claims'], check=True)
    subprocess.run(['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(mt_bundle), '--allow-support-claims'], check=True)

    output_md = tmp_path / 'proof-coverage.md'
    output_json = tmp_path / 'proof-coverage.json'
    proc = subprocess.run([
        'python3', 'scripts/generate_proof_coverage.py',
        '--results-root', str(results_root),
        '--device-matrix', str(matrix_path),
        '--output-md', str(output_md),
        '--output-json', str(output_json),
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
    print(proc.stdout)
    report = json.loads(output_json.read_text())
    assert report['aggregate_proof_coverage'] == 'READY_FOR_GOAL_REVIEW'
    assert report['g008_asr_proof_coverage'] == 'READY_FOR_GOAL_REVIEW'
    assert report['g010_mt_proof_coverage'] == 'READY_FOR_GOAL_REVIEW'
    assert report['bundle_count'] == 2
    assert report['asr']['proof_usable_rows'] == 1
    assert report['mt']['proof_usable_rows'] == 1
    assert report['asr']['supported_rows'] == 1
    assert report['mt']['supported_rows'] == 1
    assert report['blockers'] == []
    text = output_md.read_text()
    for token in [
        'Aggregate proof coverage:** READY_FOR_GOAL_REVIEW',
        'G008 ASR proof coverage:** READY_FOR_GOAL_REVIEW',
        'G010 MT proof coverage:** READY_FOR_GOAL_REVIEW',
        'None detected by this generated coverage gate; human release review is still required.',
    ]:
        assert token in text, token

print('Proof coverage transition validation: PASS')
