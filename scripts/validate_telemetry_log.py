#!/usr/bin/env python3
"""Validate FlexTranslate device-lab telemetry JSONL evidence.

The validator is intentionally conservative: telemetry can prove that a run is
well-formed enough to evaluate, but it never converts a benchmark into a support
claim. Support decisions remain owned by benchmark-result JSON plus release gate.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

REQUIRED_FIELDS = [
    'session_id',
    'monotonic_ts_ms',
    'event_type',
    'device_tier',
    'device_model',
    'os_version',
    'runtime_id',
    'model_id',
    'language_pair',
    'mode',
    'network_state',
    'app_build',
]
EVENT_TYPES = {
    'audio_callback_received',
    'vad_speech_start',
    'vad_speech_end',
    'asr_partial_emitted',
    'asr_final_emitted',
    'mt_request_started',
    'mt_result_emitted',
    'ui_transcript_rendered',
    'ui_translation_rendered',
    'memory_sample',
    'battery_sample',
    'thermal_sample',
    'network_request_attempted',
}
DEVICE_TIERS = {'low', 'mid', 'high', 'unknown'}
MODES = {'offline', 'cloud_stt', 'gemini_live', 'gemini_batch'}
NETWORK_STATES = {'offline', 'online', 'flaky', 'unknown'}
LANGUAGE_PAIR_RE = re.compile(r'^[a-z]{2,3}(-[A-Z]{2})?->[a-z]{2,3}(-[A-Z]{2})?$|^none$')


def parse_required_event(value: str) -> tuple[str, int]:
    if '=' not in value:
        raise argparse.ArgumentTypeError('expected EVENT_TYPE=COUNT')
    name, count = value.split('=', 1)
    if name not in EVENT_TYPES:
        raise argparse.ArgumentTypeError(f'unknown event type: {name}')
    try:
        parsed_count = int(count)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f'invalid count: {count}') from exc
    if parsed_count < 1:
        raise argparse.ArgumentTypeError('count must be >= 1')
    return name, parsed_count


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for line_no, raw in enumerate(path.read_text().splitlines(), start=1):
        if not raw.strip():
            continue
        try:
            event = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise SystemExit(f'{path}:{line_no}: invalid JSON: {exc}') from exc
        if not isinstance(event, dict):
            raise SystemExit(f'{path}:{line_no}: event must be an object')
        event['_line_no'] = line_no
        events.append(event)
    if not events:
        raise SystemExit(f'{path}: no telemetry events')
    return events


def validate_event(event: dict[str, Any], source: Path) -> None:
    line_no = event['_line_no']
    missing = [field for field in REQUIRED_FIELDS if field not in event]
    if missing:
        raise SystemExit(f'{source}:{line_no}: missing required fields: {missing}')

    for field in ['session_id', 'device_model', 'os_version', 'runtime_id', 'model_id', 'app_build']:
        if not isinstance(event[field], str) or not event[field].strip():
            raise SystemExit(f'{source}:{line_no}: {field} must be a non-empty string')

    if not isinstance(event['monotonic_ts_ms'], int) or event['monotonic_ts_ms'] < 0:
        raise SystemExit(f'{source}:{line_no}: monotonic_ts_ms must be a non-negative integer')
    if event['event_type'] not in EVENT_TYPES:
        raise SystemExit(f'{source}:{line_no}: unknown event_type {event["event_type"]!r}')
    if event['device_tier'] not in DEVICE_TIERS:
        raise SystemExit(f'{source}:{line_no}: invalid device_tier {event["device_tier"]!r}')
    if event['mode'] not in MODES:
        raise SystemExit(f'{source}:{line_no}: invalid mode {event["mode"]!r}')
    if event['network_state'] not in NETWORK_STATES:
        raise SystemExit(f'{source}:{line_no}: invalid network_state {event["network_state"]!r}')
    if not isinstance(event['language_pair'], str) or not LANGUAGE_PAIR_RE.match(event['language_pair']):
        raise SystemExit(f'{source}:{line_no}: invalid language_pair {event["language_pair"]!r}')
    payload = event.get('payload', {})
    if payload is not None and not isinstance(payload, dict):
        raise SystemExit(f'{source}:{line_no}: payload must be an object when present')


def validate_monotonic(events: list[dict[str, Any]], source: Path) -> None:
    last_ts_by_session: dict[str, int] = {}
    for event in events:
        session_id = event['session_id']
        ts = event['monotonic_ts_ms']
        if session_id in last_ts_by_session and ts < last_ts_by_session[session_id]:
            raise SystemExit(
                f'{source}:{event["_line_no"]}: non-monotonic timestamp in session '
                f'{session_id!r}: {ts} < {last_ts_by_session[session_id]}'
            )
        last_ts_by_session[session_id] = ts


def validate_session_correlation(events: list[dict[str, Any]], source: Path) -> None:
    signatures: dict[str, set[tuple[str, str, str, str, str]]] = defaultdict(set)
    for event in events:
        signatures[event['session_id']].add((
            event['device_model'],
            event['os_version'],
            event['runtime_id'],
            event['model_id'],
            event['app_build'],
        ))
    for session_id, seen in signatures.items():
        if len(seen) > 1:
            raise SystemExit(f'{source}: session {session_id!r} mixes device/model/runtime/build identifiers')


def validate_offline_no_network(events: list[dict[str, Any]], source: Path) -> None:
    for event in events:
        if event['mode'] == 'offline' and event['event_type'] == 'network_request_attempted':
            raise SystemExit(f'{source}:{event["_line_no"]}: offline telemetry contains network_request_attempted')
        if event['mode'] == 'offline' and event['network_state'] != 'offline':
            raise SystemExit(f'{source}:{event["_line_no"]}: offline telemetry must report network_state=offline')


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument('--log', required=True, help='Telemetry JSONL file')
    ap.add_argument(
        '--require-event-count',
        action='append',
        default=[],
        type=parse_required_event,
        metavar='EVENT_TYPE=COUNT',
        help='Require at least COUNT occurrences of EVENT_TYPE; use for p95 sample sufficiency gates.',
    )
    ap.add_argument('--offline-no-network', action='store_true', help='Reject network attempts and non-offline network_state in offline runs')
    args = ap.parse_args()

    source = Path(args.log)
    events = load_jsonl(source)
    for event in events:
        validate_event(event, source)
    validate_monotonic(events, source)
    validate_session_correlation(events, source)
    if args.offline_no_network:
        validate_offline_no_network(events, source)

    counts = Counter(event['event_type'] for event in events)
    missing_counts = [f'{name}={minimum} (saw {counts.get(name, 0)})' for name, minimum in args.require_event_count if counts.get(name, 0) < minimum]
    if missing_counts:
        raise SystemExit(f'{source}: insufficient telemetry samples for p95/coverage gates: {missing_counts}')

    print('Telemetry log validation: PASS')
    print(f'events: {len(events)}')
    print(f'sessions: {len(set(event["session_id"] for event in events))}')
    for name, count in sorted(counts.items()):
        print(f'{name}: {count}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
