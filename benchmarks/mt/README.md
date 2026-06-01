# MT/runtime benchmark execution

## Harness validation

```sh
scripts/validate_g004_mt_scaffold.py
```

Mock output validates metric/report structure only and must not be used as support evidence.

## Real runtime run pattern

```sh
python3 benchmarks/mt/run_mt_benchmark.py \
  --manifest benchmarks/mt/corpus_manifest.sample.json \
  --candidate-id milmmt-46-4b-q6-gguf-high-tier \
  --runtime-id llama.cpp/GGUF \
  --engine command \
  --command 'YOUR_TRANSLATION_COMMAND --text {source}' \
  --device-tier high \
  --device-model 'DEVICE-SKU' \
  --os-version 'OS-VERSION' \
  --output benchmarks/mt/results/DEVICE-CANDIDATE-RUNTIME.json
```

A result cannot become `supported` until package size, memory, battery, thermal, and legal review evidence are appended.
