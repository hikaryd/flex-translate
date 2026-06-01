# Vendored llama.cpp (core, CPU backend only)

This is a pruned, pinned copy of [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp),
vendored to build the MiLMMT-46-4B (Gemma-3 architecture) quality-tier on-device MT runtime.

- **Upstream**: https://github.com/ggml-org/llama.cpp
- **Pinned commit**: `de6f727` ("llama: limit max outputs of `llama_context`", #23861)
- **License**: MIT (see `LICENSE`). MIT permits commercial use, redistribution, and modification.

## Why a recent commit

MiLMMT-46-4B is a fine-tune of `google/gemma-3-4b-pt`, so its GGUF uses the **gemma3** architecture
(`src/models/gemma3.cpp`). gemma3 GGUF support requires a recent llama.cpp — this commit has it.

## What was kept / pruned

Kept (everything needed to build `libllama.so` + `libggml*.so` on the CPU backend):

- `CMakeLists.txt`, `cmake/` — root build config + module helpers (`common.cmake`, `build-info.cmake`, …)
- `include/` — public C API (`llama.h`, `llama-cpp.h`)
- `src/` — the `llama` library (including `src/models/gemma3.cpp`)
- `ggml/CMakeLists.txt`, `ggml/cmake/`, `ggml/include/`
- `ggml/src/` core files + `ggml/src/ggml-cpu/` (CPU backend)

Pruned (not compiled for the Android CPU build — `ggml_add_backend()` only `add_subdirectory()`s a
backend when its `GGML_<BACKEND>` option is ON, and we enable only `GGML_CPU`):

- All GPU/accelerator backends: `ggml-cuda`, `ggml-metal`, `ggml-vulkan`, `ggml-opencl`,
  `ggml-hip`, `ggml-sycl`, `ggml-cann`, `ggml-musa`, `ggml-rpc`, `ggml-blas`, `ggml-webgpu`,
  `ggml-hexagon`, `ggml-zdnn`, `ggml-zendnn`, `ggml-openvino`, `ggml-virtgpu`
- `common/`, `tools/`, `examples/`, `tests/`, `pocs/`, `app/`, `vendor/` — CLI/server/test code,
  gated behind `LLAMA_BUILD_COMMON`/`_TESTS`/`_EXAMPLES`/`_TOOLS`/`_APP` (all OFF in our CMake)
- The embedded web UI (WASM/`LLAMA_BUILD_HTML`, Emscripten-only)

## How it is built

`apps/android/app/src/main/cpp/CMakeLists.txt` does `add_subdirectory(llama.cpp)` with
`LLAMA_BUILD_*`/GPU options forced OFF and `GGML_CPU=ON`, then links the thin JNI shim
(`milmmt_jni.cpp` → `libmilmmt_jni.so`) against the `llama` target. Android Gradle's
`externalNativeBuild` drives it via NDK + SDK CMake, arm64-v8a only.

## Coexistence with the other runtimes

This produces a SEPARATE `libllama.so` and does not disturb the sherpa ASR runtime
(`libsherpaort13.so`) or the Microsoft ONNX Runtime (`libonnxruntime.so`) used by the M2M-100
balanced MT path. Each JNI binds its own runtime — distinct SONAMEs, no collision.

## Re-vendoring

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git
# then copy CMakeLists.txt, cmake/, include/, src/, ggml/{CMakeLists.txt,cmake,include},
# ggml/src/{core files,ggml-cpu} as above, and refresh the pinned commit in this file.
```
