#!/usr/bin/env python3
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path

VALID_CMD = [
    'python3',
    'scripts/validate_telemetry_log.py',
    '--log',
    'benchmarks/device-lab/samples/telemetry.valid.jsonl',
    '--offline-no-network',
    '--require-event-count',
    'asr_partial_emitted=3',
    '--require-event-count',
    'memory_sample=1',
    '--require-event-count',
    'battery_sample=1',
    '--require-event-count',
    'thermal_sample=1',
]

subprocess.run(VALID_CMD, check=True)

valid = Path('benchmarks/device-lab/samples/telemetry.valid.jsonl').read_text().splitlines()
with tempfile.TemporaryDirectory() as tmp:
    tmp_path = Path(tmp)
    non_monotonic = tmp_path / 'telemetry.non-monotonic.jsonl'
    mutated = valid.copy()
    mutated[3] = mutated[3].replace('"monotonic_ts_ms":220', '"monotonic_ts_ms":20')
    non_monotonic.write_text('\n'.join(mutated) + '\n')
    proc = subprocess.run(
        ['python3', 'scripts/validate_telemetry_log.py', '--log', str(non_monotonic), '--offline-no-network'],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if proc.returncode == 0 or 'non-monotonic timestamp' not in proc.stdout:
        raise SystemExit('expected non-monotonic telemetry rejection')

    network_attempt = tmp_path / 'telemetry.network-attempt.jsonl'
    mutated = valid.copy()
    mutated.insert(2, mutated[0].replace('"event_type":"audio_callback_received"', '"event_type":"network_request_attempted"').replace('"monotonic_ts_ms":0', '"monotonic_ts_ms":80'))
    network_attempt.write_text('\n'.join(mutated) + '\n')
    proc = subprocess.run(
        ['python3', 'scripts/validate_telemetry_log.py', '--log', str(network_attempt), '--offline-no-network'],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if proc.returncode == 0 or 'offline telemetry contains network_request_attempted' not in proc.stdout:
        raise SystemExit('expected offline network-attempt telemetry rejection')

    too_few_samples = tmp_path / 'telemetry.too-few-samples.jsonl'
    too_few_samples.write_text('\n'.join(valid[:2]) + '\n')
    proc = subprocess.run(
        [
            'python3',
            'scripts/validate_telemetry_log.py',
            '--log',
            str(too_few_samples),
            '--offline-no-network',
            '--require-event-count',
            'asr_partial_emitted=3',
        ],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if proc.returncode == 0 or 'insufficient telemetry samples' not in proc.stdout:
        raise SystemExit('expected insufficient telemetry samples rejection')

print('Telemetry validator validation: PASS')
