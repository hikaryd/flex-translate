# mobile-mcp UI Automation Harness

Status: QA harness for G032. This is UI-flow evidence only; it is not ASR/MT benchmark evidence, not battery/thermal proof, not legal approval, and not a support-claim authority.

## Source and setup

Use `mobile-next/mobile-mcp` for mobile UI automation across iOS and Android devices, simulators, and emulators:

```json
{
  "mcpServers": {
    "mobile-mcp": {
      "command": "npx",
      "args": ["-y", "@mobilenext/mobile-mcp@latest"]
    }
  }
}
```

The scenario manifest is `configs/mobile-mcp-scenarios.json` and is validated by:

```sh
python3 scripts/validate_mobile_mcp_qa.py
```

## What mobile-mcp proves

mobile-mcp can provide repeatable UI evidence for:

- install and launch flow;
- microphone permission path;
- offline/local-first launch state;
- missing model-pack state;
- unsupported translation-pair state;
- cloud consent and cancellation UX;
- screenshots and accessibility snapshots before/after actions.

Required UI evidence per scenario:

- `mobile-mcp-session.json` with device/platform/tool timeline;
- `accessibility-snapshot.before.json`;
- `accessibility-snapshot.after.json`;
- `screenshot.before.png`;
- `screenshot.after.png`;
- `assertions.json`.

## What mobile-mcp does not prove

Do not use mobile-mcp output to complete G008 or G010. It is not authoritative for:

- WER/CER, RTF, ASR partial latency, or translation quality/latency;
- memory peak, battery drain, or thermal state;
- model package size, runtime conversion success, or legal review;
- any ASR/MT `supported` row or release support claim.

Those still require native telemetry, benchmark JSON, device metadata, battery/thermal logs, package evidence, runtime-conversion evidence, legal-review evidence, and release-gate approval.

## Run pattern

For each target platform/device:

1. Build the iOS/Android app artifact.
2. Start MCP with the configured `npx -y @mobilenext/mobile-mcp@latest` command.
3. Run each scenario in `configs/mobile-mcp-scenarios.json`.
4. Store scenario evidence under `docs/qa/mobile-mcp-runs/<date>/<platform>/<scenario-id>/` or an external QA artifact store.
5. Keep scenario assertions focused on UI state and consent/offline invariants.
6. Run native benchmark/device-lab validators separately before any performance or support decision.


## Run artifact validation

Concrete mobile-mcp run artifacts are validated with:

```sh
python3 scripts/validate_mobile_mcp_run_artifacts.py --run-dir <scenario-evidence-dir>
```

The repository includes a sample fixture at `docs/qa/mobile-mcp-samples/android/launch-offline-local-first/` for validator coverage only. Validate that fixture with:

```sh
python3 scripts/validate_mobile_mcp_run_artifacts.py --run-dir docs/qa/mobile-mcp-samples/android/launch-offline-local-first --allow-sample
```

A valid run directory must contain `mobile-mcp-session.json`, before/after accessibility snapshots, before/after PNG screenshots, and `assertions.json`. The validator rejects support, performance, battery/thermal, and legal claims in mobile-mcp artifacts.

## Release-gate relationship

The release gate may require mobile-mcp scenario evidence as QA coverage, but passing mobile-mcp scenarios alone must leave the aggregate release blocked while G008/G010/G012 remain unresolved.
