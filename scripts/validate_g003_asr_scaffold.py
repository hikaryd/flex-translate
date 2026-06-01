#!/usr/bin/env python3
from pathlib import Path
import json
import subprocess
import sys

required = [
    'configs/asr-candidates.json',
    'docs/research/asr-proof.md',
    'benchmarks/asr/corpus_manifest.sample.json',
    'benchmarks/asr/run_asr_benchmark.py',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/AsrCandidate.kt',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/SherpaOnnxAsrProvider.kt',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/AsrCandidate.swift',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/SherpaOnnxAsrProvider.swift',
]
missing = [p for p in required if not Path(p).is_file()]
if missing:
    raise SystemExit(f'missing: {missing}')

cfg = json.loads(Path('configs/asr-candidates.json').read_text())
assert cfg['supportClaims'] == 'none_until_benchmarked'
assert cfg['runtimePolicy']['primary'] == 'sherpa-onnx'
assert cfg['runtimePolicy']['noSupportWithoutEvidence'] is True
langs = {c['language'] for c in cfg['candidates']}
assert 'ru' in langs and 'en' in langs
for cand in cfg['candidates']:
    assert cand['support'] == 'not_claimed'
    assert cand['runtime']
    assert 'targets' in cand

out = 'benchmarks/asr/results/mock-validation.json'
subprocess.run([
    sys.executable,
    'benchmarks/asr/run_asr_benchmark.py',
    '--manifest', 'benchmarks/asr/corpus_manifest.sample.json',
    '--candidate-id', 'en-zipformer-20m-low-tier-2023-02-17',
    '--engine', 'mock',
    '--output', out,
], check=True)
report = json.loads(Path(out).read_text())
assert report['support_decision'] == 'not_claimed'
assert report['wer_mean'] == 0
assert report['cer_mean'] == 0
assert report['p95_partial_latency_ms'] == 100.0
print('G003 ASR scaffold validation: PASS')
print(f'candidate_count: {len(cfg["candidates"])}')
combined = '\n'.join(Path(p).read_text(errors='ignore') for p in required)
for token in ['SherpaOnnxAsrProvider', 'supportClaim', 'not_claimed']:
    assert token in combined, token
print(f'mock_report: {out}')
