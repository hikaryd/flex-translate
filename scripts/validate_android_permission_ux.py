#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

MAIN = Path('apps/android/app/src/main/java/dev/flextranslate/foundation/MainActivity.kt')
MANIFEST = Path('apps/android/app/src/main/AndroidManifest.xml')
BUILD = Path('apps/android/app/build.gradle.kts')
for path in [MAIN, MANIFEST, BUILD]:
    if not path.is_file():
        raise SystemExit(f'missing: {path}')

main = MAIN.read_text()
manifest = MANIFEST.read_text()
build = BUILD.read_text()

# The launch screen must not immediately throw the Android permission dialog before
# showing context; the request should be tied to an explicit user action.
on_create = main.split('override fun onCreate', 1)[1].split('override fun onRequestPermissionsResult', 1)[0]
assert 'requestPermissions(' not in on_create, 'do not auto-request microphone permission in onCreate'
assert 'render()' in on_create, 'onCreate must render explanatory UI first'
assert 'onResume()' in main and 'render()' in main.split('override fun onResume', 1)[1].split('override fun onSaveInstanceState', 1)[0], 'onResume must rerender permission state after settings/dialog return'
assert 'onSaveInstanceState' in main and 'putBoolean(localModeOpenedKey, localModeOpened)' in main, 'local continuation state must survive Activity recreation'
assert 'savedInstanceState?.getBoolean(localModeOpenedKey, false)' in main, 'local continuation state restore missing'
assert 'onRequestPermissionsResult' in main and 'render()' in main.split('onRequestPermissionsResult', 1)[1], 'permission result must rerender UI'
assert 'primaryButton("Разрешить микрофон")' in main, 'missing explicit microphone permission button'
assert 'requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)' in main, 'button must request only RECORD_AUDIO'

for token in [
    'Flex Translate',
    'Нужен доступ к микрофону',
    'Микрофон готов',
    'После разрешения микрофона экран обновится сам',
    'Cloud STT/Gemini выключены по умолчанию',
    'Продолжить в локальный режим',
    'Локальный режим открыт',
    'приложение не застряло после permission',
    'Нет silent fallback',
    'support matrix',
]:
    assert token in main, token
assert 'local debug shell' not in main, 'old scary debug shell copy returned'
assert 'setContentView(scroll)' in main, 'main UI should be scrollable for small screens'
assert 'private var localModeOpened = false' in main, 'local continuation state missing'
assert 'localModeOpened = true' in main, 'enabled local continuation action missing'
assert 'primaryButton("Продолжить в локальный режим")' in main, 'post-permission continue button must be enabled'
assert 'GradientDrawable' in main and 'cornerRadius' in main, 'card styling guardrail missing'
assert 'android.permission.RECORD_AUDIO' in manifest, 'microphone permission missing'
assert 'android.permission.INTERNET' not in manifest, 'foundation shell must not request INTERNET'
assert 'jvmTarget = "17"' in build, 'Android Kotlin JVM target should stay deterministic'
print('Android permission UX validation: PASS')
