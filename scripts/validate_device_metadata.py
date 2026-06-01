#!/usr/bin/env python3
"""Validate machine-readable device metadata for a device-lab evidence run."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

REQUIRED = [
    'schema_version',
    'platform',
    'device_model',
    'device_tier',
    'os_version',
    'physical_device',
    'airplane_mode_verified',
    'telemetry_export_enabled',
    'app_build_id',
]
PLACEHOLDER_RE = re.compile(r'^(?:|tbd|todo|replace(?:[_ -].*)?|unknown|n/a|null|synthetic-os)$', re.IGNORECASE)


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def placeholder(value: Any) -> bool:
    return not isinstance(value, str) or bool(PLACEHOLDER_RE.match(value.strip()))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--metadata', required=True)
    ap.add_argument('--expected-device-model')
    ap.add_argument('--expected-os-version')
    ap.add_argument('--expected-device-tier', choices=['low', 'mid', 'high'])
    ap.add_argument('--allow-placeholder', action='store_true', help='Fixture/planning mode only; never for supported evidence.')
    args = ap.parse_args()

    path = Path(args.metadata)
    meta = load_json(path)
    missing = [k for k in REQUIRED if k not in meta or meta[k] in (None, '')]
    if missing:
        raise SystemExit(f'{path}: missing required device metadata fields: {missing}')
    if meta['schema_version'] != 1:
        raise SystemExit(f'{path}: schema_version must be 1')
    if meta['platform'] not in {'ios', 'android'}:
        raise SystemExit(f'{path}: platform must be ios or android')
    if meta['device_tier'] not in {'low', 'mid', 'high'}:
        raise SystemExit(f'{path}: device_tier must be low/mid/high')
    for key in ['physical_device', 'airplane_mode_verified', 'telemetry_export_enabled']:
        if not isinstance(meta.get(key), bool):
            raise SystemExit(f'{path}: {key} must be boolean')
    if meta['physical_device'] is not True and not args.allow_placeholder:
        raise SystemExit(f'{path}: supported/strict metadata requires physical_device=true')
    if meta['airplane_mode_verified'] is not True and not args.allow_placeholder:
        raise SystemExit(f'{path}: strict metadata requires airplane_mode_verified=true')
    if meta['telemetry_export_enabled'] is not True:
        raise SystemExit(f'{path}: telemetry_export_enabled must be true')
    if args.expected_device_model and meta['device_model'] != args.expected_device_model:
        raise SystemExit(f'{path}: device_model mismatch: {meta["device_model"]!r} != {args.expected_device_model!r}')
    if args.expected_os_version and meta['os_version'] != args.expected_os_version:
        raise SystemExit(f'{path}: os_version mismatch: {meta["os_version"]!r} != {args.expected_os_version!r}')
    if args.expected_device_tier and meta['device_tier'] != args.expected_device_tier:
        raise SystemExit(f'{path}: device_tier mismatch: {meta["device_tier"]!r} != {args.expected_device_tier!r}')
    if not args.allow_placeholder:
        for key in ['device_model', 'os_version', 'app_build_id']:
            if placeholder(meta.get(key)):
                raise SystemExit(f'{path}: {key} must be concrete in strict mode')
        if 'device_sku' in meta and placeholder(meta.get('device_sku')):
            raise SystemExit(f'{path}: device_sku must be concrete in strict mode')

    print('Device metadata validation: PASS')
    print(f'device_model: {meta["device_model"]}')
    print(f'os_version: {meta["os_version"]}')
    print(f'device_tier: {meta["device_tier"]}')
    print(f'platform: {meta["platform"]}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
