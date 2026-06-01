#!/usr/bin/env python3
from pathlib import Path
import json
import subprocess

required = [
    'configs/device-lab-matrix.json',
    'schemas/benchmark-result.schema.json',
    'schemas/model-manifest.schema.json',
    'docs/benchmarks/device-lab-runbook.md',
    'docs/benchmarks/device-lab-readiness.generated.md',
    'docs/benchmarks/device-lab-readiness.generated.json',
    'docs/benchmarks/device-lab-intake.generated.md',
    'docs/benchmarks/device-lab-intake.generated.json',
    'docs/benchmarks/proof-coverage.generated.md',
    'docs/benchmarks/proof-coverage.generated.json',
    'docs/benchmarks/proof-retry-decision.generated.md',
    'docs/benchmarks/proof-retry-decision.generated.json',
    'docs/legal/model-distribution-checklist.md',
    'docs/qa/mobile-mcp-automation.md',
    'configs/mobile-mcp-scenarios.json',
    'scripts/create_device_lab_run_template.py',
    'scripts/generate_completion_audit.py',
    'scripts/generate_device_lab_readiness.py',
    'scripts/generate_device_lab_run_plan.py',
    'scripts/generate_proof_coverage.py',
    'scripts/generate_proof_retry_decision.py',
    'scripts/validate_battery_thermal_log.py',
    'scripts/validate_benchmark_result.py',
    'scripts/validate_mobile_mcp_qa.py',
    'scripts/validate_mobile_mcp_run_artifacts.py',
    'scripts/validate_completion_audit.py',
    'scripts/validate_device_artifact_validators.py',
    'scripts/validate_device_metadata.py',
    'scripts/validate_device_lab_bundle.py',
    'scripts/validate_device_lab_bundle_validator.py',
    'scripts/validate_device_lab_evidence_intake_transition.py',
    'scripts/validate_device_lab_evidence_root.py',
    'scripts/validate_device_lab_intake_report.py',
    'scripts/validate_device_lab_evidence_root_validator.py',
    'scripts/validate_device_lab_matrix.py',
    'scripts/validate_device_lab_preflight.py',
    'scripts/validate_device_lab_matrix_validator.py',
    'scripts/validate_device_lab_readiness.py',
    'scripts/validate_device_lab_run_template_generator.py',
    'scripts/validate_device_lab_run_plan.py',
    'scripts/validate_proof_coverage.py',
    'scripts/validate_proof_retry_decision.py',
    'scripts/validate_proof_coverage_transition.py',
    'scripts/validate_model_manifest.py',
    'scripts/validate_model_manifest_validator.py',
    'scripts/validate_telemetry_log.py',
    'scripts/validate_telemetry_validator.py',
    'docs/qa/mobile-mcp-samples/android/launch-offline-local-first/mobile-mcp-session.json',
    'docs/qa/mobile-mcp-samples/android/launch-offline-local-first/accessibility-snapshot.before.json',
    'docs/qa/mobile-mcp-samples/android/launch-offline-local-first/accessibility-snapshot.after.json',
    'docs/qa/mobile-mcp-samples/android/launch-offline-local-first/screenshot.before.png',
    'docs/qa/mobile-mcp-samples/android/launch-offline-local-first/screenshot.after.png',
    'docs/qa/mobile-mcp-samples/android/launch-offline-local-first/assertions.json',
    'benchmarks/device-lab/samples/asr-result.not-claimed.json',
    'benchmarks/device-lab/samples/mt-result.not-claimed.json',
    'benchmarks/device-lab/samples/telemetry.valid.jsonl',
    'benchmarks/device-lab/samples/telemetry.mt.valid.jsonl',
    'benchmarks/device-lab/samples/device-metadata.asr.fixture.json',
    'benchmarks/device-lab/samples/device-metadata.mt.fixture.json',
    'benchmarks/device-lab/samples/battery-thermal.asr.fixture.json',
    'benchmarks/device-lab/samples/battery-thermal.mt.fixture.json',
    'benchmarks/device-lab/samples/package-evidence.asr.fixture.json',
    'benchmarks/device-lab/samples/package-evidence.milmmt.fixture.json',
    'benchmarks/device-lab/samples/runtime-conversion.asr.fixture.json',
    'benchmarks/device-lab/samples/runtime-conversion.milmmt.fixture.json',
    'benchmarks/device-lab/samples/legal-review.asr.fixture.json',
    'benchmarks/device-lab/samples/legal-review.milmmt.fixture.json',
    'benchmarks/device-lab/samples/model-manifest.asr.internal.json',
    'benchmarks/device-lab/samples/model-manifest.milmmt.internal.json',
    'benchmarks/device-lab/samples/bundles/asr-not-claimed.bundle.json',
    'benchmarks/device-lab/samples/bundles/mt-not-claimed.bundle.json',
]
missing = [p for p in required if not Path(p).is_file()]
if missing:
    raise SystemExit(f'missing: {missing}')

matrix = json.loads(Path('configs/device-lab-matrix.json').read_text())
assert matrix['status'] == 'execution_required'
assert matrix['supportClaimsAllowed'] is False
assert len(matrix['deviceTiers']) == 3
schema = json.loads(Path('schemas/benchmark-result.schema.json').read_text())
for key in ['support_decision', 'device_model', 'runtime_id', 'thermal_result']:
    assert key in schema['required'], key
runbook = Path('docs/benchmarks/device-lab-runbook.md').read_text()
for token in ['G008', 'G010', 'sherpa-onnx', 'legal review', 'package-evidence.json', 'runtime-conversion-evidence.json', 'legal-review-evidence.json', 'validate_telemetry_log.py', 'validate_device_metadata.py', 'validate_battery_thermal_log.py', 'validate_device_lab_bundle.py', 'validate_device_lab_evidence_root.py', 'device-lab-intake.generated.json', 'validate_device_lab_matrix.py', 'validate_device_lab_preflight.py', 'create_device_lab_run_template.py', 'Do not convert missing evidence into a support claim']:
    assert token in runbook, token
legal = Path('docs/legal/model-distribution-checklist.md').read_text()
for token in ['Gemma', 'MiLMMT', 'approved_for_distribution', 'internal-lab-only', 'validate_model_manifest.py', 'model manifest']:
    assert token in legal, token
manifest_schema = json.loads(Path('schemas/model-manifest.schema.json').read_text())
for key in ['model_id', 'sha256', 'package_evidence_path', 'runtime_conversion_status', 'runtime_conversion_evidence_path', 'legal_review_evidence_path', 'distribution_mode', 'legal_review_status']:
    assert key in manifest_schema['required'], key
subprocess.run(['python3', 'scripts/validate_benchmark_result.py', '--type', 'asr', '--result', 'benchmarks/device-lab/samples/asr-result.not-claimed.json'], check=True)
subprocess.run(['python3', 'scripts/validate_benchmark_result.py', '--type', 'mt', '--result', 'benchmarks/device-lab/samples/mt-result.not-claimed.json'], check=True)
subprocess.run(['python3', 'scripts/validate_telemetry_validator.py'], check=True)
subprocess.run(['python3', 'scripts/validate_model_manifest_validator.py'], check=True)
subprocess.run(['python3', 'scripts/validate_mobile_mcp_qa.py'], check=True)
subprocess.run(['python3', 'scripts/validate_mobile_mcp_run_artifacts.py', '--run-dir', 'docs/qa/mobile-mcp-samples/android/launch-offline-local-first', '--allow-sample'], check=True)
subprocess.run(['python3', 'scripts/validate_device_artifact_validators.py'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_matrix.py', '--allow-tbd'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_preflight.py', '--allow-missing', '--expect-blocked'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_matrix_validator.py'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_bundle_validator.py'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_evidence_root_validator.py'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_intake_report.py'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_evidence_intake_transition.py'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_run_template_generator.py'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_run_plan.py'], check=True)
subprocess.run(['python3', 'scripts/validate_device_lab_readiness.py'], check=True)
subprocess.run(['python3', 'scripts/validate_proof_coverage.py'], check=True)
subprocess.run(['python3', 'scripts/validate_proof_retry_decision.py'], check=True)
subprocess.run(['python3', 'scripts/validate_proof_coverage_transition.py'], check=True)
print('Device lab kit validation: PASS')
