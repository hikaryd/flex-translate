# FlexTranslate

[![CI](https://github.com/hikaryd/flex-translate/actions/workflows/ci.yml/badge.svg)](https://github.com/hikaryd/flex-translate/actions/workflows/ci.yml)
[![Релиз](https://img.shields.io/github/v/release/hikaryd/flex-translate?label=релиз)](https://github.com/hikaryd/flex-translate/releases/latest)

Переводчик живой речи, который работает офлайн. Говоришь — на лету получаешь расшифровку и перевод, всё прямо на телефоне. Без подписок и без зашитых в приложение ключей.

Задача — живой **диалог**: два собеседника на разных языках, реплики распознаются и переводятся в обе стороны.

## Что умеет

- **Распознавание речи** через sherpa-onnx — русский, английский, китайский. Полностью офлайн, на устройстве.
- **Диалоговый режим** — лента разговора: каждая реплика показывается с переводом на язык собеседника.
- **Перевод с автоподбором движка**:
  - **M2M-100** (офлайн) — модель Meta на 418М параметров. Быстрый, ~0.5–0.8 с на фразу после прогрева. Базовый офлайн-движок.
  - **MiLMMT-4B** (офлайн) — 4B-модель в GGUF для лучшего качества. Тяжёлая, для топовых устройств.
  - **Gemini Flash** (облако) — по желанию, при интернете. Свой ключ (BYOK) или через бэкенд. В режиме AUTO выбирается автоматически, когда есть сеть.
- **Загрузка моделей в приложении** — веса не зашиты в APK, качаются по запросу с докачкой и проверкой SHA-256.
- **Тёмный дизайн aquacard** — свои токены, Compose Material 3 на Android и тот же стиль на SwiftUI.
- **Язык интерфейса RU/EN** с переключателем прямо в приложении.

Направления перевода: RU↔EN, RU↔ZH (русский — опорный язык диалога).

## Как устроено

```
flex-translate/
├── apps/
│   ├── android/          # Jetpack Compose + Kotlin; sherpa-onnx (AAR), onnxruntime, llama.cpp
│   └── ios/              # SwiftUI; те же движки через xcframework
├── configs/              # Реестр моделей (id, размеры, SHA-256, ссылки на загрузку)
├── schemas/              # JSON-схемы манифестов
├── docs/                 # Планы, дизайн, QA, бенчмарки
└── scripts/              # Вспомогательные скрипты (fetch, validate)
```

Аудио идёт через `AudioPipeline` (захват + VAD) в sherpa-onnx. Перевод выбирает `LiveSessionState` по режиму `MtRoutingMode` (AUTO / on-device / cloud): онлайн и с согласием — Gemini, иначе выбранная локальная модель. Загрузку моделей ведёт `ModelDownloadManager` (докачка по Range, SHA-256, атомарная замена). Ключ Gemini хранится в `EncryptedSharedPreferences` (Android) / Keychain (iOS) и в репозиторий не попадает. iOS повторяет ту же архитектуру на SwiftUI; проект собирается через XcodeGen из `project.yml` (сам `.xcodeproj` в гите не хранится).

## Сборка

### Android

Нужны: JDK 17, Android SDK (API 28+), NDK 26.1.10909125.

```bash
cd apps/android
bash scripts/fetch_llama_prebuilt.sh          # пребилт llama.cpp arm64 для MiLMMT

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Важно: Gradle здесь требует именно JDK 17. Системный `java` (новее) и системный gradle (новее) не подойдут — путь к JDK 17 задаётся через `JAVA_HOME`, как выше.

### iOS

Нужны: Xcode 15+, XcodeGen (`brew install xcodegen`).

```bash
cd apps/ios
bash scripts/fetch_sherpa_ios.sh              # sherpa-onnx + onnxruntime
bash scripts/fetch_llama_ios.sh               # llama.cpp
xcodegen generate
xcodebuild -scheme FlexTranslate -destination 'platform=iOS Simulator,name=iPhone 17' build
```

## Установка на iPhone через SideStore

IPA в релизе — без подписи. SideStore переподпишет её твоим Apple ID прямо на телефоне: без аккаунта разработчика и без перестановки каждые 7 дней.

1. Поставь **SideStore** по инструкции с [sidestore.io](https://sidestore.io) (один раз настраивается через компьютер; дальше переподпись идёт сама по Wi-Fi).
2. Скачай IPA со [страницы релиза](https://github.com/hikaryd/flex-translate/releases/latest) прямо на iPhone (`FlexTranslate-vX.Y.Z-unsigned.ipa`).
3. В SideStore: **My Apps → «+» →** выбери `.ipa`.
4. SideStore подпишет приложение твоим Apple ID и поставит. Подпись продлевается в фоне — переустанавливать каждые 7 дней не нужно.

## Модели

Веса **не входят в APK** — качаются в приложении, на экране «Модели».

| Модель | Размер | Назначение | Лицензия |
|--------|--------|------------|----------|
| sherpa-onnx zipformer (RU/EN/ZH) | ~40–180 МБ | распознавание речи | Apache-2.0 |
| M2M-100 (ONNX) | ~390 МБ | офлайн-перевод, быстрый | MIT |
| MiLMMT-46-4B Q6_K (GGUF) | ~3.5 ГБ | офлайн-перевод, качество | [условия Gemma](https://ai.google.dev/gemma/terms) |

MiLMMT-4B построена на Gemma 3, поэтому её использование подчиняется [условиям Gemma](https://ai.google.dev/gemma/terms). Приложение показывает это перед загрузкой; скачивая модель, ты принимаешь условия.

## Ограничения v0.1.0

- **MiLMMT-4B на устройстве пока не запускается**: prebuilt llama.cpp (b9453) на Android не регистрирует ggml-backend (CPU-`.so` без SONAME). Фикс написан и компилируется, но ещё не проверен прогоном на устройстве. Офлайн-перевод работает через M2M-100, онлайн — через Gemini.
- IPA для iOS идёт без подписи (ставится через SideStore). При первом запуске приложение просит доступ к микрофону.
