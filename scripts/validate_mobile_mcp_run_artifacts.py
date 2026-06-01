#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

SCENARIO_CONFIG = Path('configs/mobile-mcp-scenarios.json')
DEFAULT_SAMPLE = Path('docs/qa/mobile-mcp-samples/android/launch-offline-local-first')
PNG_SIG = b'\x89PNG\r\n\x1a\n'
REQUIRED_FILES = [
    'mobile-mcp-session.json',
    'accessibility-snapshot.before.json',
    'accessibility-snapshot.after.json',
    'screenshot.before.png',
    'screenshot.after.png',
    'assertions.json',
]
FORBIDDEN_AUTHORITY = {
    'asr_wer_cer',
    'asr_rtf',
    'partial_latency',
    'translation_quality',
    'translation_latency',
    'memory_peak',
    'battery_delta',
    'thermal_result',
    'legal_review',
    'support_claims',
}


def fail(message: str) -> None:
    raise SystemExit(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        fail(f'{path}: invalid JSON: {exc}')
    if not isinstance(data, dict):
        fail(f'{path}: expected JSON object')
    return data


def load_config() -> tuple[dict[str, Any], dict[str, Any]]:
    data = load_json(SCENARIO_CONFIG)
    scenarios = {s['id']: s for s in data.get('scenarios', []) if isinstance(s, dict) and 'id' in s}
    if not scenarios:
        fail(f'{SCENARIO_CONFIG}: no scenarios configured')
    return data, scenarios


def assert_png(path: Path) -> None:
    raw = path.read_bytes()
    if not raw.startswith(PNG_SIG):
        fail(f'{path}: expected PNG artifact')


def assert_no_support_or_perf_claims(obj: Any, source: Path) -> None:
    if isinstance(obj, dict):
        for key, value in obj.items():
            lk = str(key).lower()
            if lk in {'support_decision', 'support_claim'} and str(value).lower() in {'supported', 'true', 'yes'}:
                fail(f'{source}: mobile-mcp artifact must not make support claims ({key}={value!r})')
            if lk == 'support_claims_allowed' and value is not False:
                fail(f'{source}: support_claims_allowed must be false')
            if lk in {'performance_claims_made', 'legal_claims_made', 'support_claims_made'} and value is not False:
                fail(f'{source}: {key} must be false')
            assert_no_support_or_perf_claims(value, source)
    elif isinstance(obj, list):
        for item in obj:
            assert_no_support_or_perf_claims(item, source)


def validate_run_dir(run_dir: Path, allow_sample: bool = False) -> dict[str, Any]:
    if not run_dir.is_dir():
        fail(f'{run_dir}: not a directory')
    missing = [name for name in REQUIRED_FILES if not (run_dir / name).is_file()]
    if missing:
        fail(f'{run_dir}: missing required mobile-mcp artifacts: {missing}')

    config, scenarios = load_config()
    allowed_tools = set(config.get('allowed_tools', []))
    authority_forbidden = set(config.get('authority', {}).get('not_authoritative_for', []))
    if not FORBIDDEN_AUTHORITY.issubset(authority_forbidden):
        fail(f'{SCENARIO_CONFIG}: authority guardrail incomplete')

    session = load_json(run_dir / 'mobile-mcp-session.json')
    before = load_json(run_dir / 'accessibility-snapshot.before.json')
    after = load_json(run_dir / 'accessibility-snapshot.after.json')
    assertions = load_json(run_dir / 'assertions.json')
    for path in [run_dir / 'screenshot.before.png', run_dir / 'screenshot.after.png']:
        assert_png(path)
    for source, obj in [
        (run_dir / 'mobile-mcp-session.json', session),
        (run_dir / 'accessibility-snapshot.before.json', before),
        (run_dir / 'accessibility-snapshot.after.json', after),
        (run_dir / 'assertions.json', assertions),
    ]:
        assert_no_support_or_perf_claims(obj, source)

    scenario_id = session.get('scenario_id')
    if scenario_id not in scenarios:
        fail(f'{run_dir}: unknown scenario_id {scenario_id!r}')
    scenario = scenarios[scenario_id]
    platform = session.get('platform')
    if platform not in {'android', 'ios'} or platform not in scenario.get('platforms', []):
        fail(f'{run_dir}: platform {platform!r} is not valid for scenario {scenario_id}')
    if session.get('schema_version') != 1 or assertions.get('schema_version') != 1:
        fail(f'{run_dir}: session/assertions schema_version must be 1')
    if before.get('scenario_id') != scenario_id or after.get('scenario_id') != scenario_id or assertions.get('scenario_id') != scenario_id:
        fail(f'{run_dir}: scenario_id mismatch across artifacts')
    if before.get('platform') != platform or after.get('platform') != platform or assertions.get('platform') != platform:
        fail(f'{run_dir}: platform mismatch across artifacts')

    server = session.get('mcp_server', {})
    if server.get('command') != 'npx' or '@mobilenext/mobile-mcp@latest' not in server.get('args', []):
        fail(f'{run_dir}: session must record mobile-mcp npx server command')
    if session.get('evidence_authority') != 'ui_flow_qa_only':
        fail(f'{run_dir}: evidence_authority must be ui_flow_qa_only')
    if session.get('support_claims_allowed') is not False:
        fail(f'{run_dir}: support_claims_allowed must be false')
    if not FORBIDDEN_AUTHORITY.issubset(set(session.get('non_authoritative_for', []))):
        fail(f'{run_dir}: session non_authoritative_for is incomplete')
    if not FORBIDDEN_AUTHORITY.issubset(set(assertions.get('non_authoritative_for', []))):
        fail(f'{run_dir}: assertions non_authoritative_for is incomplete')

    tools_used = session.get('tools_used', [])
    if not isinstance(tools_used, list) or not tools_used:
        fail(f'{run_dir}: tools_used must be a non-empty list')
    unknown_tools = sorted(set(tools_used) - allowed_tools)
    if unknown_tools:
        fail(f'{run_dir}: tools_used contains tools not allowed by scenario config: {unknown_tools}')
    scenario_tools = {step.get('tool') for step in scenario.get('steps', [])}
    if not set(tools_used).issubset(scenario_tools | {'mobile_save_screenshot'}):
        fail(f'{run_dir}: tools_used must match scenario steps')

    if not isinstance(before.get('elements'), list) or not before['elements']:
        fail(f'{run_dir}: before snapshot must contain UI elements')
    if not isinstance(after.get('elements'), list) or not after['elements']:
        fail(f'{run_dir}: after snapshot must contain UI elements')
    if assertions.get('result') not in {'pass', 'fail', 'blocked'}:
        fail(f'{run_dir}: assertions result must be pass/fail/blocked')
    assertion_values = assertions.get('assertions', {})
    if not isinstance(assertion_values, dict):
        fail(f'{run_dir}: assertions.assertions must be an object')
    required_assertions = set(scenario.get('required_assertions', []))
    missing_assertions = sorted(required_assertions - set(assertion_values))
    if missing_assertions:
        fail(f'{run_dir}: missing scenario required assertions: {missing_assertions}')
    if assertions.get('result') == 'pass':
        false_values = [key for key in required_assertions if assertion_values.get(key) is not True]
        if false_values:
            fail(f'{run_dir}: pass result requires required assertions true: {false_values}')

    if not allow_sample and 'sample' in str(run_dir).lower():
        fail(f'{run_dir}: sample fixtures require --allow-sample')

    return {'scenario_id': scenario_id, 'platform': platform, 'result': assertions.get('result')}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--run-dir', default=str(DEFAULT_SAMPLE))
    ap.add_argument('--allow-sample', action='store_true')
    args = ap.parse_args()
    summary = validate_run_dir(Path(args.run_dir), allow_sample=args.allow_sample)
    print('mobile-mcp run artifact validation: PASS')
    print(f'scenario_id: {summary["scenario_id"]}')
    print(f'platform: {summary["platform"]}')
    print(f'result: {summary["result"]}')
    print('authority: ui_flow_qa_only')
    print('support_claims_allowed: false')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
