# Android permission UX runtime evidence

This captures real-device evidence for the Android launch/permission UX fix. It is UI evidence only; it is **not** ASR/MT benchmark, legal, battery/thermal, or support-claim proof.

## One-command capture

With one Android device/emulator visible in `adb devices -l`, run:

```sh
cd /Users/tronin.egor/Documents/dev/flex-translate
python3 scripts/capture_android_permission_ux_run.py
```

The script builds and installs the debug APK, clears app state, captures the pre-permission screen, taps `Разрешить микрофон`, grants the Android microphone dialog when possible, taps `Продолжить в локальный режим`, writes screenshots/XML/logcat/assertions under `docs/qa/android-permission-ux-runs/<timestamp>/`, and finally runs:

```sh
python3 scripts/validate_android_permission_ux_run.py --run-dir <that-run-dir>
```

If several devices are attached, pass the serial:

```sh
python3 scripts/capture_android_permission_ux_run.py --serial <adb-serial>
```

If the system permission dialog cannot be tapped automatically because of OEM/localized UI, approve it manually and rerun the validator against the produced run directory, or use the manual flow below.

## Build and install

```sh
cd /Users/tronin.egor/Documents/dev/flex-translate
gradle -p apps/android :app:assembleDebug
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear dev.flextranslate
```

## Capture before permission

```sh
RUN_DIR=docs/qa/android-permission-ux-runs/$(date +%Y-%m-%d-%H%M%S)
mkdir -p "$RUN_DIR"

cat > "$RUN_DIR/device.json" <<EOF
{
  "platform": "android",
  "device_model": "$(adb shell getprop ro.product.model | tr -d '\r')",
  "android_release": "$(adb shell getprop ro.build.version.release | tr -d '\r')",
  "android_sdk": "$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
}
EOF

adb logcat -c
adb shell am start -n dev.flextranslate/.foundation.MainActivity
sleep 2
adb exec-out screencap -p > "$RUN_DIR/screenshot-before-permission.png"
adb shell uiautomator dump /sdcard/flex-before.xml
adb pull /sdcard/flex-before.xml "$RUN_DIR/screen-before-permission.xml"
```

Expected before state: the app screen is visible first, with `Нужен доступ к микрофону` and the `Разрешить микрофон` button. The Android permission dialog should not appear until the button is tapped.

## Grant permission and capture after state

Tap the app button `Разрешить микрофон`, approve the Android microphone permission dialog, then run:

```sh
sleep 1
adb exec-out screencap -p > "$RUN_DIR/screenshot-after-permission.png"
adb shell uiautomator dump /sdcard/flex-after.xml
adb pull /sdcard/flex-after.xml "$RUN_DIR/screen-after-permission.xml"
```

Expected after state: the screen says `Микрофон готов`, explains that the local ASR provider/model is not yet connected, and keeps cloud disabled by default.

## Capture local-mode continuation

Tap the app button `Продолжить в локальный режим`, then run:

```sh
sleep 1
adb exec-out screencap -p > "$RUN_DIR/screenshot-local-mode.png"
adb shell uiautomator dump /sdcard/flex-local.xml
adb pull /sdcard/flex-local.xml "$RUN_DIR/screen-local-mode.xml"
adb logcat -d -v time > "$RUN_DIR/logcat.txt"
cat > "$RUN_DIR/assertions.json" <<'EOF'
{
  "microphone_permission_only": true,
  "no_permission_dialog_before_button_tap": true,
  "before_screen_has_permission_explanation": true,
  "after_grant_screen_has_microphone_ready": true,
  "local_mode_continue_unblocked": true,
  "cloud_default_disabled": true,
  "support_claims_not_visible": true,
  "asr_mt_support_not_claimed": true
}
EOF

python3 scripts/validate_android_permission_ux_run.py --run-dir "$RUN_DIR"
```

Expected local-mode state: the screen says `Локальный режим открыт`, confirms that the app did not get stuck after permission, and still does not claim ASR/MT support without benchmark evidence.
