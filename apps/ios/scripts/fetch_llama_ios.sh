#!/usr/bin/env bash
# Fetch the official prebuilt llama.cpp iOS xcframework (device arm64 + simulator arm64/x86_64)
# from a pinned ggml-org/llama.cpp GitHub release and stage it under
# apps/ios/FlexTranslate/Vendor/LlamaCpp/llama.xcframework.
#
# The xcframework is a large binary blob (~100 MB download) and is git-ignored — not committed.
# This script makes the prebuilt runtime path reproducible: it downloads a pinned release asset
# (llama-<TAG>-xcframework.zip), extracts the xcframework, and stages it in the correct location.
#
# Pinned to release b9469 whose xcframework ships both device (arm64) and simulator
# (arm64+x86_64) slices and includes llama.h / ggml.h headers needed by the bridging header.
#
# Mirror of Android: apps/android/scripts/fetch_llama_prebuilt.sh (same pattern).
#
# Requires: curl (or gh), unzip.
set -euo pipefail

TAG="b9469"
ASSET="llama-${TAG}-xcframework.zip"
REPO="ggml-org/llama.cpp"
DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IOS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
VENDOR_DIR="${IOS_DIR}/FlexTranslate/Vendor/LlamaCpp"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

LLAMA_DEST="${VENDOR_DIR}/llama.xcframework"

if [ -d "${LLAMA_DEST}" ]; then
  echo "llama.xcframework (${TAG}) already present in ${VENDOR_DIR} — skipping download."
  exit 0
fi

mkdir -p "${VENDOR_DIR}"

echo "Downloading llama.cpp ${TAG} iOS xcframework (~100 MB)..."
if command -v gh &>/dev/null; then
  gh release download "${TAG}" --repo "${REPO}" --pattern "${ASSET}" --dir "${TMP}" --clobber
else
  curl -L --progress-bar -o "${TMP}/${ASSET}" "${DOWNLOAD_URL}"
fi

echo "Extracting..."
unzip -q "${TMP}/${ASSET}" -d "${TMP}/extracted"

# The zip contains llama.xcframework at the root or one level down.
FOUND="$(find "${TMP}/extracted" -maxdepth 2 -name "llama.xcframework" -type d | head -1)"
if [ -z "${FOUND}" ]; then
  echo "ERROR: llama.xcframework not found in extracted archive." >&2
  echo "Contents:" >&2
  find "${TMP}/extracted" -maxdepth 3 | head -30 >&2
  exit 1
fi

echo "Staging llama.xcframework..."
rm -rf "${LLAMA_DEST}"
cp -R "${FOUND}" "${LLAMA_DEST}"

echo ""
echo "Done. Staged:"
echo "  ${LLAMA_DEST}"
echo ""
echo "Slices:"
ls "${LLAMA_DEST}/" | grep -v Info.plist || true
echo ""
echo "Now run: cd apps/ios && xcodegen generate"
