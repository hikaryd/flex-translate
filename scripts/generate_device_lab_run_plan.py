#!/usr/bin/env python3
"""Generate the concrete device-lab run plan for G008/G010 retries."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

DEFAULT_OUTPUT = Path('docs/benchmarks/device-lab-run-plan.generated.md')


def load_json(path: str | Path) -> dict[str, Any]:
    data = json.loads(Path(path).read_text())
    if not isinstance(data, dict):
        raise SystemExit(f'{path}: expected JSON object')
    return data


def slug(value: str) -> str:
    out = ''.join(ch.lower() if ch.isalnum() else '-' for ch in value)
    while '--' in out:
        out = out.replace('--', '-')
    return out.strip('-') or 'unknown'


def tier_platform_rows(matrix: dict[str, Any]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for tier in matrix.get('deviceTiers', []):
        tier_name = str(tier.get('tier', 'unknown'))
        for platform in ['android', 'ios']:
            platform_data = tier.get(platform, {})
            rows.append({
                'tier': tier_name,
                'platform': platform,
                'sku': str(platform_data.get('sku', 'TBD')),
                'os_version': str(platform_data.get('osVersion', 'TBD')),
                'class_or_min_ram': str(platform_data.get('class') or platform_data.get('minRamGb') or 'TBD'),
            })
    return rows


def device_rows_for_candidate(matrix: dict[str, Any], tiers: list[str]) -> list[dict[str, str]]:
    tier_set = set(tiers)
    return [row for row in tier_platform_rows(matrix) if row['tier'] in tier_set]


def has_tbd_devices(matrix: dict[str, Any]) -> bool:
    for row in tier_platform_rows(matrix):
        if row['sku'] == 'TBD' or row['os_version'] == 'TBD':
            return True
    return False


def asr_rows(matrix: dict[str, Any], asr: dict[str, Any]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for candidate in asr.get('candidates', []):
        tiers = [str(t) for t in candidate.get('tiers', [])]
        for device in device_rows_for_candidate(matrix, tiers):
            output_dir = f'benchmarks/device-lab/results/REPLACE_DATE/{slug(device["platform"] + "-" + device["sku"])}/{candidate["id"]}'
            rows.append({
                'goal': 'G008',
                'type': 'asr',
                'candidate': str(candidate['id']),
                'language': str(candidate.get('language', 'REPLACE_LANGUAGE')),
                'tier': device['tier'],
                'platform': device['platform'],
                'sku': device['sku'],
                'os_version': device['os_version'],
                'runtime': str(candidate.get('runtime', 'sherpa-onnx')),
                'output_dir': output_dir,
                'template_command': ' '.join([
                    'python3 scripts/create_device_lab_run_template.py',
                    '--type asr',
                    f'--candidate-id {candidate["id"]}',
                    f'--device-model "{device["sku"]}"',
                    f'--os-version "{device["os_version"]}"',
                    f'--device-tier {device["tier"]}',
                    f'--language {candidate.get("language", "REPLACE_LANGUAGE")}',
                    f'--output-dir {output_dir}',
                ]),
            })
    return rows


def mt_rows(matrix: dict[str, Any], mt: dict[str, Any]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for candidate in mt.get('candidates', []):
        tiers = [str(t) for t in candidate.get('targetTiers', [])]
        pairs = [str(p) for p in candidate.get('languagePairs', [])]
        runtimes = [str(r) for r in candidate.get('runtimeCandidates', [])]
        for device in device_rows_for_candidate(matrix, tiers):
            for pair in pairs:
                for runtime in runtimes:
                    output_dir = f'benchmarks/device-lab/results/REPLACE_DATE/{slug(device["platform"] + "-" + device["sku"])}/{candidate["id"]}-{slug(pair)}-{slug(runtime)}'
                    rows.append({
                        'goal': 'G010',
                        'type': 'mt',
                        'candidate': str(candidate['id']),
                        'language_pair': pair,
                        'tier': device['tier'],
                        'platform': device['platform'],
                        'sku': device['sku'],
                        'os_version': device['os_version'],
                        'runtime': runtime,
                        'output_dir': output_dir,
                        'template_command': ' '.join([
                            'python3 scripts/create_device_lab_run_template.py',
                            '--type mt',
                            f'--candidate-id {candidate["id"]}',
                            f'--runtime-id "{runtime}"',
                            f'--device-model "{device["sku"]}"',
                            f'--os-version "{device["os_version"]}"',
                            f'--device-tier {device["tier"]}',
                            f'--language-pair "{pair}"',
                            f'--output-dir {output_dir}',
                        ]),
                    })
    return rows


def render_table(headers: list[str], rows: list[dict[str, str]], keys: list[str], limit: int | None = None) -> list[str]:
    shown = rows if limit is None else rows[:limit]
    lines = ['| ' + ' | '.join(headers) + ' |', '| ' + ' | '.join(['---'] * len(headers)) + ' |']
    for row in shown:
        lines.append('| ' + ' | '.join(str(row.get(key, '')) for key in keys) + ' |')
    if limit is not None and len(rows) > limit:
        lines.append(f'| … | … | … | … | … | … | … | … | … |')
    return lines


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--output', default=str(DEFAULT_OUTPUT))
    args = ap.parse_args()

    matrix = load_json('configs/device-lab-matrix.json')
    asr = load_json('configs/asr-candidates.json')
    mt = load_json('configs/mt-candidates.json')
    asr_plan = asr_rows(matrix, asr)
    mt_plan = mt_rows(matrix, mt)
    readiness = 'BLOCKED' if has_tbd_devices(matrix) else 'READY_FOR_LAB_EXECUTION'

    lines: list[str] = [
        '# Device Lab Run Plan',
        '',
        'Generated from `configs/device-lab-matrix.json`, `configs/asr-candidates.json`, and `configs/mt-candidates.json`.',
        '',
        f'**Execution readiness:** {readiness}',
        f'**Support claims allowed:** {str(matrix.get("supportClaimsAllowed", False)).lower()}',
        '',
        'This plan is not evidence and creates no ASR/MT support claim. It is the concrete command plan for collecting real G008/G010 evidence.',
        '',
        '## External blockers before execution',
        '',
    ]
    if readiness == 'BLOCKED':
        lines.extend([
            '- Device SKUs and/or OS versions in `configs/device-lab-matrix.json` are still `TBD`.',
            '- Replace every `TBD` with a real iOS/Android lab device before treating generated commands as executable.',
        ])
    else:
        lines.append('- None detected in the device-lab matrix; verify devices are physically available before running.')
    lines.extend([
        '- Real model artifacts, checksums, package sizes, battery/thermal logs, telemetry JSONL, and legal review are still required.',
        '',
        '## ASR G008 run matrix',
        '',
        f'- Planned ASR runs: {len(asr_plan)}',
        '',
    ])
    lines.extend(render_table(
        ['Candidate', 'Language', 'Tier', 'Platform', 'SKU', 'OS', 'Runtime', 'Output dir'],
        asr_plan,
        ['candidate', 'language', 'tier', 'platform', 'sku', 'os_version', 'runtime', 'output_dir'],
    ))
    lines.extend(['', '### ASR template commands', ''])
    for row in asr_plan:
        lines.extend(['```sh', row['template_command'], '```', ''])

    lines.extend([
        '## MT G010 run matrix',
        '',
        f'- Planned MT runtime/pair runs: {len(mt_plan)}',
        '',
    ])
    lines.extend(render_table(
        ['Candidate', 'Pair', 'Tier', 'Platform', 'SKU', 'OS', 'Runtime', 'Output dir'],
        mt_plan,
        ['candidate', 'language_pair', 'tier', 'platform', 'sku', 'os_version', 'runtime', 'output_dir'],
        limit=40,
    ))
    lines.extend(['', '### MT template command pattern', ''])
    for row in mt_plan[:20]:
        lines.extend(['```sh', row['template_command'], '```', ''])
    if len(mt_plan) > 20:
        lines.append(f'Additional MT commands omitted from display: {len(mt_plan) - 20}. Regenerate or inspect this script output model if a full per-command list is needed.')
        lines.append('')

    lines.extend([
        '## Required post-run validation',
        '',
        'After filling and renaming template files to real evidence files, run:',
        '',
        '```sh',
        'python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/REPLACE_DATE',
        'python3 scripts/check_release_gate.py',
        '```',
        '',
        'The release gate must remain blocked until G008 and G010 are checkpointed with real validated evidence and G012 final QA/review passes.',
        '',
    ])

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(lines))
    print(f'wrote: {output}')
    print(f'execution_readiness: {readiness}')
    print(f'asr_runs: {len(asr_plan)}')
    print(f'mt_runs: {len(mt_plan)}')
    print(f'support_claims_allowed: {str(matrix.get("supportClaimsAllowed", False)).lower()}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
