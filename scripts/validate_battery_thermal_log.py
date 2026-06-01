#!/usr/bin/env python3
"""Validate battery/thermal evidence for a device-lab benchmark run."""
from __future__ import annotations

import argparse
import json
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

REQUIRED = [
    'schema_version',
    'session_id',
    'device_model',
    'os_version',
    'duration_minutes',
    'battery_start_percent',
    'battery_end_percent',
    'battery_delta_percent_30m',
    'thermal_result',
    'samples',
]
THERMAL_RESULTS = {'nominal', 'fair', 'serious_recovered', 'critical', 'not_measured'}


def load_json(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def as_decimal(value: Any, *, field: str, path: Path) -> Decimal:
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise SystemExit(f'{path}: {field} must be numeric') from exc


def close_enough(a: Decimal, b: Decimal) -> bool:
    return abs(a - b) <= Decimal('0.01')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--log', required=True)
    ap.add_argument('--expected-device-model')
    ap.add_argument('--expected-os-version')
    ap.add_argument('--expected-battery-delta-percent-30m')
    ap.add_argument('--expected-thermal-result')
    ap.add_argument('--allow-not-measured', action='store_true', help='Fixture/planning mode only; never for supported evidence.')
    args = ap.parse_args()

    path = Path(args.log)
    log = load_json(path)
    missing = [k for k in REQUIRED if k not in log or log[k] in (None, '')]
    if missing:
        raise SystemExit(f'{path}: missing required battery/thermal fields: {missing}')
    if log['schema_version'] != 1:
        raise SystemExit(f'{path}: schema_version must be 1')
    if args.expected_device_model and log['device_model'] != args.expected_device_model:
        raise SystemExit(f'{path}: device_model mismatch: {log["device_model"]!r} != {args.expected_device_model!r}')
    if args.expected_os_version and log['os_version'] != args.expected_os_version:
        raise SystemExit(f'{path}: os_version mismatch: {log["os_version"]!r} != {args.expected_os_version!r}')
    thermal = log['thermal_result']
    if thermal not in THERMAL_RESULTS:
        raise SystemExit(f'{path}: thermal_result must be one of {sorted(THERMAL_RESULTS)}')
    if thermal == 'not_measured' and not args.allow_not_measured:
        raise SystemExit(f'{path}: strict/support evidence cannot use thermal_result=not_measured')
    if args.expected_thermal_result and thermal != args.expected_thermal_result:
        raise SystemExit(f'{path}: thermal_result mismatch: {thermal!r} != {args.expected_thermal_result!r}')

    duration = as_decimal(log['duration_minutes'], field='duration_minutes', path=path)
    start = as_decimal(log['battery_start_percent'], field='battery_start_percent', path=path)
    end = as_decimal(log['battery_end_percent'], field='battery_end_percent', path=path)
    delta = as_decimal(log['battery_delta_percent_30m'], field='battery_delta_percent_30m', path=path)
    if duration <= 0:
        raise SystemExit(f'{path}: duration_minutes must be positive')
    if not (0 <= start <= 100 and 0 <= end <= 100):
        raise SystemExit(f'{path}: battery percentages must be between 0 and 100')
    if delta < 0:
        raise SystemExit(f'{path}: battery_delta_percent_30m must be non-negative')
    if args.expected_battery_delta_percent_30m:
        expected = as_decimal(args.expected_battery_delta_percent_30m, field='expected-battery-delta-percent-30m', path=path)
        if not close_enough(delta, expected):
            raise SystemExit(f'{path}: battery_delta_percent_30m mismatch: {delta} != {expected}')
    samples = log['samples']
    if not isinstance(samples, list) or not samples:
        raise SystemExit(f'{path}: samples must be a non-empty list')
    for index, sample in enumerate(samples):
        if not isinstance(sample, dict):
            raise SystemExit(f'{path}: sample {index} must be object')
        for key in ['minute', 'battery_percent', 'thermal_state']:
            if key not in sample:
                raise SystemExit(f'{path}: sample {index} missing {key}')

    print('Battery/thermal log validation: PASS')
    print(f'device_model: {log["device_model"]}')
    print(f'battery_delta_percent_30m: {log["battery_delta_percent_30m"]}')
    print(f'thermal_result: {thermal}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
