#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_ROOT="$ROOT/app/build/outputs/apk"
BUILD_TYPE="${BUILD_TYPE:-debug}"
[[ "$BUILD_TYPE" =~ ^(debug|release)$ ]] || { echo "::error::Unsupported BUILD_TYPE: $BUILD_TYPE" >&2; exit 1; }
declare -A FLAVOR_ABI=(
  [arm32]=armeabi-v7a
  [arm64]=arm64-v8a
  [x86]=x86
  [x64]=x86_64
)
fail() { echo "::error::$*" >&2; exit 1; }
for flavor in arm32 arm64 x86 x64; do
  abi="${FLAVOR_ABI[$flavor]}"
  apk="$APK_ROOT/$flavor/$BUILD_TYPE/app-$flavor-$BUILD_TYPE.apk"
  [[ -s "$apk" ]] || fail "Expected APK missing: $apk"
  temp="$(mktemp -d)"
  trap 'rm -rf "$temp"' EXIT
  if ! unzip -p "$apk" "lib/$abi/libtermux-bootstrap.so" > "$temp/libtermux-bootstrap.so"; then
    fail "$apk does not contain lib/$abi/libtermux-bootstrap.so"
  fi
  unzip -p "$apk" assets/bootstrap/bootstrap.json > "$temp/bootstrap.json" || fail "$apk does not contain bootstrap metadata"
  python3 - "$abi" "$temp/libtermux-bootstrap.so" "$temp/bootstrap.json" <<'PY'
import json, pathlib, sys
expected_abi, library, metadata, build_type = sys.argv[1:]
binary = pathlib.Path(library).read_bytes()
if not binary.startswith(b"\x7fELF"):
    raise SystemExit(f"Embedded bootstrap library is not an ELF binary for {expected_abi}")
data = json.loads(pathlib.Path(metadata).read_text(encoding="utf-8"))
if data.get("abi") != expected_abi:
    raise SystemExit(f"APK ABI/bootstrap mismatch: expected {expected_abi}, got {data.get('abi')!r}")
if data.get("applicationId") != "com.techwithkaushik.cppide":
    raise SystemExit(f"APK bootstrap package identity mismatch for {expected_abi}")
if data.get("prefix") != "/data/data/com.techwithkaushik.cppide/files/usr":
    raise SystemExit(f"APK bootstrap prefix mismatch for {expected_abi}")
if build_type == "release" and data.get("approvedForDistribution") is not True:
    raise SystemExit(f"Release APK contains unapproved bootstrap for {expected_abi}")
if not isinstance(data.get("sha256"), str) or len(data["sha256"]) != 64:
    raise SystemExit(f"APK bootstrap metadata has no SHA-256 for {expected_abi}")
if len(binary) < 4096:
    raise SystemExit(f"Native bootstrap library is suspiciously small for {expected_abi}")
print(f"APK contains native bootstrap library for {expected_abi} ({data.get('version')}, {len(binary)} bytes)")
PY
  unzip -Z1 "$apk" > "$temp/apk-entries.txt"
  if grep -Fxq 'assets/bootstrap/bootstrap.zip' "$temp/apk-entries.txt"; then
    fail "$apk duplicates bootstrap.zip in assets; expected native embedding only"
  fi
  rm -rf "$temp"
  trap - EXIT
done
