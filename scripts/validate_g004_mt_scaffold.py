#!/usr/bin/env python3
from pathlib import Path
import json
import subprocess
import sys

required = [
    'configs/mt-candidates.json',
    'docs/research/translation-runtime-proof.md',
    'benchmarks/mt/corpus_manifest.sample.json',
    'benchmarks/mt/run_mt_benchmark.py',
    'benchmarks/mt/README.md',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/TranslationCandidate.kt',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/TranslationCandidate.swift',
]
missing = [p for p in required if not Path(p).is_file()]
if missing:
    raise SystemExit(f'missing: {missing}')

cfg = json.loads(Path('configs/mt-candidates.json').read_text())
assert cfg['supportClaims'] == 'none_until_benchmarked_and_legal_reviewed'
assert cfg['runtimePolicy']['noSupportWithoutEvidence'] is True
assert cfg['runtimePolicy']['noDistributionWithoutLegalReview'] is True
classes = {c['candidateClass'] for c in cfg['candidates']}
for expected in ['high_tier_llm_mt', 'smaller_quantized_llm_mt', 'dedicated_mt_model', 'commercial_or_platform_sdk']:
    assert expected in classes, expected
for cand in cfg['candidates']:
    assert cand['support'] == 'not_claimed'
    assert cand['languagePairs']
    assert cand['runtimeCandidates']

out = 'benchmarks/mt/results/mock-validation.json'
subprocess.run([
    sys.executable,
    'benchmarks/mt/run_mt_benchmark.py',
    '--manifest', 'benchmarks/mt/corpus_manifest.sample.json',
    '--candidate-id', 'milmmt-46-4b-q6-gguf-high-tier',
    '--runtime-id', 'mock-runtime',
    '--engine', 'mock',
    '--output', out,
], check=True)
report = json.loads(Path(out).read_text())
assert report['support_decision'] == 'not_claimed'
assert report['legal_review_status'] == 'not_reviewed'
assert report['quality_score_mean'] == 1
combined = '\n'.join(Path(p).read_text(errors='ignore') for p in required)
for token in ['MiLMMT', 'not_claimed', 'legal review', 'llama.cpp/GGUF', 'LiteRT-LM']:
    assert token in combined, token
print('G004 MT scaffold validation: PASS')
print(f'candidate_count: {len(cfg["candidates"])}')
print(f'mock_report: {out}')
