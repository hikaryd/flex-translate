#!/usr/bin/env python3
"""Generate support matrix docs from device-lab benchmark evidence.

This script is deliberately conservative: only benchmark result JSON files with
`support_decision == supported` become supported rows. Mock/not_claimed samples
are shown as non-support evidence and never upgraded.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def load_results(paths: list[Path]) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for root in paths:
        if root.is_file() and root.suffix == '.json':
            results.append(json.loads(root.read_text()))
        elif root.is_dir():
            for path in sorted(root.rglob('*.json')):
                try:
                    data = json.loads(path.read_text())
                except json.JSONDecodeError:
                    continue
                if {'result_type', 'support_decision', 'model_id', 'runtime_id'} <= set(data):
                    data['_source_path'] = str(path)
                    results.append(data)
    return results


def decision_icon(decision: str) -> str:
    return {
        'supported': '✅ supported',
        'unsupported': '❌ unsupported',
        'needs_review': '⚠️ needs review',
        'not_claimed': '— not claimed',
    }.get(decision, f'— {decision}')


def row_value(result: dict[str, Any], key: str) -> str:
    value = result.get(key)
    if value is None:
        return 'TBD'
    return str(value)


def render_table(title: str, results: list[dict[str, Any]], result_type: str) -> list[str]:
    lines = [f'## {title}', '']
    filtered = [r for r in results if r.get('result_type') == result_type]
    if not filtered:
        return lines + ['No evidence files found.', '']
    if result_type == 'asr':
        headers = ['Decision', 'Language', 'Tier', 'Device', 'Model', 'Runtime', 'WER', 'CER', 'RTF', 'p95 partial ms', 'Memory MB', 'Battery/30m', 'Thermal', 'Source']
        keys = ['language', 'device_tier', 'device_model', 'model_id', 'runtime_id', 'wer', 'cer', 'rtf', 'p95_partial_latency_ms', 'memory_peak_mb', 'battery_delta_percent_30m', 'thermal_result']
    else:
        headers = ['Decision', 'Pair', 'Tier', 'Device', 'Model', 'Runtime', 'Quality', 'p95 translation ms', 'Memory MB', 'Battery/30m', 'Thermal', 'Legal', 'Source']
        keys = ['language_pair', 'device_tier', 'device_model', 'model_id', 'runtime_id', 'quality_score', 'p95_translation_latency_ms', 'memory_peak_mb', 'battery_delta_percent_30m', 'thermal_result', 'legal_review_status']
    lines.append('| ' + ' | '.join(headers) + ' |')
    lines.append('| ' + ' | '.join(['---'] * len(headers)) + ' |')
    for result in filtered:
        cells = [decision_icon(str(result.get('support_decision', 'not_claimed')))]
        cells.extend(row_value(result, key) for key in keys)
        cells.append(result.get('_source_path', 'direct-input'))
        lines.append('| ' + ' | '.join(cells) + ' |')
    lines.append('')
    return lines


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--results-root', action='append', default=['benchmarks/device-lab/results'])
    ap.add_argument('--include-samples', action='store_true')
    ap.add_argument('--output', default='docs/benchmarks/support-matrix.generated.md')
    args = ap.parse_args()

    roots = [Path(p) for p in args.results_root]
    if args.include_samples:
        roots.append(Path('benchmarks/device-lab/samples'))
    results = load_results(roots)
    supported = [r for r in results if r.get('support_decision') == 'supported']

    lines = [
        '# Generated Support Matrix',
        '',
        'This file is generated from device-lab benchmark result JSON files.',
        '',
        '**Conservative rule:** only rows with `support_decision: supported` are support claims. `not_claimed` samples and incomplete results are not support evidence.',
        '',
        f'- Evidence files scanned: {len(results)}',
        f'- Supported rows: {len(supported)}',
        '',
    ]
    lines.extend(render_table('ASR evidence', results, 'asr'))
    lines.extend(render_table('MT evidence', results, 'mt'))

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text('\n'.join(lines) + '\n')
    print(f'wrote: {output}')
    print(f'evidence_files: {len(results)}')
    print(f'supported_rows: {len(supported)}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
