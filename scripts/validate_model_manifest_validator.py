#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import tempfile
from pathlib import Path

ASR_SAMPLE = Path('benchmarks/device-lab/samples/model-manifest.asr.internal.json')
MILMMT_SAMPLE = Path('benchmarks/device-lab/samples/model-manifest.milmmt.internal.json')

subprocess.run(['python3', 'scripts/validate_model_manifest.py', '--manifest', str(ASR_SAMPLE)], check=True)
subprocess.run(['python3', 'scripts/validate_model_manifest.py', '--manifest', str(MILMMT_SAMPLE)], check=True)

with tempfile.TemporaryDirectory() as tmp:
    tmp_path = Path(tmp)

    bad_sha = json.loads(ASR_SAMPLE.read_text())
    bad_sha['sha256'] = 'not-a-sha'
    bad_sha_path = tmp_path / 'bad-sha.json'
    bad_sha_path.write_text(json.dumps(bad_sha))
    proc = subprocess.run(['python3', 'scripts/validate_model_manifest.py', '--manifest', str(bad_sha_path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'sha256 must be 64 hex characters' not in proc.stdout:
        raise SystemExit('expected bad sha rejection')

    unapproved_distribution = json.loads(MILMMT_SAMPLE.read_text())
    unapproved_distribution['distribution_mode'] = 'first_run_download'
    unapproved_path = tmp_path / 'unapproved-distribution.json'
    unapproved_path.write_text(json.dumps(unapproved_distribution))
    proc = subprocess.run(['python3', 'scripts/validate_model_manifest.py', '--manifest', str(unapproved_path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'unapproved models cannot use external distribution_mode' not in proc.stdout:
        raise SystemExit('expected unapproved distribution rejection')

    approved_without_flag = json.loads(ASR_SAMPLE.read_text())
    approved_without_flag.update({
        'distribution_mode': 'first_run_download',
        'legal_review_status': 'approved_for_distribution',
        'commercial_restrictions_reviewed': True,
        'privacy_impact_reviewed': True,
        'app_store_policy_reviewed': True,
        'review_owner': 'legal@example.invalid',
        'review_date': '2026-01-01',
        'legal_review_evidence_path': str(tmp_path / 'legal-review-evidence.json'),
    })
    legal_evidence = {
        'schema_version': 1,
        'model_id': approved_without_flag['model_id'],
        'legal_review_status': 'approved_for_distribution',
        'distribution_mode': 'first_run_download',
        'reviewer': 'legal@example.invalid',
        'review_date': '2026-01-01',
        'artifact_source_url': approved_without_flag['artifact_source_url'],
        'license_url': approved_without_flag['license_url'],
        'commercial_restrictions_reviewed': True,
        'privacy_impact_reviewed': True,
        'app_store_policy_reviewed': True,
        'decision': 'approved_for_distribution',
        'sensitive_family_reviewed': False,
        'sensitive_family_notes': 'Not a sensitive-family ASR test fixture.',
    }
    (tmp_path / 'legal-review-evidence.json').write_text(json.dumps(legal_evidence))
    approved_path = tmp_path / 'approved-without-flag.json'
    approved_path.write_text(json.dumps(approved_without_flag))
    proc = subprocess.run(['python3', 'scripts/validate_model_manifest.py', '--manifest', str(approved_path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'require --allow-distribution' not in proc.stdout:
        raise SystemExit('expected approved distribution flag rejection')
    subprocess.run(['python3', 'scripts/validate_model_manifest.py', '--manifest', str(approved_path), '--allow-distribution'], check=True)

    missing_legal = dict(approved_without_flag)
    missing_legal['legal_review_evidence_path'] = str(tmp_path / 'missing-legal-review.json')
    missing_legal_path = tmp_path / 'missing-legal.json'
    missing_legal_path.write_text(json.dumps(missing_legal))
    proc = subprocess.run(['python3', 'scripts/validate_model_manifest.py', '--manifest', str(missing_legal_path), '--allow-distribution'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'requires legal review evidence file' not in proc.stdout:
        raise SystemExit('expected missing legal review evidence rejection')

    bad_legal = dict(legal_evidence)
    bad_legal['decision'] = 'internal_only'
    (tmp_path / 'bad-legal-review.json').write_text(json.dumps(bad_legal))
    bad_legal_manifest = dict(approved_without_flag)
    bad_legal_manifest['legal_review_evidence_path'] = str(tmp_path / 'bad-legal-review.json')
    bad_legal_path = tmp_path / 'bad-legal-manifest.json'
    bad_legal_path.write_text(json.dumps(bad_legal_manifest))
    proc = subprocess.run(['python3', 'scripts/validate_model_manifest.py', '--manifest', str(bad_legal_path), '--allow-distribution'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if proc.returncode == 0 or 'decision=approved_for_distribution' not in proc.stdout:
        raise SystemExit('expected bad legal review decision rejection')

print('Model manifest validator validation: PASS')
