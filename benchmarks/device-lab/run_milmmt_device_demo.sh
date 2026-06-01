#!/usr/bin/env bash
# WS4/G005 A2 device demo for the MiLMMT-46-4B (Gemma-3) quality MT tier on SM-S937B.
#
# Pushes the Q6_K GGUF into the app's INTERNAL filesDir (run-as cp — Android 11+ scoped storage
# hides another app's external dir from `adb shell`, exactly like the ASR/M2M packs), then leaves
# the app ready for: pick "MiLMMT-46 4B (качество)" in the picker → run the RU WAV self-test
# (ASR decodes ru_0.wav → MiLMMT translates RU->EN). Captures logcat + a screenshot afterwards.
#
# Real model output only: the screenshot/logcat record the genuine MiLMMT translation. Nothing here
# fabricates text.
set -euo pipefail

DEVICE="R5CY61166KE"
PKG="dev.flextranslate"
MODEL_ID="milmmt-46-4b-q6"
GGUF_NAME="MiLMMT-46-4B-v0.1.Q6_K.gguf"
HOST_GGUF="${HOME}/llama-build/models/${GGUF_NAME}"
RESULTS="$(cd "$(dirname "$0")" && pwd)/results"
ADB=(adb -s "$DEVICE")

echo "== 1. push GGUF to /sdcard tmp =="
"${ADB[@]}" push "$HOST_GGUF" "/sdcard/${GGUF_NAME}"

echo "== 2. copy into internal filesDir via run-as =="
"${ADB[@]}" shell run-as "$PKG" mkdir -p "files/models/${MODEL_ID}"
"${ADB[@]}" shell "run-as $PKG sh -c 'cat /sdcard/${GGUF_NAME} > files/models/${MODEL_ID}/${GGUF_NAME}'"
"${ADB[@]}" shell rm "/sdcard/${GGUF_NAME}"

echo "== 3. verify on device =="
"${ADB[@]}" shell run-as "$PKG" ls -la "files/models/${MODEL_ID}/"

echo "== 4. clear logcat, launch app =="
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

cat <<'EOF'

== MANUAL STEPS in the app ==
  1. Языки → Модель перевода → tap "MiLMMT-46 4B (качество, на устройстве)".
  2. Live → set RU → EN → tap the WAV self-test (feeds ru_0.wav through ASR, then MiLMMT MT).
  3. Wait for the translation (4B on mobile is slow — seconds).

Then run:
  adb -s R5CY61166KE logcat -d | grep -E "MilmmtJni|MilmmtMtProvider|LlamaCppBridge|llama" \
      > benchmarks/device-lab/results/ws4-a2-milmmt-logcat.txt
  adb -s R5CY61166KE exec-out screencap -p > benchmarks/device-lab/results/ws4-a2-milmmt-translation.png
EOF
mkdir -p "$RESULTS"
