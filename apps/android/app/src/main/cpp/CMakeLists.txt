# CMake for the MiLMMT (Gemma-3) quality-tier MT JNI bridge.
#
# Builds the vendored llama.cpp core (libggml*.so + libllama.so) for the device ABI (arm64-v8a)
# plus our thin JNI shim (libmilmmt_jni.so). CPU backend only — no GPU backends are enabled, so the
# pruned ggml-cuda/metal/vulkan/... directories are never referenced (see ggml_add_backend()).
#
# This produces a SEPARATE libllama.so; it does NOT touch the sherpa ASR runtime
# (libsherpaort13.so) or the Microsoft ONNX Runtime (libonnxruntime.so) used for the M2M-100
# balanced MT path. Each JNI binds its own runtime; no SONAME collision.

cmake_minimum_required(VERSION 3.22.1)
project(milmmt_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- llama.cpp build options: core libraries only, CPU backend, no extras. ---
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# ggml: CPU backend only; everything else off. GGML_NATIVE OFF so the NDK cross-compile does not
# probe the host CPU. ARM dotprod/i8mm are enabled by the toolchain for arm64-v8a automatically.
set(GGML_CPU             ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_BLAS            OFF CACHE BOOL "" FORCE)
set(GGML_CUDA            OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL          OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(milmmt_jni SHARED milmmt_jni.cpp)

target_link_libraries(milmmt_jni
    llama
    android
    log)
