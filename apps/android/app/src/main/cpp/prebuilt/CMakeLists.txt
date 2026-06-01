# CMake for the MiLMMT (Gemma-3) quality-tier MT JNI bridge — PREBUILT llama.cpp variant.
#
# This is the ACTIVE CMake entry point for the MiLMMT native build. It must be named `CMakeLists.txt`
# (Android Gradle Plugin rejects any other filename via [CXX1400]), so it lives in its own `prebuilt/`
# subdirectory to coexist with the from-source `../CMakeLists.txt` (which stays UNUSED). The narrative
# header in `../CMakeLists.prebuilt.txt` documents the same approach; this file is its build-usable twin.
#
# Unlike ../CMakeLists.txt (which builds the vendored llama.cpp from source and needs a heavy NDK
# toolchain compile), this variant links the OFFICIAL prebuilt llama.cpp Android arm64 shared
# libraries staged in cpp/prebuilt-libs/<abi>/ (from the ggml-org/llama.cpp release `b9453`:
# libllama.so + libggml*.so + libggml-cpu-*.so). It compiles ONLY our thin JNI shim
# (milmmt_jni.cpp -> libmilmmt_jni.so) against the matching b9453 headers (cpp/prebuilt-include/),
# so the build is fast and does NOT require building llama.cpp itself.
#
# Why a prebuilt: the source build's pinned NDK (26.1.10909125) failed to install via sdkmanager the
# first time (left a 4 KB stub); a fresh sdkmanager install then succeeded, giving us clang 17 — but
# we still skip the multi-minute from-source llama.cpp compile because the release prebuilt's
# libllama.so already exports every symbol milmmt_jni.cpp calls (verified by verify_llama_symbols.py),
# making the shim drop-in compatible. We only use the NDK clang to cross-compile the one .cpp file.

cmake_minimum_required(VERSION 3.22.1)
project(milmmt_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# This file lives in cpp/prebuilt/; the shim, headers and prebuilt .so live one level up in cpp/.
get_filename_component(CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)

# Prebuilt llama.cpp arm64 shared libs live in cpp/prebuilt-libs/<abi>/ (kept OUT of jniLibs so the
# deferred-state baseline APK did not package ~78 MB of unused .so; build.gradle.kts now adds this dir
# as a jniLibs srcDir so AGP packages them). We import libllama.so as the link target; its DT_NEEDED
# pulls libggml.so + libggml-base.so, and libggml.so dlopen()s the right libggml-cpu-*.so at runtime
# by CPU feature detection.
set(PREBUILT_DIR "${CPP_DIR}/prebuilt-libs/${ANDROID_ABI}")

add_library(llama_prebuilt SHARED IMPORTED)
set_target_properties(llama_prebuilt PROPERTIES IMPORTED_LOCATION "${PREBUILT_DIR}/libllama.so")

# b9453 headers (llama.h pulls ggml.h). Fetched from the matching release tag.
include_directories("${CPP_DIR}/prebuilt-include")

add_library(milmmt_jni SHARED "${CPP_DIR}/milmmt_jni.cpp")

target_link_libraries(milmmt_jni
    llama_prebuilt
    android
    log)
