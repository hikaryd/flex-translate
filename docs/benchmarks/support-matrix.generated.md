# Generated Support Matrix

This file is generated from device-lab benchmark result JSON files.

**Conservative rule:** only rows with `support_decision: supported` are support claims. `not_claimed` samples and incomplete results are not support evidence.

- Evidence files scanned: 2
- Supported rows: 0

## ASR evidence

| Decision | Language | Tier | Device | Model | Runtime | WER | CER | RTF | p95 partial ms | Memory MB | Battery/30m | Thermal | Source |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| — not claimed | en | high | lab-device-tbd | en-zipformer-mid-high-2023-06-26 | sherpa-onnx | 0.0 | 0.0 | 0.1 | 100 | 0 | 0 | not_measured | benchmarks/device-lab/samples/asr-result.not-claimed.json |

## MT evidence

| Decision | Pair | Tier | Device | Model | Runtime | Quality | p95 translation ms | Memory MB | Battery/30m | Thermal | Legal | Source |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| — not claimed | en->ru | high | lab-device-tbd | milmmt-46-4b-q6-gguf-high-tier | mock-runtime | 1.0 | 1 | 0 | 0 | not_measured | not_reviewed | benchmarks/device-lab/samples/mt-result.not-claimed.json |

