# ASR benchmark execution

This directory contains the G003 ASR benchmark harness.

## Harness validation

```sh
scripts/validate_g003_asr_scaffold.py
```

This runs the `mock` engine and proves metric/report plumbing only. It is not support evidence.

## Real sherpa-onnx run pattern

Real support evidence must be generated on target iOS/Android devices or lab-equivalent target platforms. The generic CLI wrapper expects a command that prints the transcript for one audio file:

```sh
python3 benchmarks/asr/run_asr_benchmark.py \
  --manifest benchmarks/asr/corpus_manifest.sample.json \
  --candidate-id ru-t-one-streaming-2025-09-08 \
  --engine sherpa_cli \
  --sherpa-command 'YOUR_SHERPA_COMMAND --input {audio}' \
  --device-tier high \
  --device-model 'DEVICE-SKU' \
  --os-version 'OS-VERSION' \
  --output benchmarks/asr/results/DEVICE-CANDIDATE.json
```

A result cannot become `supported` until package size, memory, battery, thermal, and audio dropout evidence are appended from native instrumentation.
