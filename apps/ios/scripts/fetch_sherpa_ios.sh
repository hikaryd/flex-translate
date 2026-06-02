#!/usr/bin/env bash
# Fetch the official prebuilt sherpa-onnx iOS xcframework (static, device + simulator slices)
# and the matching onnxruntime static xcframework from k2-fsa/sherpa-onnx GitHub releases.
#
# These are large binary blobs (~74 MB download, ~270 MB unpacked) so they are git-ignored, not
# committed. This script makes the prebuilt runtime path reproducible: it downloads a pinned
# release, extracts only the two xcframeworks the app links, and stages them under
# apps/ios/Vendor/SherpaOnnx/.
#
# Mirror of Android: apps/android/scripts/fetch_llama_prebuilt.sh (same pattern).
#
# Requires: curl, tar, bzip2.
set -euo pipefail

VERSION="v1.13.2"
ASSET="sherpa-onnx-${VERSION}-ios-no-tts.tar.bz2"
REPO="k2-fsa/sherpa-onnx"
DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${VERSION}/${ASSET}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENDOR_DIR="${IOS_DIR}/FlexTranslate/Vendor/SherpaOnnx"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

SHERPA_DEST="${VENDOR_DIR}/sherpa-onnx.xcframework"
ONNX_DEST="${VENDOR_DIR}/onnxruntime.xcframework"

if [ -d "${SHERPA_DEST}" ] && [ -d "${ONNX_DEST}" ]; then
  echo "sherpa-onnx ${VERSION} xcframeworks already present in ${VENDOR_DIR} — skipping download."
  exit 0
fi

mkdir -p "${VENDOR_DIR}"

echo "Downloading sherpa-onnx ${VERSION} iOS prebuilt (~74 MB)..."
curl -L --progress-bar -o "${TMP}/${ASSET}" "${DOWNLOAD_URL}"

echo "Extracting..."
tar xjf "${TMP}/${ASSET}" -C "${TMP}"

EXTRACT_DIR="${TMP}/build-ios-no-tts"

echo "Staging sherpa-onnx.xcframework..."
rm -rf "${SHERPA_DEST}"
cp -R "${EXTRACT_DIR}/sherpa-onnx.xcframework" "${SHERPA_DEST}"

echo "Staging onnxruntime.xcframework..."
rm -rf "${ONNX_DEST}"
# The onnxruntime xcframework is nested under ios-onnxruntime/1.17.1/
cp -R "${EXTRACT_DIR}/ios-onnxruntime/1.17.1/onnxruntime.xcframework" "${ONNX_DEST}"

echo ""
echo "Done. Staged:"
echo "  ${SHERPA_DEST}"
echo "  ${ONNX_DEST}"
echo ""
echo "Now run: cd apps/ios && xcodegen generate"
