# Device Lab Run Plan

Generated from `configs/device-lab-matrix.json`, `configs/asr-candidates.json`, and `configs/mt-candidates.json`.

**Execution readiness:** BLOCKED
**Support claims allowed:** false

This plan is not evidence and creates no ASR/MT support claim. It is the concrete command plan for collecting real G008/G010 evidence.

## External blockers before execution

- Device SKUs and/or OS versions in `configs/device-lab-matrix.json` are still `TBD`.
- Replace every `TBD` with a real iOS/Android lab device before treating generated commands as executable.
- Real model artifacts, checksums, package sizes, battery/thermal logs, telemetry JSONL, and legal review are still required.

## ASR G008 run matrix

- Planned ASR runs: 22

| Candidate | Language | Tier | Platform | SKU | OS | Runtime | Output dir |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ru-t-one-streaming-2025-09-08 | ru | low | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-t-one-streaming-2025-09-08 |
| ru-t-one-streaming-2025-09-08 | ru | low | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-t-one-streaming-2025-09-08 |
| ru-t-one-streaming-2025-09-08 | ru | mid | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-t-one-streaming-2025-09-08 |
| ru-t-one-streaming-2025-09-08 | ru | mid | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-t-one-streaming-2025-09-08 |
| ru-t-one-streaming-2025-09-08 | ru | high | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-t-one-streaming-2025-09-08 |
| ru-t-one-streaming-2025-09-08 | ru | high | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-t-one-streaming-2025-09-08 |
| ru-small-zipformer-baseline-2024-09-18 | ru | low | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-small-zipformer-baseline-2024-09-18 |
| ru-small-zipformer-baseline-2024-09-18 | ru | low | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-small-zipformer-baseline-2024-09-18 |
| ru-small-zipformer-baseline-2024-09-18 | ru | mid | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-small-zipformer-baseline-2024-09-18 |
| ru-small-zipformer-baseline-2024-09-18 | ru | mid | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-small-zipformer-baseline-2024-09-18 |
| ru-small-zipformer-baseline-2024-09-18 | ru | high | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-small-zipformer-baseline-2024-09-18 |
| ru-small-zipformer-baseline-2024-09-18 | ru | high | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-small-zipformer-baseline-2024-09-18 |
| en-zipformer-20m-low-tier-2023-02-17 | en | low | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/en-zipformer-20m-low-tier-2023-02-17 |
| en-zipformer-20m-low-tier-2023-02-17 | en | low | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/en-zipformer-20m-low-tier-2023-02-17 |
| en-zipformer-mid-high-2023-06-26 | en | mid | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/en-zipformer-mid-high-2023-06-26 |
| en-zipformer-mid-high-2023-06-26 | en | mid | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/en-zipformer-mid-high-2023-06-26 |
| en-zipformer-mid-high-2023-06-26 | en | high | android | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/en-zipformer-mid-high-2023-06-26 |
| en-zipformer-mid-high-2023-06-26 | en | high | ios | TBD | TBD | sherpa-onnx | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/en-zipformer-mid-high-2023-06-26 |
| multilingual-whisper-fallback | any_unsupported | mid | android | TBD | TBD | whisper.cpp_or_sherpa_onnx_whisper | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/multilingual-whisper-fallback |
| multilingual-whisper-fallback | any_unsupported | mid | ios | TBD | TBD | whisper.cpp_or_sherpa_onnx_whisper | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/multilingual-whisper-fallback |
| multilingual-whisper-fallback | any_unsupported | high | android | TBD | TBD | whisper.cpp_or_sherpa_onnx_whisper | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/multilingual-whisper-fallback |
| multilingual-whisper-fallback | any_unsupported | high | ios | TBD | TBD | whisper.cpp_or_sherpa_onnx_whisper | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/multilingual-whisper-fallback |

### ASR template commands

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-t-one-streaming-2025-09-08 --device-model "TBD" --os-version "TBD" --device-tier low --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-t-one-streaming-2025-09-08
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-t-one-streaming-2025-09-08 --device-model "TBD" --os-version "TBD" --device-tier low --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-t-one-streaming-2025-09-08
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-t-one-streaming-2025-09-08 --device-model "TBD" --os-version "TBD" --device-tier mid --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-t-one-streaming-2025-09-08
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-t-one-streaming-2025-09-08 --device-model "TBD" --os-version "TBD" --device-tier mid --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-t-one-streaming-2025-09-08
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-t-one-streaming-2025-09-08 --device-model "TBD" --os-version "TBD" --device-tier high --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-t-one-streaming-2025-09-08
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-t-one-streaming-2025-09-08 --device-model "TBD" --os-version "TBD" --device-tier high --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-t-one-streaming-2025-09-08
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-small-zipformer-baseline-2024-09-18 --device-model "TBD" --os-version "TBD" --device-tier low --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-small-zipformer-baseline-2024-09-18
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-small-zipformer-baseline-2024-09-18 --device-model "TBD" --os-version "TBD" --device-tier low --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-small-zipformer-baseline-2024-09-18
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-small-zipformer-baseline-2024-09-18 --device-model "TBD" --os-version "TBD" --device-tier mid --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-small-zipformer-baseline-2024-09-18
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-small-zipformer-baseline-2024-09-18 --device-model "TBD" --os-version "TBD" --device-tier mid --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-small-zipformer-baseline-2024-09-18
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-small-zipformer-baseline-2024-09-18 --device-model "TBD" --os-version "TBD" --device-tier high --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/ru-small-zipformer-baseline-2024-09-18
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id ru-small-zipformer-baseline-2024-09-18 --device-model "TBD" --os-version "TBD" --device-tier high --language ru --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/ru-small-zipformer-baseline-2024-09-18
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id en-zipformer-20m-low-tier-2023-02-17 --device-model "TBD" --os-version "TBD" --device-tier low --language en --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/en-zipformer-20m-low-tier-2023-02-17
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id en-zipformer-20m-low-tier-2023-02-17 --device-model "TBD" --os-version "TBD" --device-tier low --language en --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/en-zipformer-20m-low-tier-2023-02-17
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id en-zipformer-mid-high-2023-06-26 --device-model "TBD" --os-version "TBD" --device-tier mid --language en --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/en-zipformer-mid-high-2023-06-26
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id en-zipformer-mid-high-2023-06-26 --device-model "TBD" --os-version "TBD" --device-tier mid --language en --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/en-zipformer-mid-high-2023-06-26
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id en-zipformer-mid-high-2023-06-26 --device-model "TBD" --os-version "TBD" --device-tier high --language en --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/en-zipformer-mid-high-2023-06-26
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id en-zipformer-mid-high-2023-06-26 --device-model "TBD" --os-version "TBD" --device-tier high --language en --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/en-zipformer-mid-high-2023-06-26
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id multilingual-whisper-fallback --device-model "TBD" --os-version "TBD" --device-tier mid --language any_unsupported --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/multilingual-whisper-fallback
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id multilingual-whisper-fallback --device-model "TBD" --os-version "TBD" --device-tier mid --language any_unsupported --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/multilingual-whisper-fallback
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id multilingual-whisper-fallback --device-model "TBD" --os-version "TBD" --device-tier high --language any_unsupported --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/multilingual-whisper-fallback
```

```sh
python3 scripts/create_device_lab_run_template.py --type asr --candidate-id multilingual-whisper-fallback --device-model "TBD" --os-version "TBD" --device-tier high --language any_unsupported --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/multilingual-whisper-fallback
```

## MT G010 run matrix

- Planned MT runtime/pair runs: 72

| Candidate | Pair | Tier | Platform | SKU | OS | Runtime | Output dir |
| --- | --- | --- | --- | --- | --- | --- | --- |
| milmmt-46-4b-q6-gguf-high-tier | ru->en | high | android | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-llama-cpp-gguf |
| milmmt-46-4b-q6-gguf-high-tier | ru->en | high | android | TBD | TBD | MLC LLM if convertible | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-mlc-llm-if-convertible |
| milmmt-46-4b-q6-gguf-high-tier | ru->en | high | android | TBD | TBD | LiteRT-LM if convertible | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-litert-lm-if-convertible |
| milmmt-46-4b-q6-gguf-high-tier | en->ru | high | android | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-llama-cpp-gguf |
| milmmt-46-4b-q6-gguf-high-tier | en->ru | high | android | TBD | TBD | MLC LLM if convertible | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-mlc-llm-if-convertible |
| milmmt-46-4b-q6-gguf-high-tier | en->ru | high | android | TBD | TBD | LiteRT-LM if convertible | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-litert-lm-if-convertible |
| milmmt-46-4b-q6-gguf-high-tier | ru->en | high | ios | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-llama-cpp-gguf |
| milmmt-46-4b-q6-gguf-high-tier | ru->en | high | ios | TBD | TBD | MLC LLM if convertible | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-mlc-llm-if-convertible |
| milmmt-46-4b-q6-gguf-high-tier | ru->en | high | ios | TBD | TBD | LiteRT-LM if convertible | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-litert-lm-if-convertible |
| milmmt-46-4b-q6-gguf-high-tier | en->ru | high | ios | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-llama-cpp-gguf |
| milmmt-46-4b-q6-gguf-high-tier | en->ru | high | ios | TBD | TBD | MLC LLM if convertible | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-mlc-llm-if-convertible |
| milmmt-46-4b-q6-gguf-high-tier | en->ru | high | ios | TBD | TBD | LiteRT-LM if convertible | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-litert-lm-if-convertible |
| milmmt-smaller-quant-mid-high | ru->en | mid | android | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-llama-cpp-gguf |
| milmmt-smaller-quant-mid-high | ru->en | mid | android | TBD | TBD | MLC LLM | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-mlc-llm |
| milmmt-smaller-quant-mid-high | ru->en | mid | android | TBD | TBD | LiteRT-LM | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-litert-lm |
| milmmt-smaller-quant-mid-high | en->ru | mid | android | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-llama-cpp-gguf |
| milmmt-smaller-quant-mid-high | en->ru | mid | android | TBD | TBD | MLC LLM | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-mlc-llm |
| milmmt-smaller-quant-mid-high | en->ru | mid | android | TBD | TBD | LiteRT-LM | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-litert-lm |
| milmmt-smaller-quant-mid-high | ru->en | mid | ios | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-ru-en-llama-cpp-gguf |
| milmmt-smaller-quant-mid-high | ru->en | mid | ios | TBD | TBD | MLC LLM | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-ru-en-mlc-llm |
| milmmt-smaller-quant-mid-high | ru->en | mid | ios | TBD | TBD | LiteRT-LM | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-ru-en-litert-lm |
| milmmt-smaller-quant-mid-high | en->ru | mid | ios | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-en-ru-llama-cpp-gguf |
| milmmt-smaller-quant-mid-high | en->ru | mid | ios | TBD | TBD | MLC LLM | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-en-ru-mlc-llm |
| milmmt-smaller-quant-mid-high | en->ru | mid | ios | TBD | TBD | LiteRT-LM | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-en-ru-litert-lm |
| milmmt-smaller-quant-mid-high | ru->en | high | android | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-llama-cpp-gguf |
| milmmt-smaller-quant-mid-high | ru->en | high | android | TBD | TBD | MLC LLM | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-mlc-llm |
| milmmt-smaller-quant-mid-high | ru->en | high | android | TBD | TBD | LiteRT-LM | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-litert-lm |
| milmmt-smaller-quant-mid-high | en->ru | high | android | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-llama-cpp-gguf |
| milmmt-smaller-quant-mid-high | en->ru | high | android | TBD | TBD | MLC LLM | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-mlc-llm |
| milmmt-smaller-quant-mid-high | en->ru | high | android | TBD | TBD | LiteRT-LM | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-litert-lm |
| milmmt-smaller-quant-mid-high | ru->en | high | ios | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-ru-en-llama-cpp-gguf |
| milmmt-smaller-quant-mid-high | ru->en | high | ios | TBD | TBD | MLC LLM | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-ru-en-mlc-llm |
| milmmt-smaller-quant-mid-high | ru->en | high | ios | TBD | TBD | LiteRT-LM | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-ru-en-litert-lm |
| milmmt-smaller-quant-mid-high | en->ru | high | ios | TBD | TBD | llama.cpp/GGUF | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-en-ru-llama-cpp-gguf |
| milmmt-smaller-quant-mid-high | en->ru | high | ios | TBD | TBD | MLC LLM | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-en-ru-mlc-llm |
| milmmt-smaller-quant-mid-high | en->ru | high | ios | TBD | TBD | LiteRT-LM | benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-en-ru-litert-lm |
| dedicated-mt-low-mid | ru->en | low | android | TBD | TBD | ONNX | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/dedicated-mt-low-mid-ru-en-onnx |
| dedicated-mt-low-mid | ru->en | low | android | TBD | TBD | LiteRT | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/dedicated-mt-low-mid-ru-en-litert |
| dedicated-mt-low-mid | ru->en | low | android | TBD | TBD | other_mobile_runtime | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/dedicated-mt-low-mid-ru-en-other-mobile-runtime |
| dedicated-mt-low-mid | en->ru | low | android | TBD | TBD | ONNX | benchmarks/device-lab/results/REPLACE_DATE/android-tbd/dedicated-mt-low-mid-en-ru-onnx |
| … | … | … | … | … | … | … | … | … |

### MT template command pattern

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "llama.cpp/GGUF" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-llama-cpp-gguf
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "MLC LLM if convertible" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-mlc-llm-if-convertible
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "LiteRT-LM if convertible" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-litert-lm-if-convertible
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "llama.cpp/GGUF" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-llama-cpp-gguf
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "MLC LLM if convertible" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-mlc-llm-if-convertible
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "LiteRT-LM if convertible" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-litert-lm-if-convertible
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "llama.cpp/GGUF" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-llama-cpp-gguf
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "MLC LLM if convertible" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-mlc-llm-if-convertible
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "LiteRT-LM if convertible" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-ru-en-litert-lm-if-convertible
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "llama.cpp/GGUF" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-llama-cpp-gguf
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "MLC LLM if convertible" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-mlc-llm-if-convertible
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-46-4b-q6-gguf-high-tier --runtime-id "LiteRT-LM if convertible" --device-model "TBD" --os-version "TBD" --device-tier high --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-46-4b-q6-gguf-high-tier-en-ru-litert-lm-if-convertible
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-smaller-quant-mid-high --runtime-id "llama.cpp/GGUF" --device-model "TBD" --os-version "TBD" --device-tier mid --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-llama-cpp-gguf
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-smaller-quant-mid-high --runtime-id "MLC LLM" --device-model "TBD" --os-version "TBD" --device-tier mid --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-mlc-llm
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-smaller-quant-mid-high --runtime-id "LiteRT-LM" --device-model "TBD" --os-version "TBD" --device-tier mid --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-ru-en-litert-lm
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-smaller-quant-mid-high --runtime-id "llama.cpp/GGUF" --device-model "TBD" --os-version "TBD" --device-tier mid --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-llama-cpp-gguf
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-smaller-quant-mid-high --runtime-id "MLC LLM" --device-model "TBD" --os-version "TBD" --device-tier mid --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-mlc-llm
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-smaller-quant-mid-high --runtime-id "LiteRT-LM" --device-model "TBD" --os-version "TBD" --device-tier mid --language-pair "en->ru" --output-dir benchmarks/device-lab/results/REPLACE_DATE/android-tbd/milmmt-smaller-quant-mid-high-en-ru-litert-lm
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-smaller-quant-mid-high --runtime-id "llama.cpp/GGUF" --device-model "TBD" --os-version "TBD" --device-tier mid --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-ru-en-llama-cpp-gguf
```

```sh
python3 scripts/create_device_lab_run_template.py --type mt --candidate-id milmmt-smaller-quant-mid-high --runtime-id "MLC LLM" --device-model "TBD" --os-version "TBD" --device-tier mid --language-pair "ru->en" --output-dir benchmarks/device-lab/results/REPLACE_DATE/ios-tbd/milmmt-smaller-quant-mid-high-ru-en-mlc-llm
```

Additional MT commands omitted from display: 52. Regenerate or inspect this script output model if a full per-command list is needed.

## Required post-run validation

After filling and renaming template files to real evidence files, run:

```sh
python3 scripts/validate_device_lab_evidence_root.py --results-root benchmarks/device-lab/results/REPLACE_DATE
python3 scripts/check_release_gate.py
```

The release gate must remain blocked until G008 and G010 are checkpointed with real validated evidence and G012 final QA/review passes.
