#!/usr/bin/env python3
"""MT/runtime benchmark harness for G004.

Mock mode validates report plumbing only. Real support decisions require target
runtime/model/device measurements and legal review.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from pathlib import Path


def token_overlap(reference: str, hypothesis: str) -> float:
    ref = set(reference.lower().split())
    hyp = set(hypothesis.lower().split())
    if not ref:
        return 1.0 if not hyp else 0.0
    return len(ref & hyp) / len(ref)


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((p / 100) * (len(ordered) - 1))))
    return ordered[idx]


def translate_mock(item: dict) -> tuple[str, float]:
    start = time.perf_counter()
    return item["reference"], max(0.001, time.perf_counter() - start)


def translate_command(item: dict, command_template: str) -> tuple[str, float]:
    if not command_template:
        raise SystemExit("--command is required for engine=command")
    cmd = command_template.format(source=item["source"].replace("'", "'\\''"))
    start = time.perf_counter()
    proc = subprocess.run(cmd, shell=True, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    elapsed = max(0.001, time.perf_counter() - start)
    if proc.returncode != 0:
        raise SystemExit(f"translation command failed for {item['id']}: {proc.stderr}")
    return proc.stdout.strip(), elapsed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--candidate-id", required=True)
    ap.add_argument("--runtime-id", required=True)
    ap.add_argument("--engine", choices=["mock", "command"], default="mock")
    ap.add_argument("--command", default=os.environ.get("MT_COMMAND", ""))
    ap.add_argument("--device-tier", default="unknown")
    ap.add_argument("--device-model", default="unverified-host")
    ap.add_argument("--os-version", default=os.uname().release if hasattr(os, "uname") else "unknown")
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    manifest = json.loads(Path(args.manifest).read_text())
    rows = []
    latencies = []
    for item in manifest["items"]:
        hypothesis, elapsed = translate_mock(item) if args.engine == "mock" else translate_command(item, args.command)
        latency_ms = elapsed * 1000
        latencies.append(latency_ms)
        rows.append({
            "id": item["id"],
            "source": item["source"],
            "reference": item["reference"],
            "hypothesis": hypothesis,
            "quality_metric": "token_overlap_placeholder",
            "quality_score": token_overlap(item["reference"], hypothesis),
            "translation_latency_ms": latency_ms,
        })

    summary = {
        "candidate_id": args.candidate_id,
        "runtime_id": args.runtime_id,
        "engine": args.engine,
        "support_decision": "not_claimed" if args.engine == "mock" else "needs_review",
        "device_model": args.device_model,
        "os_version": args.os_version,
        "device_tier": args.device_tier,
        "language_pair": manifest["languagePair"],
        "item_count": len(rows),
        "quality_metric": "token_overlap_placeholder",
        "quality_score_mean": sum(r["quality_score"] for r in rows) / max(1, len(rows)),
        "p95_translation_latency_ms": percentile(latencies, 95),
        "package_size_mb": None,
        "memory_peak_mb": None,
        "battery_delta_percent_30m": None,
        "thermal_result": "not_measured",
        "legal_review_status": "not_reviewed",
        "rows": rows,
    }
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({k: summary[k] for k in ["candidate_id", "runtime_id", "engine", "support_decision", "quality_score_mean", "p95_translation_latency_ms", "legal_review_status"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
