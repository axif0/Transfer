#!/usr/bin/env bash
# Download pinned cloudflared binaries into jniLibs for Android packaging.
# Version and checksums must match CloudflareTunnel.CLOUDFLARED_VERSION / EXPECTED_SHA256.
set -euo pipefail

VERSION="2025.8.1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI="$ROOT/app/src/main/jniLibs"

declare -A URLS=(
  ["arm64-v8a"]="https://github.com/cloudflare/cloudflared/releases/download/${VERSION}/cloudflared-linux-arm64"
  ["x86_64"]="https://github.com/cloudflare/cloudflared/releases/download/${VERSION}/cloudflared-linux-amd64"
)
declare -A SHA256=(
  ["arm64-v8a"]="9e2088063c8b8f71ce4b15d65e6f4b1ef345f90c9c15e762cfd2bc8fc63cf22a"
  ["x86_64"]="a66353004197ee4c1fcb68549203824882bba62378ad4d00d234bdb8251f1114"
)

for abi in "${!URLS[@]}"; do
  dir="$JNI/$abi"
  mkdir -p "$dir"
  out="$dir/libcloudflared.so"
  echo "Fetching ${URLS[$abi]} -> $out"
  curl -fsSL -o "$out" "${URLS[$abi]}"
  actual="$(sha256sum "$out" | awk '{print $1}')"
  expected="${SHA256[$abi]}"
  if [[ "$actual" != "$expected" ]]; then
    echo "SHA-256 mismatch for $abi: expected=$expected actual=$actual" >&2
    rm -f "$out"
    exit 1
  fi
  chmod +x "$out"
  echo "OK $abi ($actual)"
done

echo "cloudflared $VERSION packaged."
