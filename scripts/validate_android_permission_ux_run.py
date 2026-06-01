#!/usr/bin/env python3
"""Validate real-device Android permission/onboarding UX evidence.

This is UI/launch evidence only. It does not prove ASR/MT benchmark support.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

APP_ID = 'dev.flextranslate'

REQUIRED = [
    'device.json',
    'screen-before-permission.xml',
    'screen-after-permission.xml',
    'screen-local-mode.xml',
    'screenshot-before-permission.png',
    'screenshot-after-permission.png',
    'screenshot-local-mode.png',
    'logcat.txt',
    'assertions.json',
]


def load_json(path: Path) -> dict:
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def require_text(path: Path, tokens: list[str]) -> None:
    text = path.read_text(errors='ignore')
    for token in tokens:
        if token not in text:
            raise SystemExit(f'{path}: missing token {token!r}')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--run-dir', required=True)
    args = ap.parse_args()
    run_dir = Path(args.run_dir)
    if not run_dir.is_dir():
        raise SystemExit(f'missing run dir: {run_dir}')
    missing = [name for name in REQUIRED if not (run_dir / name).is_file()]
    if missing:
        raise SystemExit(f'missing required artifacts: {missing}')

    device = load_json(run_dir / 'device.json')
    assertions = load_json(run_dir / 'assertions.json')
    for key in ['platform', 'device_model', 'android_release', 'android_sdk']:
        if not device.get(key):
            raise SystemExit(f'device.json missing {key}')
    if device.get('platform') != 'android':
        raise SystemExit('device.json platform must be android')

    expected_assertions = {
        'microphone_permission_only': True,
        'no_permission_dialog_before_button_tap': True,
        'before_screen_has_permission_explanation': True,
        'after_grant_screen_has_microphone_ready': True,
        'local_mode_continue_unblocked': True,
        'cloud_default_disabled': True,
        'support_claims_not_visible': True,
        'asr_mt_support_not_claimed': True,
    }
    for key, expected in expected_assertions.items():
        if assertions.get(key) is not expected:
            raise SystemExit(f'assertions.json expected {key}={expected!r}')

    require_text(run_dir / 'screen-before-permission.xml', ['Нужен доступ к микрофону', 'Разрешить микрофон'])
    require_text(run_dir / 'screen-after-permission.xml', ['Микрофон готов', 'Продолжить в локальный режим', 'Cloud disabled by default'])
    require_text(run_dir / 'screen-local-mode.xml', ['Локальный режим открыт', 'приложение не застряло после permission', 'ASR support пока не заявлен'])
    logcat = (run_dir / 'logcat.txt').read_text(errors='ignore').lower()
    for line in logcat.splitlines():
        if APP_ID not in line:
            continue
        for bad in ['fatal exception', 'permission denial']:
            if bad in line:
                raise SystemExit(f'logcat contains app-related {bad!r}: {line[:240]}')

    for png in ['screenshot-before-permission.png', 'screenshot-after-permission.png', 'screenshot-local-mode.png']:
        data = (run_dir / png).read_bytes()
        if not data:
            raise SystemExit(f'{png}: empty screenshot')
        if not data.startswith(b'\x89PNG\r\n\x1a\n'):
            raise SystemExit(f'{png}: expected PNG screenshot')

    print('Android permission UX run validation: PASS')
    print(f'run_dir: {run_dir}')
    print(f'device: {device.get("device_model")} Android {device.get("android_release")} SDK {device.get("android_sdk")}')
    print('authority: ui_permission_flow_only_not_asr_mt_support')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
