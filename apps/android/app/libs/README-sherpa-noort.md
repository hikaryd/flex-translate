# sherpa-onnx-1.13.2-noort.aar

A repack of the vendored `sherpa-onnx-1.13.2.aar` in which sherpa's private ONNX Runtime is
**renamed** so it no longer collides with the Microsoft ONNX Runtime used for MT. (The `-noort`
filename is historical — the AAR still ships sherpa's runtime, just under a unique SONAME.)

## Why

WS4 adds on-device machine translation (M2M-100) via the official Microsoft
`com.microsoft.onnxruntime:onnxruntime-android` Java API (`OrtEnvironment`/`OrtSession`). That
artifact ships `libonnxruntime4j_jni.so` (the Java JNI) + its own `libonnxruntime.so`.

The original sherpa AAR also bundles a `libonnxruntime.so` (a different, older ORT build with a
different exported-symbol layout). They share the SONAME `libonnxruntime.so`, so only one copy can
be packaged — and on device they are NOT interchangeable:

- Keep sherpa's copy → sherpa ASR works, but the Microsoft JNI fails:
  `UnsatisfiedLinkError: cannot locate symbol "OrtGetApiBase"` (MT broken).
- Keep Microsoft's copy → Microsoft MT works, but sherpa's `libsherpa-onnx-jni.so` fails the SAME
  way (ASR broken).

Each JNI was linked against its own ORT build and only that build satisfies it.

## Fix

Give sherpa's runtime a **unique SONAME** so both ORT builds coexist in the APK, each JNI binding
its own:

- Rename sherpa `jni/<abi>/libonnxruntime.so` → `libsherpaort13.so` (same byte length, 17 chars,
  so the ELF `.dynstr` offsets are unchanged — a safe in-place rename).
- Patch the DT_SONAME of that runtime and the DT_NEEDED of `libsherpa-onnx-jni.so`,
  `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so` from `libonnxruntime.so` →
  `libsherpaort13.so` (same in-place same-length byte edit).

sherpa's Kotlin only `System.loadLibrary("sherpa-onnx-jni")` (it never loads "onnxruntime"
directly), so renaming the runtime is invisible to sherpa's API; the Android linker resolves the
patched DT_NEEDED. Microsoft's `libonnxruntime.so` stays untouched so its Java
`System.loadLibrary("onnxruntime")` and its JNI's DT_NEEDED still resolve.

Verified on device SM-S937B: sherpa recognizer init AND M2M-100 `OrtSession` init both succeed.

## Recipe (reproducible)

The repack is produced by `scripts/repack_sherpa_aar.py` (same-length `.dynstr` byte rename +
file rename, applied to every ABI). The original `sherpa-onnx-1.13.2.aar` is kept in `libs/` for
provenance.
