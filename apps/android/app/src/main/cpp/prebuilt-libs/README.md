# Prebuilt llama.cpp Android libraries (MiLMMT quality-tier MT runtime)

This directory holds the official prebuilt **llama.cpp** Android `arm64-v8a` shared libraries that
back the MiLMMT-46-4B (Gemma-3) quality-tier on-device MT path. The `.so` blobs (~78 MB) are
**git-ignored** (see root `.gitignore`) and fetched on demand — only this README and the build glue
are committed.

## Why a prebuilt instead of building llama.cpp from source

The original integration vendored llama.cpp source under `cpp/llama.cpp/` and built it via
`externalNativeBuild` pinned to NDK `26.1.10909125`. That NDK install (via `sdkmanager`) hung and
failed, leaving a 4 KB stub, so the source compile cannot run (task #30). The official llama.cpp
release ships a prebuilt Android arm64 `libllama.so` whose exported API exactly matches what our JNI
shim (`../milmmt_jni.cpp`) calls (verified — all 21 `llama_*` symbols present), so we link the
prebuilt and compile only the thin shim.

## How to populate

```bash
apps/android/scripts/fetch_llama_prebuilt.sh
```

This downloads the pinned `ggml-org/llama.cpp` release asset (`b9453`,
`llama-b9453-bin-android-arm64.tar.gz`) via the GitHub CLI, extracts the needed `.so` files into
`arm64-v8a/`, and verifies the symbol set against `cpp/milmmt_jni.cpp`.

Libraries staged here:
- `libllama.so` — the llama API the shim links against
- `libggml.so`, `libggml-base.so` — ggml core (backend dispatcher + base ops)
- `libggml-cpu-android_armv8.0/8.2/8.6/9.0/9.2_*.so` — CPU feature variants; `libggml.so` `dlopen`s
  the best match for the device at runtime

## How to activate the native MiLMMT build

Both pieces below are currently disabled so the deferred-state baseline APK stays lean and builds
without an NDK. To turn the prebuilt MiLMMT runtime on:

1. Install any modern NDK (e.g. `r26b`) — NOT via the failed `sdkmanager` 26.1.10909125 path.
2. In `app/build.gradle.kts`, re-enable the `externalNativeBuild` blocks but point them at the
   prebuilt CMake: `path = file("src/main/cpp/CMakeLists.prebuilt.txt")`, restrict to `arm64-v8a`,
   and add `sourceSets["main"].jniLibs.srcDir("src/main/cpp/prebuilt-libs")` so AGP packages these
   `.so` into the APK.
3. Rebuild. `LlamaCppBridge.isAvailable` becomes true once `libmilmmt_jni.so` loads, and the MiLMMT
   provider runs real GGUF inference instead of reporting "runtime ещё не установлен".
