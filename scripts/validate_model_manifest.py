#!/usr/bin/env python3
"""Validate model artifact/legal manifests for FlexTranslate device-lab proof.

A passing manifest means the model artifact metadata is structurally usable for
lab evidence. It does not approve support by itself. Distribution support is
only possible when this validator, benchmark evidence, telemetry evidence, and
release gate all agree.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

REQUIRED_FIELDS = [
    'schema_version',
    'model_id',
    'model_name',
    'model_version',
    'model_family',
    'artifact_format',
    'artifact_source_url',
    'license_url',
    'sha256',
    'artifact_size_mb',
    'package_evidence_path',
    'runtime_ids',
    'runtime_conversion_status',
    'runtime_conversion_evidence_path',
    'runtime_conversion_notes',
    'legal_review_evidence_path',
    'intended_use',
    'distribution_mode',
    'legal_review_status',
    'review_owner',
    'review_date',
    'commercial_restrictions_reviewed',
    'privacy_impact_reviewed',
    'app_store_policy_reviewed',
]
MODEL_FAMILIES = {'sherpa-onnx', 'silero-vad', 'whisper', 'milmmt', 'gemma-derived', 'dedicated-mt', 'commercial-sdk', 'other'}
ARTIFACT_FORMATS = {'onnx', 'gguf', 'mlc', 'litert', 'sdk', 'other'}
INTENDED_USES = {'asr', 'vad', 'mt', 'asr_baseline', 'mt_baseline'}
DISTRIBUTION_MODES = {'bundled', 'first_run_download', 'internal_lab_only', 'blocked'}
LEGAL_STATUSES = {'not_reviewed', 'approved_for_distribution', 'internal_only', 'blocked'}
RUNTIME_CONVERSION_STATUSES = {'native', 'converted_and_validated', 'not_attempted', 'blocked', 'not_applicable'}
SHA256_RE = re.compile(r'^[a-fA-F0-9]{64}$')
SENSITIVE_FAMILIES = {'milmmt', 'gemma-derived'}
PLACEHOLDER_RE = re.compile(r'^(?:|tbd|todo|replace(?:[_ -].*)?|unknown|n/a|null)$', re.IGNORECASE)


def resolve_manifest_path(source: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute() or path.is_file():
        return path
    source_relative = source.parent / path
    if source_relative.is_file():
        return source_relative
    return path


def require_non_empty_string(data: dict[str, Any], field: str, source: Path) -> None:
    if not isinstance(data.get(field), str) or not data[field].strip():
        raise SystemExit(f'{source}: {field} must be a non-empty string')


def validate_manifest(data: dict[str, Any], source: Path, allow_distribution: bool) -> None:
    missing = [field for field in REQUIRED_FIELDS if field not in data or data[field] is None]
    if missing:
        raise SystemExit(f'{source}: missing required manifest fields: {missing}')
    if data['schema_version'] != 1:
        raise SystemExit(f'{source}: schema_version must be 1')
    for field in ['model_id', 'model_name', 'model_version', 'artifact_source_url', 'license_url', 'package_evidence_path', 'runtime_conversion_evidence_path', 'runtime_conversion_notes', 'legal_review_evidence_path', 'review_owner', 'review_date']:
        require_non_empty_string(data, field, source)
    if data['model_family'] not in MODEL_FAMILIES:
        raise SystemExit(f'{source}: invalid model_family {data["model_family"]!r}')
    if data['artifact_format'] not in ARTIFACT_FORMATS:
        raise SystemExit(f'{source}: invalid artifact_format {data["artifact_format"]!r}')
    if not isinstance(data['artifact_size_mb'], (int, float)) or data['artifact_size_mb'] <= 0:
        raise SystemExit(f'{source}: artifact_size_mb must be > 0')
    if not isinstance(data['runtime_ids'], list) or not data['runtime_ids'] or not all(isinstance(x, str) and x.strip() for x in data['runtime_ids']):
        raise SystemExit(f'{source}: runtime_ids must be a non-empty string array')
    if data['runtime_conversion_status'] not in RUNTIME_CONVERSION_STATUSES:
        raise SystemExit(f'{source}: invalid runtime_conversion_status {data["runtime_conversion_status"]!r}')
    if data['intended_use'] not in INTENDED_USES:
        raise SystemExit(f'{source}: invalid intended_use {data["intended_use"]!r}')
    if data['distribution_mode'] not in DISTRIBUTION_MODES:
        raise SystemExit(f'{source}: invalid distribution_mode {data["distribution_mode"]!r}')
    if data['legal_review_status'] not in LEGAL_STATUSES:
        raise SystemExit(f'{source}: invalid legal_review_status {data["legal_review_status"]!r}')
    if not SHA256_RE.match(str(data['sha256'])):
        raise SystemExit(f'{source}: sha256 must be 64 hex characters')
    for field in ['commercial_restrictions_reviewed', 'privacy_impact_reviewed', 'app_store_policy_reviewed']:
        if not isinstance(data[field], bool):
            raise SystemExit(f'{source}: {field} must be boolean')

    if data['legal_review_status'] == 'approved_for_distribution':
        for field in ['commercial_restrictions_reviewed', 'privacy_impact_reviewed', 'app_store_policy_reviewed']:
            if data[field] is not True:
                raise SystemExit(f'{source}: approved_for_distribution requires {field}=true')
        if data['distribution_mode'] not in {'bundled', 'first_run_download'}:
            raise SystemExit(f'{source}: approved_for_distribution requires bundled or first_run_download distribution_mode')
        if not allow_distribution:
            raise SystemExit(f'{source}: approved distribution manifests require --allow-distribution in release/legal review context')

        package_path = resolve_manifest_path(source, data['package_evidence_path'])
        if not package_path.is_file():
            raise SystemExit(f'{source}: approved distribution requires package evidence file: {package_path}')
        package = json.loads(package_path.read_text())
        if not isinstance(package, dict):
            raise SystemExit(f'{package_path}: package evidence must be a JSON object')
        for field in ['schema_version', 'model_id', 'sha256', 'artifact_size_mb', 'package_size_mb', 'measurement_method', 'package_files']:
            if field not in package or package[field] in (None, ''):
                raise SystemExit(f'{package_path}: missing required package evidence field {field}')
        if package['schema_version'] != 1:
            raise SystemExit(f'{package_path}: schema_version must be 1')
        if package['model_id'] != data['model_id'] or package['sha256'] != data['sha256']:
            raise SystemExit(f'{package_path}: package evidence model_id/sha256 must match manifest')
        if float(package['artifact_size_mb']) != float(data['artifact_size_mb']):
            raise SystemExit(f'{package_path}: package artifact_size_mb must match manifest')
        if float(package['package_size_mb']) < float(data['artifact_size_mb']):
            raise SystemExit(f'{package_path}: package_size_mb must be >= artifact_size_mb')
        if PLACEHOLDER_RE.match(str(package['measurement_method']).strip()):
            raise SystemExit(f'{package_path}: approved package evidence requires concrete measurement_method')
        if not isinstance(package['package_files'], list) or not package['package_files']:
            raise SystemExit(f'{package_path}: package_files must be a non-empty list')

        conversion_path = resolve_manifest_path(source, data['runtime_conversion_evidence_path'])
        if not conversion_path.is_file():
            raise SystemExit(f'{source}: approved distribution requires runtime conversion evidence file: {conversion_path}')
        conversion = json.loads(conversion_path.read_text())
        if not isinstance(conversion, dict):
            raise SystemExit(f'{conversion_path}: runtime conversion evidence must be a JSON object')
        for field in ['schema_version', 'model_id', 'runtime_id', 'runtime_conversion_status', 'validation_status']:
            if field not in conversion or conversion[field] in (None, ''):
                raise SystemExit(f'{conversion_path}: missing required runtime conversion field {field}')
        if conversion['schema_version'] != 1:
            raise SystemExit(f'{conversion_path}: schema_version must be 1')
        if conversion['model_id'] != data['model_id']:
            raise SystemExit(f'{conversion_path}: conversion model_id must match manifest')
        if conversion['runtime_id'] not in data['runtime_ids']:
            raise SystemExit(f'{conversion_path}: conversion runtime_id must be listed in manifest runtime_ids')
        if conversion['runtime_conversion_status'] != data['runtime_conversion_status']:
            raise SystemExit(f'{conversion_path}: conversion status must match manifest runtime_conversion_status')
        if data['intended_use'] == 'mt' and data['runtime_conversion_status'] not in {'native', 'converted_and_validated'}:
            raise SystemExit(f'{source}: approved MT distribution requires native or converted_and_validated runtime_conversion_status')
        if data['runtime_conversion_status'] == 'converted_and_validated' and conversion['validation_status'] != 'validated':
            raise SystemExit(f'{conversion_path}: converted_and_validated requires validation_status=validated')

        legal_path = resolve_manifest_path(source, data['legal_review_evidence_path'])
        if not legal_path.is_file():
            raise SystemExit(f'{source}: approved distribution requires legal review evidence file: {legal_path}')
        legal = json.loads(legal_path.read_text())
        if not isinstance(legal, dict):
            raise SystemExit(f'{legal_path}: legal review evidence must be a JSON object')
        legal_required = [
            'schema_version',
            'model_id',
            'legal_review_status',
            'distribution_mode',
            'reviewer',
            'review_date',
            'artifact_source_url',
            'license_url',
            'commercial_restrictions_reviewed',
            'privacy_impact_reviewed',
            'app_store_policy_reviewed',
            'decision',
            'sensitive_family_reviewed',
            'sensitive_family_notes',
        ]
        missing_legal = [field for field in legal_required if field not in legal or legal[field] in (None, '')]
        if missing_legal:
            raise SystemExit(f'{legal_path}: missing required legal review evidence fields: {missing_legal}')
        if legal['schema_version'] != 1:
            raise SystemExit(f'{legal_path}: schema_version must be 1')
        for field in ['model_id', 'legal_review_status', 'distribution_mode', 'artifact_source_url', 'license_url']:
            if legal[field] != data[field]:
                raise SystemExit(f'{legal_path}: legal review {field} must match manifest')
        if legal['reviewer'] != data['review_owner'] or legal['review_date'] != data['review_date']:
            raise SystemExit(f'{legal_path}: reviewer/review_date must match manifest review owner/date')
        if legal['decision'] != 'approved_for_distribution':
            raise SystemExit(f'{legal_path}: approved distribution requires decision=approved_for_distribution')
        for field in ['commercial_restrictions_reviewed', 'privacy_impact_reviewed', 'app_store_policy_reviewed']:
            if legal[field] is not True:
                raise SystemExit(f'{legal_path}: approved distribution requires legal evidence {field}=true')
        if data['model_family'] in SENSITIVE_FAMILIES:
            if legal['sensitive_family_reviewed'] is not True:
                raise SystemExit(f'{legal_path}: sensitive model family requires sensitive_family_reviewed=true')
            if PLACEHOLDER_RE.match(str(legal['sensitive_family_notes']).strip()):
                raise SystemExit(f'{legal_path}: sensitive model family requires concrete sensitive_family_notes')

    if data['legal_review_status'] in {'not_reviewed', 'internal_only'} and data['distribution_mode'] in {'bundled', 'first_run_download'}:
        raise SystemExit(f'{source}: unapproved models cannot use external distribution_mode {data["distribution_mode"]!r}')

    if data['legal_review_status'] == 'blocked' and data['distribution_mode'] != 'blocked':
        raise SystemExit(f'{source}: blocked legal status requires distribution_mode=blocked')

    if data['model_family'] in SENSITIVE_FAMILIES and data['legal_review_status'] != 'approved_for_distribution':
        if data['distribution_mode'] != 'internal_lab_only':
            raise SystemExit(f'{source}: MiLMMT/Gemma-derived artifacts must remain internal_lab_only unless approved_for_distribution')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--allow-distribution', action='store_true')
    args = ap.parse_args()

    source = Path(args.manifest)
    data = json.loads(source.read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{source}: manifest must be a JSON object')
    validate_manifest(data, source, args.allow_distribution)
    print('Model manifest validation: PASS')
    print(f'model_id: {data["model_id"]}')
    print(f'legal_review_status: {data["legal_review_status"]}')
    print(f'distribution_mode: {data["distribution_mode"]}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
