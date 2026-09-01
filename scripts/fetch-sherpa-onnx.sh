#!/usr/bin/env bash
set -euo pipefail

# Fetch the official sherpa-onnx AAR from GitHub Releases and verify its SHA-256.
# Mirror of scripts/fetch-sherpa-onnx.ps1 so CI (Linux/macOS) can reproduce the
# same binary. The AAR is deliberately not committed to Git.
#
# Usage: bash scripts/fetch-sherpa-onnx.sh [version]

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-1.13.6}"
DEST_DIR="$PROJECT_ROOT/third_party"
DEST="$DEST_DIR/sherpa-onnx-$VERSION.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"
EXPECTED_SHA256='0012D9A28F15BD6FB966B62B70A75DA3990512FDCCCE28B83098248CE4BE1698'
MIN_SIZE=40000000

mkdir -p "$DEST_DIR"

if [ -f "$DEST" ] && [ "$(stat -c%s "$DEST" 2>/dev/null || stat -f%z "$DEST" 2>/dev/null)" -ge "$MIN_SIZE" ]; then
    echo "AAR already exists: $DEST"
else
    echo "Downloading official sherpa-onnx $VERSION AAR..."
    curl -L --fail --retry 5 --retry-all-errors --retry-delay 2 -o "$DEST" "$URL"
fi

SIZE=$(stat -c%s "$DEST" 2>/dev/null || stat -f%z "$DEST" 2>/dev/null)
if [ "$SIZE" -lt "$MIN_SIZE" ]; then
    echo "Downloaded AAR is incomplete: $SIZE bytes" >&2
    exit 1
fi

ACTUAL_SHA256=$(sha256sum "$DEST" | cut -d' ' -f1)
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "AAR checksum mismatch: expected $EXPECTED_SHA256, got $ACTUAL_SHA256" >&2
    exit 1
fi

echo "Ready: $DEST ($SIZE bytes, SHA-256 $ACTUAL_SHA256)"