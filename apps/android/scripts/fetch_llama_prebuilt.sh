#!/usr/bin/env bash
# Fetch the official prebuilt llama.cpp Android arm64 shared libraries needed by the MiLMMT
# (Gemma-3) quality-tier MT JNI bridge, and stage them under app/src/main/cpp/prebuilt-libs/arm64-v8a/.
#
# These are large binary blobs (~78 MB total) so they are git-ignored, not committed. This script
# makes the prebuilt MiLMMT runtime path reproducible: it downloads a pinned llama.cpp release asset
# (ggml-org/llama.cpp), extracts only the .so files the bridge links/loads at runtime, and verifies
# that libllama.so exports every symbol cpp/milmmt_jni.cpp calls.
#
# Pinned to release b9453: its libllama.so exports the full modern API the shim uses
# (llama_model_load_from_file, llama_init_from_model, llama_get_memory, llama_sampler_*, ...), and
# the matching b9453 headers are vendored under cpp/prebuilt-include/.
#
# Requires: gh (GitHub CLI, authenticated), tar, python3.
set -euo pipefail

TAG="b9453"
ASSET="llama-${TAG}-bin-android-arm64.tar.gz"
REPO="ggml-org/llama.cpp"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST="${APP_DIR}/app/src/main/cpp/prebuilt-libs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

echo "Resolving ${ASSET} from ${REPO} release ${TAG}..."
ASSET_URL="$(gh api "repos/${REPO}/releases/tags/${TAG}" --jq ".assets[] | select(.name==\"${ASSET}\") | .url")"
if [ -z "${ASSET_URL}" ]; then
  echo "ERROR: asset ${ASSET} not found in ${REPO} release ${TAG}" >&2
  exit 1
fi

echo "Downloading (~73 MB)..."
gh api "${ASSET_URL}" -H "Accept: application/octet-stream" > "${TMP}/${ASSET}"
tar xzf "${TMP}/${ASSET}" -C "${TMP}"

SRC="${TMP}/llama-${TAG}"
mkdir -p "${DEST}"
# libllama.so + the ggml core + every CPU feature-variant (libggml.so picks one at runtime).
cp "${SRC}/libllama.so" "${SRC}/libggml.so" "${SRC}/libggml-base.so" "${DEST}/"
cp "${SRC}"/libggml-cpu-android_*.so "${DEST}/"

echo "Staged $(ls "${DEST}"/*.so | wc -l | tr -d ' ') .so files into ${DEST}"
ls -la "${DEST}"

echo "Verifying libllama.so exports the JNI shim's symbols..."
python3 "${SCRIPT_DIR}/verify_llama_symbols.py" "${DEST}" "${APP_DIR}/app/src/main/cpp/milmmt_jni.cpp"
echo "Done. Activate the prebuilt build in app/build.gradle.kts (see CMakeLists.prebuilt.txt header)."
