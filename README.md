# FlexTranslate

[![CI](https://github.com/eitronin1-blip/flex-translate/actions/workflows/ci.yml/badge.svg)](https://github.com/eitronin1-blip/flex-translate/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/eitronin1-blip/flex-translate?label=release)](https://github.com/eitronin1-blip/flex-translate/releases/latest)

Offline-first live speech transcription and tiered dialogue translation. Speak — get transcribed and translated in real time, entirely on-device. No subscription, no embedded API keys.

## Features

- **Live speech transcription** via sherpa-onnx (RU, EN, ZH) — fully offline, runs on-device
- **Dialogue translation** — two-speaker conversation log with per-turn MT
- **Tiered MT with AUTO routing**:
  - **M2M-100 offline** — Meta's 418 M-param multilingual model; fast, ~100 ms/sentence
  - **MiLMMT-4B offline** — GGUF-quantised LLM quality; slower, richest output
  - **Gemini Flash cloud** — opt-in, requires user's own API key (BYOK); AUTO mode selects it when online
- **In-app model download manager** — models are never bundled; downloaded on demand with resume + SHA-256 verification
- **Aquacard dark design system** — custom design tokens, Compose Material 3, SwiftUI parity
- **RU / EN UI localisation** with runtime switcher

Supported translation directions: RU↔EN, RU↔ZH, EN↔ZH.

## Architecture

```
flex-translate/
├── apps/
│   ├── android/          # Jetpack Compose + Kotlin; sherpa-onnx AAR; onnxruntime-android
│   └── ios/              # SwiftUI; sherpa-onnx xcframework; llama.cpp xcframework
├── configs/              # Model registry JSON (specs, SHA-256, download URLs)
├── schemas/              # JSON schema for model specs
├── docs/                 # QA artefacts, benchmarks
└── scripts/              # Utility scripts (repack, validate)
```

### Android (`apps/android`)

Jetpack Compose single-activity app. Key layers:

- **Audio pipeline** — `AudioPipeline` (AudioRecord + VAD) → sherpa-onnx `OnlineRecognizer`
- **MT providers** — `M2m100MtProvider` (ORT), `MilmmtMtProvider` (JNI → libllama.so), `GeminiFlashTranslationProvider` (HTTP)
- **MT routing** — `LiveSessionState` selects provider based on `MtRoutingMode` (MANUAL / AUTO)
- **Model download** — `ModelDownloadManager` (OkHttp resume, SHA-256, atomic rename)
- **Gemini BYOK** — key stored in `EncryptedSharedPreferences`; never committed

### iOS (`apps/ios`)

SwiftUI app with feature parity. Key layers mirror Android. Uses `project.yml` + XcodeGen; the `.xcodeproj` is gitignored.

## Build

### Android

Requirements: JDK 17, Android SDK (API 28+), NDK 26.1.10909125.

```bash
# Fetch prebuilt llama.cpp arm64 .so (required for MiLMMT tier)
cd apps/android
bash scripts/fetch_llama_prebuilt.sh

# Debug APK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```

> **JDK note**: Gradle requires JDK 17. Set `JAVA_HOME` as shown above or configure it in `apps/android/local.properties`.

### iOS

Requirements: Xcode 15+, [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
# Install XcodeGen if needed
brew install xcodegen

cd apps/ios

# Fetch prebuilt xcframeworks (sherpa-onnx + onnxruntime + llama.cpp)
bash scripts/fetch_sherpa_ios.sh
bash scripts/fetch_llama_ios.sh

# Generate Xcode project
xcodegen generate

# Build (simulator)
xcodebuild -scheme FlexTranslate \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  build
```

## iOS install via SideStore

SideStore re-signs the unsigned IPA with your own Apple ID — no developer account, no 7-day expiry.

1. **Install SideStore** (one-time, needs a computer): follow the guide at [sidestore.io](https://sidestore.io). After the initial setup SideStore re-signs apps over Wi-Fi; no USB needed again.
2. **Download the IPA** from the [latest release page](https://github.com/eitronin1-blip/flex-translate/releases/latest) onto your iPhone (`FlexTranslate-vX.Y.Z-unsigned.ipa`).
3. **In SideStore** → My Apps → **"+"** → navigate to the `.ipa` file.
4. SideStore signs it with your Apple ID and installs it. It auto-renews in the background — no repeated 7-day reinstall.

## Models

Models are **not bundled** — they are downloaded in-app from the Models screen.

| Model | Size | Tier | License |
|-------|------|------|---------|
| sherpa-onnx zipformer (RU/EN/ZH) | ~40–60 MB each | ASR | Apache-2.0 |
| M2M-100 ONNX | ~390 MB | MT offline fast | MIT |
| MiLMMT-46-4B Q6_K GGUF | ~3.5 GB | MT offline quality | [Gemma terms](https://ai.google.dev/gemma/terms) |

> **Gemma / MiLMMT license**: The MiLMMT-4B model is derived from Gemma 3. Use of this model is subject to the [Gemma Terms of Service](https://ai.google.dev/gemma/terms). By downloading the model in-app you accept those terms. The app displays the license disclosure before download.

## Known limitations (v0.1.0)

- MiLMMT-4B on-device load: fix for cold-start GGML backend initialisation on Samsung S25-class devices is pending a device re-run verification.
- iOS SideStore distribution is unsigned; the app requests microphone permission at first launch.
