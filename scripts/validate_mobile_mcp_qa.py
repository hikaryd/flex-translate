#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path

CONFIG = Path('configs/mobile-mcp-scenarios.json')
DOC = Path('docs/qa/mobile-mcp-automation.md')
RELEASE_DOC = Path('docs/qa/release-gate.md')

REQUIRED_TOOLS = {
    'mobile_list_available_devices',
    'mobile_install_app',
    'mobile_launch_app',
    'mobile_list_elements_on_screen',
    'mobile_save_screenshot',
}
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
REQUIRED_SCENARIOS = {
    'launch-offline-local-first',
    'microphone-permission-path',
    'missing-model-pack-state',
    'cloud-consent-explicit-opt-in',
    'unsupported-translation-pair',
}
REQUIRED_ARTIFACTS = {
    'mobile-mcp-session.json',
    'accessibility-snapshot.before.json',
    'accessibility-snapshot.after.json',
    'screenshot.before.png',
    'screenshot.after.png',
    'assertions.json',
}


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> int:
    if not CONFIG.is_file():
        fail(f'missing {CONFIG}')
    if not DOC.is_file():
        fail(f'missing {DOC}')
    data = json.loads(CONFIG.read_text())
    if data.get('schema_version') != 1:
        fail('mobile-mcp config schema_version must be 1')
    server = data.get('mcp_server', {})
    if server.get('command') != 'npx' or '@mobilenext/mobile-mcp@latest' not in server.get('args', []):
        fail('mobile-mcp server must use npx -y @mobilenext/mobile-mcp@latest')
    authority = data.get('authority', {})
    if authority.get('scope') != 'ui_flow_qa_only':
        fail('mobile-mcp authority scope must be ui_flow_qa_only')
    if authority.get('support_claims_allowed') is not False:
        fail('mobile-mcp must not allow support claims')
    if authority.get('native_evidence_required_for_perf') is not True:
        fail('mobile-mcp must require native evidence for performance')
    if not FORBIDDEN_AUTHORITY.issubset(set(authority.get('not_authoritative_for', []))):
        fail('mobile-mcp forbidden authority list is incomplete')
    artifacts = set(data.get('required_evidence_artifacts', []))
    if not REQUIRED_ARTIFACTS.issubset(artifacts):
        fail('mobile-mcp required evidence artifacts are incomplete')
    allowed = set(data.get('allowed_tools', []))
    if not REQUIRED_TOOLS.issubset(allowed):
        fail('mobile-mcp allowed tools are missing core automation tools')

    platforms = data.get('platform_targets', [])
    platform_names = {p.get('platform') for p in platforms}
    if platform_names != {'android', 'ios'}:
        fail('mobile-mcp platform targets must be exactly android and ios')
    for target in platforms:
        classes = set(target.get('device_classes', []))
        if target.get('platform') == 'android' and not {'real_device', 'emulator'}.issubset(classes):
            fail('android target must include real_device and emulator')
        if target.get('platform') == 'ios' and not {'real_device', 'simulator'}.issubset(classes):
            fail('ios target must include real_device and simulator')

    scenarios = data.get('scenarios', [])
    scenario_ids = {s.get('id') for s in scenarios}
    if not REQUIRED_SCENARIOS.issubset(scenario_ids):
        fail('mobile-mcp required scenario set is incomplete')
    for scenario in scenarios:
        sid = scenario.get('id')
        if set(scenario.get('platforms', [])) != {'android', 'ios'}:
            fail(f'{sid}: scenario must target both android and ios')
        if not scenario.get('purpose') or not scenario.get('preconditions'):
            fail(f'{sid}: scenario needs purpose and preconditions')
        steps = scenario.get('steps', [])
        if not steps:
            fail(f'{sid}: scenario needs steps')
        for step in steps:
            if step.get('tool') not in allowed:
                fail(f'{sid}: step uses tool not in allowed_tools: {step.get("tool")}')
            if not step.get('assertion'):
                fail(f'{sid}: step missing assertion')
        assertions = set(scenario.get('required_assertions', []))
        if not assertions:
            fail(f'{sid}: scenario needs required assertions')
        joined = ' '.join(assertions).lower()
        if 'support_claim' in joined and not any('not_claimed' in a or 'not_claim' in a or 'not_visible' in a for a in assertions):
            fail(f'{sid}: support-claim assertions must be negative/conservative')

    doc = DOC.read_text()
    for token in [
        'UI-flow evidence only',
        '@mobilenext/mobile-mcp@latest',
        'not ASR/MT benchmark evidence',
        'not battery/thermal proof',
        'not legal approval',
        'not a support-claim authority',
        'native telemetry',
        'validate_mobile_mcp_run_artifacts.py',
    ]:
        if token not in doc:
            fail(f'{DOC}: missing token {token!r}')
    release_doc = RELEASE_DOC.read_text() if RELEASE_DOC.is_file() else ''
    if 'validate_mobile_mcp_qa.py' not in release_doc or 'mobile-mcp' not in release_doc:
        fail(f'{RELEASE_DOC}: release gate must mention mobile-mcp validator')

    print('mobile-mcp QA harness validation: PASS')
    print(f'scenarios: {len(scenarios)}')
    print('authority: ui_flow_qa_only')
    print('support_claims_allowed: false')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
