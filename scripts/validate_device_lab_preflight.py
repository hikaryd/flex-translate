#!/usr/bin/env python3
"""Conservative preflight for real G008/G010 device-lab execution.

This is not benchmark evidence. It tells a lab runner whether the local
machine/environment is ready to collect real ASR/MT proof artifacts.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

DEFAULT_RESULTS_ROOT = Path('benchmarks/device-lab/results')
DEFAULT_MODEL_ROOT = Path('benchmarks/device-lab/model-artifacts')
DEFAULT_MATRIX = Path('configs/device-lab-matrix.json')
MATRIX_VALIDATOR = Path('scripts/validate_device_lab_matrix.py')
MODEL_MANIFEST_VALIDATOR = Path('scripts/validate_model_manifest.py')


def run_capture(cmd: list[str], *, timeout: int = 8) -> tuple[int, str]:
    try:
        proc = subprocess.run(
            cmd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
    except FileNotFoundError:
        return 127, f'missing executable: {cmd[0]}'
    except subprocess.TimeoutExpired as exc:
        out = exc.stdout if isinstance(exc.stdout, str) else ''
        return 124, f'timeout after {timeout}s\n{out}'.strip()
    return proc.returncode, proc.stdout.strip()


def add_blocker(report: dict[str, Any], code: str, detail: str, action: str) -> None:
    report['blockers'].append({'code': code, 'detail': detail, 'action': action})


def command_available(name: str) -> bool:
    return shutil.which(name) is not None


def validate_matrix(report: dict[str, Any], matrix_path: Path) -> None:
    if not matrix_path.is_file():
        add_blocker(report, 'device_matrix_missing', f'{matrix_path} is missing', 'Create configs/device-lab-matrix.json before lab execution.')
        report['checks']['device_matrix'] = {'status': 'missing'}
        return
    if not MATRIX_VALIDATOR.is_file():
        add_blocker(report, 'device_matrix_validator_missing', f'{MATRIX_VALIDATOR} is missing', 'Restore the strict matrix validator.')
        report['checks']['device_matrix'] = {'status': 'validator_missing'}
        return
    code, out = run_capture(['python3', str(MATRIX_VALIDATOR), '--matrix', str(matrix_path), '--json'])
    try:
        matrix_report = json.loads(out)
    except json.JSONDecodeError:
        matrix_report = {'validation_status': 'UNKNOWN', 'raw_output': out}
    report['checks']['device_matrix'] = matrix_report
    if code != 0 or matrix_report.get('validation_status') != 'PASS':
        missing = matrix_report.get('missing_device_fields', [])
        detail = f'strict matrix validation is not PASS; missing={missing}; errors={matrix_report.get("errors", [])}'
        add_blocker(report, 'device_matrix_not_ready', detail, 'Fill exact iOS/Android SKUs and OS versions, then run validate_device_lab_matrix.py without --allow-tbd.')


def validate_tooling(report: dict[str, Any]) -> None:
    required = ['python3']
    optional_required_for_lab = ['adb', 'npx']
    ios_required = ['xcrun', 'xcodebuild']
    tooling: dict[str, Any] = {}
    for tool in required + optional_required_for_lab + ios_required:
        tooling[tool] = {'available': command_available(tool), 'path': shutil.which(tool)}
    report['checks']['tooling'] = tooling
    for tool in required:
        if not tooling[tool]['available']:
            add_blocker(report, f'{tool}_missing', f'{tool} is not on PATH', f'Install {tool} for validator and benchmark execution.')
    for tool in optional_required_for_lab:
        if not tooling[tool]['available']:
            add_blocker(report, f'{tool}_missing', f'{tool} is not on PATH', f'Install {tool}; Android/mobile-mcp lab execution needs it.')
    for tool in ios_required:
        if not tooling[tool]['available']:
            add_blocker(report, f'{tool}_missing', f'{tool} is not on PATH', 'Install/select Xcode command-line tools for iOS lab execution.')


def parse_adb_devices(output: str) -> list[str]:
    devices: list[str] = []
    for line in output.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == 'device':
            devices.append(parts[0])
    return devices


def validate_devices(report: dict[str, Any], require_connected_devices: bool) -> None:
    devices: dict[str, Any] = {}
    if command_available('adb'):
        code, out = run_capture(['adb', 'devices'], timeout=8)
        android_devices = parse_adb_devices(out) if code == 0 else []
        devices['android'] = {'command_status': code, 'connected_count': len(android_devices), 'devices': android_devices, 'raw': out[:1200]}
    else:
        devices['android'] = {'command_status': 127, 'connected_count': 0, 'devices': []}

    if command_available('xcrun'):
        code, out = run_capture(['xcrun', 'xctrace', 'list', 'devices'], timeout=12)
        # Conservative: xctrace output may include many simulators; require_connected_devices
        # still needs the lab runner to verify physical devices in the report.
        lines = [line for line in out.splitlines() if line.strip()]
        devices['ios'] = {'command_status': code, 'listed_count': max(0, len(lines) - 1), 'raw': out[:1600]}
    else:
        devices['ios'] = {'command_status': 127, 'listed_count': 0}
    report['checks']['connected_devices'] = devices

    if require_connected_devices:
        if devices['android'].get('connected_count', 0) == 0:
            add_blocker(report, 'android_device_not_connected', 'adb reports no connected Android device in device state', 'Connect/unlock target Android device with USB debugging before G008/G010 runs.')
        if devices['ios'].get('listed_count', 0) == 0:
            add_blocker(report, 'ios_device_not_listed', 'xcrun xctrace lists no iOS device/simulator entries', 'Connect/select target iOS device before iOS G008/G010 runs.')


def validate_model_artifacts(report: dict[str, Any], model_root: Path, require_model_artifacts: bool) -> None:
    manifests = sorted(p for p in model_root.rglob('model-manifest.json')) if model_root.exists() else []
    report['checks']['model_artifacts'] = {
        'root': str(model_root),
        'root_exists': model_root.exists(),
        'manifest_count': len(manifests),
        'manifest_paths': [str(p) for p in manifests],
    }
    if not model_root.exists() or not manifests:
        if require_model_artifacts:
            add_blocker(report, 'model_artifacts_missing', f'no model-manifest.json files found under {model_root}', 'Place real ASR/MT model artifacts plus model-manifest.json/package/runtime/legal evidence under the lab model artifact root.')
        return
    if not MODEL_MANIFEST_VALIDATOR.is_file():
        add_blocker(report, 'model_manifest_validator_missing', f'{MODEL_MANIFEST_VALIDATOR} is missing', 'Restore model manifest validation before lab execution.')
        return
    validations = []
    for manifest in manifests:
        code, out = run_capture(['python3', str(MODEL_MANIFEST_VALIDATOR), '--manifest', str(manifest)], timeout=10)
        validations.append({'path': str(manifest), 'exit_code': code, 'output': out[:1200]})
        if code != 0:
            add_blocker(report, 'model_manifest_invalid', f'{manifest} failed validation', 'Fix model manifest/package/runtime/legal evidence before running benchmarks.')
    report['checks']['model_artifacts']['validations'] = validations


def validate_output_root(report: dict[str, Any], results_root: Path) -> None:
    results_root.mkdir(parents=True, exist_ok=True)
    marker = results_root / '.gitkeep'
    marker.touch(exist_ok=True)
    writable = True
    probe = results_root / '.preflight-write-test.tmp'
    try:
        probe.write_text('ok')
        probe.unlink()
    except OSError:
        writable = False
    existing_bundles = sorted(str(p) for p in results_root.rglob('*.bundle.json') if '.template.' not in p.name)
    report['checks']['evidence_output_root'] = {
        'root': str(results_root),
        'exists': results_root.exists(),
        'writable': writable,
        'existing_bundle_count': len(existing_bundles),
        'existing_bundle_paths': existing_bundles,
    }
    if not writable:
        add_blocker(report, 'evidence_root_not_writable', f'{results_root} is not writable', 'Fix permissions or choose a writable --results-root for lab evidence.')


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    report: dict[str, Any] = {
        'schema_version': 1,
        'preflight_authority': 'execution_readiness_only_not_support_evidence',
        'support_claims_allowed': False,
        'status': 'UNKNOWN',
        'blockers': [],
        'checks': {},
        'non_evidence_notice': 'Passing this preflight only permits real lab execution; it is not ASR/MT benchmark, legal, battery/thermal, or support-claim proof.',
    }
    validate_matrix(report, Path(args.matrix))
    validate_tooling(report)
    validate_devices(report, require_connected_devices=args.require_connected_devices)
    validate_model_artifacts(report, Path(args.model_artifacts_root), require_model_artifacts=args.require_model_artifacts)
    validate_output_root(report, Path(args.results_root))
    report['status'] = 'READY_FOR_REAL_DEVICE_LAB_EXECUTION' if not report['blockers'] else 'BLOCKED'
    return report


def print_text(report: dict[str, Any]) -> None:
    print('Device-lab execution preflight:', report['status'])
    print('authority:', report['preflight_authority'])
    print('support_claims_allowed:', str(report['support_claims_allowed']).lower())
    print('blocker_count:', len(report['blockers']))
    for blocker in report['blockers']:
        print(f"blocker: {blocker['code']} — {blocker['detail']}")
        print(f"  action: {blocker['action']}")
    print(report['non_evidence_notice'])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--matrix', default=str(DEFAULT_MATRIX))
    ap.add_argument('--results-root', default=str(DEFAULT_RESULTS_ROOT))
    ap.add_argument('--model-artifacts-root', default=str(DEFAULT_MODEL_ROOT))
    ap.add_argument('--require-connected-devices', action='store_true', default=True)
    ap.add_argument('--no-require-connected-devices', dest='require_connected_devices', action='store_false')
    ap.add_argument('--require-model-artifacts', action='store_true', default=True)
    ap.add_argument('--no-require-model-artifacts', dest='require_model_artifacts', action='store_false')
    ap.add_argument('--allow-missing', action='store_true', help='Return 0 while still reporting BLOCKED; use only for conservative repository validation.')
    ap.add_argument('--expect-blocked', action='store_true', help='Fail if current preflight unexpectedly becomes ready.')
    ap.add_argument('--json', action='store_true')
    args = ap.parse_args()

    report = build_report(args)
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print_text(report)

    if args.expect_blocked and report['status'] != 'BLOCKED':
        print('expected BLOCKED preflight state, got READY', file=sys.stderr)
        return 3
    if report['status'] == 'BLOCKED' and not args.allow_missing:
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
