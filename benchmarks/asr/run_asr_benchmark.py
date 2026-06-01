#!/usr/bin/env python3
"""ASR benchmark harness for G003.

The mock engine validates metric/report plumbing only. Real support decisions require
running a real engine on target devices and filling all required evidence fields.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from statistics import median


def edit_distance(a: list[str], b: list[str]) -> int:
    dp = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        dp[i][0] = i
    for j in range(len(b) + 1):
        dp[0][j] = j
    for i, ai in enumerate(a, 1):
        for j, bj in enumerate(b, 1):
            dp[i][j] = min(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + (ai != bj),
            )
    return dp[-1][-1]


def wer(ref: str, hyp: str) -> float:
    r = ref.lower().split()
    h = hyp.lower().split()
    return edit_distance(r, h) / max(1, len(r))


def cer(ref: str, hyp: str) -> float:
    r = list(ref.lower().replace(" ", ""))
    h = list(hyp.lower().replace(" ", ""))
    return edit_distance(r, h) / max(1, len(r))


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))
    return ordered[idx]


@dataclass
class Recognition:
    text: str
    elapsed_sec: float
    partial_latency_ms: float | None


def recognize_mock(item: dict) -> Recognition:
    start = time.perf_counter()
    # Deterministic perfect transcript for harness validation only.
    text = item["groundTruth"]
    elapsed = max(0.001, time.perf_counter() - start)
    return Recognition(text=text, elapsed_sec=elapsed, partial_latency_ms=100.0)


def recognize_sherpa_cli(item: dict, command_template: str) -> Recognition:
    if not command_template:
        raise SystemExit("--sherpa-command is required for engine=sherpa_cli")
    cmd = command_template.format(audio=item["audioPath"])
    start = time.perf_counter()
    proc = subprocess.run(cmd, shell=True, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    elapsed = max(0.001, time.perf_counter() - start)
    if proc.returncode != 0:
        raise SystemExit(f"sherpa command failed for {item['id']}: {proc.stderr}")
    return Recognition(text=proc.stdout.strip(), elapsed_sec=elapsed, partial_latency_ms=None)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--candidate-id", required=True)
    ap.add_argument("--engine", choices=["mock", "sherpa_cli"], default="mock")
    ap.add_argument("--sherpa-command", default=os.environ.get("SHERPA_ASR_COMMAND", ""))
    ap.add_argument("--device-tier", default="unknown")
    ap.add_argument("--device-model", default="unverified-host")
    ap.add_argument("--os-version", default=os.uname().release if hasattr(os, "uname") else "unknown")
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    manifest = json.loads(Path(args.manifest).read_text())
    items = manifest["items"]
    rows = []
    latencies = []
    total_audio = 0.0
    total_elapsed = 0.0
    for item in items:
        rec = recognize_mock(item) if args.engine == "mock" else recognize_sherpa_cli(item, args.sherpa_command)
        total_audio += float(item["durationSec"])
        total_elapsed += rec.elapsed_sec
        if rec.partial_latency_ms is not None:
            latencies.append(rec.partial_latency_ms)
        rows.append({
            "id": item["id"],
            "groundTruth": item["groundTruth"],
            "hypothesis": rec.text,
            "wer": wer(item["groundTruth"], rec.text),
            "cer": cer(item["groundTruth"], rec.text),
            "elapsed_sec": rec.elapsed_sec,
            "partial_latency_ms": rec.partial_latency_ms,
        })

    summary = {
        "candidate_id": args.candidate_id,
        "engine": args.engine,
        "support_decision": "not_claimed" if args.engine == "mock" else "needs_review",
        "device_model": args.device_model,
        "os_version": args.os_version,
        "device_tier": args.device_tier,
        "item_count": len(rows),
        "wer_mean": sum(r["wer"] for r in rows) / max(1, len(rows)),
        "cer_mean": sum(r["cer"] for r in rows) / max(1, len(rows)),
        "rtf": total_elapsed / max(0.001, total_audio),
        "p50_partial_latency_ms": median(latencies) if latencies else None,
        "p95_partial_latency_ms": percentile(latencies, 95),
        "package_size_mb": None,
        "memory_peak_mb": None,
        "battery_delta_percent_30m": None,
        "thermal_result": "not_measured",
        "audio_dropouts": None,
        "rows": rows,
    }
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({k: summary[k] for k in ["candidate_id", "engine", "support_decision", "wer_mean", "cer_mean", "rtf", "p95_partial_latency_ms"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
