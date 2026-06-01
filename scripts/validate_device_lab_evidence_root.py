#!/usr/bin/env python3
"""Validate a device-lab evidence root and refresh conservative release evidence.

This is the single-command intake step for real G008/G010 proof retries. It scans
one or more result roots for evidence bundle descriptors, validates each bundle,
refreshes the generated support matrix, and (unless support claims are explicitly
allowed) reruns the no-false-support guardrail.
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any

DEFAULT_RESULTS_ROOT = Path('benchmarks/device-lab/results')
SAMPLE_BUNDLE_ROOT = Path('benchmarks/device-lab/samples/bundles')
DEFAULT_INTAKE_REPORT_JSON = Path('docs/benchmarks/device-lab-intake.generated.json')
DEFAULT_INTAKE_REPORT_MD = Path('docs/benchmarks/device-lab-intake.generated.md')


def run(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    print('RUN', ' '.join(cmd))
    return subprocess.run(cmd, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def discover_bundles(root: Path) -> list[Path]:
    if root.is_file():
        return [root] if root.name.endswith('.bundle.json') else []
    if not root.exists():
        return []
    return sorted(path for path in root.rglob('*.bundle.json') if path.is_file())


def unique_paths(paths: list[Path]) -> list[Path]:
    seen: set[str] = set()
    out: list[Path] = []
    for path in paths:
        key = str(path)
        if key not in seen:
            seen.add(key)
            out.append(path)
    return out


def validate_bundle(bundle: Path, allow_support_claims: bool) -> None:
    cmd = ['python3', 'scripts/validate_device_lab_bundle.py', '--bundle', str(bundle)]
    if allow_support_claims:
        cmd.append('--allow-support-claims')
    try:
        proc = run(cmd)
    except subprocess.CalledProcessError as exc:
        if exc.stdout:
            print(exc.stdout, end='')
        raise SystemExit(exc.returncode) from exc
    print(proc.stdout, end='')


def refresh_support_matrix(results_roots: list[Path], include_samples: bool, output: Path | None) -> tuple[int, int]:
    cmd = ['python3', 'scripts/generate_support_matrix.py']
    if results_roots != [DEFAULT_RESULTS_ROOT]:
        for root in results_roots:
            cmd.extend(['--results-root', str(root)])
    if include_samples:
        cmd.append('--include-samples')
    if output is not None:
        cmd.extend(['--output', str(output)])
    proc = run(cmd)
    print(proc.stdout, end='')
    evidence_files = supported_rows = -1
    for line in proc.stdout.splitlines():
        if line.startswith('evidence_files:'):
            evidence_files = int(line.split(':', 1)[1].strip())
        if line.startswith('supported_rows:'):
            supported_rows = int(line.split(':', 1)[1].strip())
    return evidence_files, supported_rows



def build_report(
    *,
    results_roots: list[Path],
    real_bundles: list[Path],
    sample_bundles: list[Path],
    evidence_files: int,
    supported_rows: int,
    allow_support_claims: bool,
    include_samples: bool,
    support_matrix_output: Path | None,
) -> dict[str, Any]:
    blockers: list[str] = []
    if not real_bundles:
        blockers.append('no_real_device_lab_bundles_found')
    if supported_rows <= 0:
        blockers.append('zero_supported_rows')
    if not allow_support_claims and supported_rows > 0:
        blockers.append('support_rows_present_without_allow_support_claims')
    if include_samples and not real_bundles:
        blockers.append('sample_fixtures_only_not_real_evidence')
    return {
        'schema_version': 1,
        'intake_status': 'BLOCKED' if blockers else 'READY_FOR_PROOF_REVIEW',
        'results_roots': [str(p) for p in results_roots],
        'include_samples': include_samples,
        'allow_support_claims': allow_support_claims,
        'support_matrix_output': str(support_matrix_output) if support_matrix_output else 'docs/benchmarks/support-matrix.generated.md',
        'real_bundles_found': len(real_bundles),
        'sample_bundles_validated': len(sample_bundles),
        'evidence_files': evidence_files,
        'supported_rows': supported_rows,
        'real_bundle_paths': [str(p) for p in real_bundles],
        'sample_bundle_paths': [str(p) for p in sample_bundles],
        'validated_bundle_paths': [str(p) for p in real_bundles + sample_bundles],
        'blockers': blockers,
        'non_evidence_notice': 'This intake report records validation status only; it is not ASR/MT benchmark proof, legal approval, or a support claim.',
    }


def render_report_md(report: dict[str, Any]) -> str:
    lines = [
        '# Device Lab Evidence Intake Report',
        '',
        f'**Intake status:** {report["intake_status"]}',
        f'**Allow support claims:** {str(report["allow_support_claims"]).lower()}',
        f'**Real bundles found:** {report["real_bundles_found"]}',
        f'**Sample bundles validated:** {report["sample_bundles_validated"]}',
        f'**Evidence files:** {report["evidence_files"]}',
        f'**Supported rows:** {report["supported_rows"]}',
        '',
        report['non_evidence_notice'],
        '',
        '## Results roots',
        '',
    ]
    for root in report['results_roots']:
        lines.append(f'- `{root}`')
    lines.extend(['', '## Blockers', ''])
    if report['blockers']:
        for blocker in report['blockers']:
            lines.append(f'- `{blocker}`')
    else:
        lines.append('- None detected by intake; proof coverage and human release review are still required.')
    lines.extend(['', '## Validated real bundles', ''])
    if report['real_bundle_paths']:
        for path in report['real_bundle_paths']:
            lines.append(f'- `{path}`')
    else:
        lines.append('- None')
    lines.extend(['', '## Validated sample bundles', ''])
    if report['sample_bundle_paths']:
        for path in report['sample_bundle_paths']:
            lines.append(f'- `{path}`')
    else:
        lines.append('- None')
    lines.extend([
        '',
        'Do not retry/close G008 or G010 from this report unless real bundles exist, proof coverage is READY_FOR_GOAL_REVIEW, and legal/release review approves the evidence.',
        '',
    ])
    return '\n'.join(lines)


def write_report(report: dict[str, Any], report_json: Path | None, report_md: Path | None) -> None:
    if report_json is not None:
        report_json.parent.mkdir(parents=True, exist_ok=True)
        report_json.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
        print(f'wrote: {report_json}')
    if report_md is not None:
        report_md.parent.mkdir(parents=True, exist_ok=True)
        report_md.write_text(render_report_md(report))
        print(f'wrote: {report_md}')

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--results-root', action='append', default=None, help='Device-lab result root or bundle file. May be repeated.')
    ap.add_argument('--include-samples', action='store_true', help='Also validate repository sample bundles and include sample benchmark results in the support matrix.')
    ap.add_argument('--allow-support-claims', action='store_true', help='Permit supported bundle decisions. Use only after legal/release approval.')
    ap.add_argument('--support-matrix-output', default=None, help='Override generated support-matrix output path. Use temp paths for positive transition tests.')
    ap.add_argument('--report-json', default=None, help='Write machine-readable intake report JSON.')
    ap.add_argument('--report-md', default=None, help='Write markdown intake report summary.')
    args = ap.parse_args()

    results_roots = [Path(p) for p in (args.results_root or [str(DEFAULT_RESULTS_ROOT)])]
    real_bundles = unique_paths([bundle for root in results_roots for bundle in discover_bundles(root)])
    sample_bundles = discover_bundles(SAMPLE_BUNDLE_ROOT) if args.include_samples else []

    for bundle in real_bundles:
        validate_bundle(bundle, args.allow_support_claims)
    for bundle in sample_bundles:
        validate_bundle(bundle, args.allow_support_claims)

    evidence_files, supported_rows = refresh_support_matrix(
        results_roots,
        args.include_samples,
        Path(args.support_matrix_output) if args.support_matrix_output else None,
    )

    if not args.allow_support_claims:
        proc = run(['python3', 'scripts/validate_no_false_support_claims.py'])
        print(proc.stdout, end='')
    elif supported_rows == 0:
        print('support_claims_allowed: true, but generated support matrix still has zero supported rows')

    report = build_report(
        results_roots=results_roots,
        real_bundles=real_bundles,
        sample_bundles=sample_bundles,
        evidence_files=evidence_files,
        supported_rows=supported_rows,
        allow_support_claims=args.allow_support_claims,
        include_samples=args.include_samples,
        support_matrix_output=Path(args.support_matrix_output) if args.support_matrix_output else None,
    )
    write_report(
        report,
        Path(args.report_json) if args.report_json else None,
        Path(args.report_md) if args.report_md else None,
    )

    print('Device lab evidence root intake validation: PASS')
    print(f'intake_status: {report["intake_status"]}')
    print(f'results_roots: {[str(p) for p in results_roots]}')
    print(f'real_bundles_found: {len(real_bundles)}')
    print(f'sample_bundles_validated: {len(sample_bundles)}')
    print(f'evidence_files: {evidence_files}')
    print(f'supported_rows: {supported_rows}')
    print(f'allow_support_claims: {args.allow_support_claims}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
