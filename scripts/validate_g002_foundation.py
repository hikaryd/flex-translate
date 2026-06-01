#!/usr/bin/env python3
from pathlib import Path
import json

required_files = [
    'docs/architecture/mobile-foundation.md',
    'schemas/telemetry-event.schema.json',
    'apps/android/settings.gradle.kts',
    'apps/android/app/src/main/AndroidManifest.xml',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/MainActivity.kt',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/AudioCaptureController.kt',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/ProviderAdapters.kt',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/OfflineFirstState.kt',
    'apps/android/app/src/main/java/dev/flextranslate/foundation/TelemetryEvent.kt',
    'apps/ios/FlexTranslate/Info.plist',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/FlexTranslateApp.swift',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/AudioCaptureController.swift',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/ProviderAdapters.swift',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/OfflineFirstState.swift',
    'apps/ios/FlexTranslate/Sources/FlexTranslate/TelemetryEvent.swift',
]
missing = [p for p in required_files if not Path(p).is_file()]
if missing:
    raise SystemExit(f"missing files: {missing}")

schema = json.loads(Path('schemas/telemetry-event.schema.json').read_text())
for field in ['session_id', 'monotonic_ts_ms', 'event_type', 'device_tier', 'mode', 'network_state']:
    assert field in schema['required'], field

android_manifest = Path('apps/android/app/src/main/AndroidManifest.xml').read_text()
assert 'android.permission.RECORD_AUDIO' in android_manifest
assert 'android.permission.INTERNET' not in android_manifest, 'G002 core shell must not request Internet permission'

ios_plist = Path('apps/ios/FlexTranslate/Info.plist').read_text()
assert 'NSMicrophoneUsageDescription' in ios_plist

combined = '\n'.join(Path(p).read_text(errors='ignore') for p in required_files)
for token in ['AsrProvider', 'TranslationProvider', 'CloudProvider', 'MissingOfflinePack', 'UnsupportedOfflineTranslation', 'CloudDisabled']:
    assert token in combined, token

print('G002 foundation validation: PASS')
print(f'validated_files: {len(required_files)}')
