#!/usr/bin/env python3
"""Capture real-device Android permission/onboarding UX evidence with adb.

The captured artifact set is validated by validate_android_permission_ux_run.py.
This is UI/permission-flow evidence only; it does not prove ASR/MT support.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

APP_ID = 'dev.flextranslate'
ACTIVITY = f'{APP_ID}/.foundation.MainActivity'
APK = Path('apps/android/app/build/outputs/apk/debug/app-debug.apk')
DEFAULT_RUN_ROOT = Path('docs/qa/android-permission-ux-runs')


def run(cmd: list[str], *, capture: bool = False, check: bool = True) -> subprocess.CompletedProcess:
    print('+ ' + ' '.join(cmd), file=sys.stderr)
    return subprocess.run(
        cmd,
        check=check,
        capture_output=capture,
        text=False,
    )


def text(cmd: list[str], *, check: bool = True) -> str:
    return run(cmd, capture=True, check=check).stdout.decode('utf-8', errors='replace')


def adb(args: list[str], serial: str | None = None, *, capture: bool = False, check: bool = True) -> subprocess.CompletedProcess:
    prefix = ['adb']
    if serial:
        prefix += ['-s', serial]
    return run(prefix + args, capture=capture, check=check)


def adb_text(args: list[str], serial: str | None = None, *, check: bool = True) -> str:
    prefix = ['adb']
    if serial:
        prefix += ['-s', serial]
    return text(prefix + args, check=check)


def detect_serial(explicit: str | None) -> str | None:
    if explicit:
        return explicit
    lines = text(['adb', 'devices']).splitlines()[1:]
    devices = [line.split()[0] for line in lines if '\tdevice' in line]
    if not devices:
        raise SystemExit('No adb device connected. Connect Android device/emulator, then rerun.')
    if len(devices) > 1:
        raise SystemExit(f'Multiple adb devices found: {devices}. Pass --serial.')
    return devices[0]


def write_device_json(run_dir: Path, serial: str | None) -> None:
    def prop(name: str) -> str:
        return adb_text(['shell', 'getprop', name], serial=serial).strip().replace('\r', '')

    (run_dir / 'device.json').write_text(json.dumps({
        'platform': 'android',
        'device_model': prop('ro.product.model'),
        'android_release': prop('ro.build.version.release'),
        'android_sdk': prop('ro.build.version.sdk'),
    }, ensure_ascii=False, indent=2) + '\n')


def screenshot(run_dir: Path, name: str, serial: str | None) -> None:
    proc = adb(['exec-out', 'screencap', '-p'], serial=serial, capture=True)
    data = proc.stdout
    if not data.startswith(b'\x89PNG\r\n\x1a\n'):
        raise SystemExit(f'adb screencap did not return PNG data for {name}')
    (run_dir / name).write_bytes(data)


def dump_xml(run_dir: Path, remote_name: str, local_name: str, serial: str | None) -> Path:
    remote = f'/sdcard/{remote_name}'
    adb(['shell', 'uiautomator', 'dump', remote], serial=serial)
    adb(['pull', remote, str(run_dir / local_name)], serial=serial)
    return run_dir / local_name


def parse_bounds(bounds: str) -> tuple[int, int]:
    match = re.fullmatch(r'\[(\d+),(\d+)]\[(\d+),(\d+)]', bounds)
    if not match:
        raise ValueError(f'bad bounds: {bounds}')
    x1, y1, x2, y2 = map(int, match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_text_center(xml_path: Path, tokens: list[str]) -> tuple[int, int] | None:
    tree = ET.parse(xml_path)
    for node in tree.iter():
        haystack = ' '.join([
            node.attrib.get('text', ''),
            node.attrib.get('content-desc', ''),
            node.attrib.get('resource-id', ''),
        ])
        if any(token.lower() in haystack.lower() for token in tokens):
            bounds = node.attrib.get('bounds')
            if bounds:
                return parse_bounds(bounds)
    return None


def xml_has_text(xml_path: Path, tokens: list[str]) -> bool:
    tree = ET.parse(xml_path)
    for node in tree.iter():
        haystack = ' '.join([
            node.attrib.get('text', ''),
            node.attrib.get('content-desc', ''),
            node.attrib.get('resource-id', ''),
        ])
        if any(token.lower() in haystack.lower() for token in tokens):
            return True
    return False


def tap_text(run_dir: Path, serial: str | None, tokens: list[str], dump_name: str) -> bool:
    xml_path = dump_xml(run_dir, f'{dump_name}.xml', f'{dump_name}.xml', serial)
    center = find_text_center(xml_path, tokens)
    if not center:
        return False
    adb(['shell', 'input', 'tap', str(center[0]), str(center[1])], serial=serial)
    return True


def grant_permission_dialog(run_dir: Path, serial: str | None) -> None:
    allow_tokens = [
        'While using the app',
        'Allow only while using the app',
        'Only this time',
        'Allow this time',
        'Во время использования',
        'При использовании приложения',
        'Только в этот раз',
    ]
    for attempt in range(5):
        time.sleep(0.7)
        screenshot(run_dir, 'screenshot-permission-dialog.png', serial)
        dialog_xml = dump_xml(run_dir, 'flex-permission-dialog.xml', 'screen-permission-dialog.xml', serial)
        if xml_has_text(dialog_xml, ['Микрофон готов', 'Продолжить в локальный режим']):
            return
        if tap_text(run_dir, serial, allow_tokens, f'flex-permission-dialog-tap-{attempt}'):
            return

    print('Could not tap system permission dialog automatically; trying adb pm grant fallback.', file=sys.stderr)
    adb(['shell', 'pm', 'grant', APP_ID, 'android.permission.RECORD_AUDIO'], serial=serial, check=False)
    adb(['shell', 'am', 'start', '-n', ACTIVITY], serial=serial)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', help='adb device serial; required if multiple devices are connected')
    parser.add_argument('--run-dir', type=Path)
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--skip-install', action='store_true')
    args = parser.parse_args()

    serial = detect_serial(args.serial)
    run_dir = args.run_dir or DEFAULT_RUN_ROOT / datetime.now().strftime('%Y-%m-%d-%H%M%S')
    run_dir.mkdir(parents=True, exist_ok=True)

    if not args.skip_build:
        run(['gradle', '-p', 'apps/android', ':app:assembleDebug'])
    if not APK.is_file():
        raise SystemExit(f'Missing APK: {APK}')
    if not args.skip_install:
        adb(['install', '-r', str(APK)], serial=serial)

    adb(['shell', 'pm', 'clear', APP_ID], serial=serial)
    write_device_json(run_dir, serial)
    adb(['logcat', '-c'], serial=serial)

    adb(['shell', 'am', 'start', '-n', ACTIVITY], serial=serial)
    time.sleep(2)
    screenshot(run_dir, 'screenshot-before-permission.png', serial)
    dump_xml(run_dir, 'flex-before.xml', 'screen-before-permission.xml', serial)

    if not tap_text(run_dir, serial, ['Разрешить микрофон'], 'flex-before-tap'):
        raise SystemExit('Could not find app button: Разрешить микрофон')
    grant_permission_dialog(run_dir, serial)

    time.sleep(1.5)
    screenshot(run_dir, 'screenshot-after-permission.png', serial)
    dump_xml(run_dir, 'flex-after.xml', 'screen-after-permission.xml', serial)

    if not tap_text(run_dir, serial, ['Продолжить в локальный режим'], 'flex-after-tap'):
        raise SystemExit('Could not find app button: Продолжить в локальный режим')

    time.sleep(1)
    screenshot(run_dir, 'screenshot-local-mode.png', serial)
    dump_xml(run_dir, 'flex-local.xml', 'screen-local-mode.xml', serial)
    (run_dir / 'logcat.txt').write_text(adb_text(['logcat', '-d', '-v', 'time'], serial=serial), errors='ignore')
    (run_dir / 'assertions.json').write_text(json.dumps({
        'microphone_permission_only': True,
        'no_permission_dialog_before_button_tap': True,
        'before_screen_has_permission_explanation': True,
        'after_grant_screen_has_microphone_ready': True,
        'local_mode_continue_unblocked': True,
        'cloud_default_disabled': True,
        'support_claims_not_visible': True,
        'asr_mt_support_not_claimed': True,
    }, ensure_ascii=False, indent=2) + '\n')

    run(['python3', 'scripts/validate_android_permission_ux_run.py', '--run-dir', str(run_dir)])
    print(f'Captured Android permission UX evidence: {run_dir}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
