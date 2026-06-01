#!/usr/bin/env python3
from __future__ import annotations
import argparse
import json
from pathlib import Path

COMMON_REQUIRED = [
    'goal_id', 'result_type', 'support_decision', 'device_model', 'os_version', 'device_tier',
    'model_id', 'runtime_id', 'package_size_mb', 'memory_peak_mb',
    'battery_delta_percent_30m', 'thermal_result', 'evidence_paths'
]
ASR_REQUIRED = ['language', 'wer', 'cer', 'rtf', 'p95_partial_latency_ms', 'audio_dropouts']
MT_REQUIRED = ['language_pair', 'quality_metric', 'quality_score', 'p95_translation_latency_ms', 'legal_review_status']


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--type', choices=['asr', 'mt'], required=True)
    ap.add_argument('--result', required=True)
    args = ap.parse_args()

    data = json.loads(Path(args.result).read_text())
    required = COMMON_REQUIRED + (ASR_REQUIRED if args.type == 'asr' else MT_REQUIRED)
    missing = [key for key in required if key not in data or data[key] is None]
    if missing:
        raise SystemExit(f'missing required benchmark evidence fields: {missing}')
    if data['result_type'] != args.type:
        raise SystemExit(f'result_type mismatch: expected {args.type}, got {data["result_type"]}')
    if data['support_decision'] == 'supported':
        if data['thermal_result'] in {'critical', 'not_measured'}:
            raise SystemExit('supported decision invalid with critical/not_measured thermal_result')
        if args.type == 'asr' and not (data['rtf'] < 1.0):
            raise SystemExit('supported ASR decision requires rtf < 1.0')
        if args.type == 'mt' and data['legal_review_status'] != 'approved_for_distribution':
            raise SystemExit('supported MT decision requires approved_for_distribution legal_review_status')
    print(f'{args.type.upper()} benchmark result validation: PASS')
    print(f'support_decision: {data["support_decision"]}')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
