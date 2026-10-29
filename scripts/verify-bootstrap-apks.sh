#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_ROOT="$ROOT/app/build/outputs/apk"
declare -A FLAVOR_ABI=(
  [arm32]=armeabi-v7a
  [arm64]=arm64-v8a
  [x86]=x86
  [x64]=x86_64
)
fail() { echo "::error::$*" >&2; exit 1; }
for flavor in arm32 arm64 x86 x64; do
  abi="${FLAVOR_ABI[$flavor]}"
  apk="$APK_ROOT/$flavor/debug/app-$flavor-debug.apk"
  [[ -s "$apk" ]] || fail "Expected APK missing: $apk"
  temp="$(mktemp -d)"
  trap 'rm -rf "$temp"' EXIT
  unzip -p "$apk" assets/bootstrap/bootstrap.zip > "$temp/bootstrap.zip" || fail "$apk does not contain bundled bootstrap.zip"
  unzip -p "$apk" assets/bootstrap/bootstrap.json > "$temp/bootstrap.json" || fail "$apk does not contain bootstrap.json"
  python3 - "$abi" "$temp/bootstrap.json" <<'PY'
import json, pathlib, sys
expected, path = sys.argv[1:]
data = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
if data.get("abi") != expected:
    raise SystemExit(f"APK ABI/bootstrap mismatch: APK flavor expects {expected}, bundle declares {data.get('abi')!r}")
print(f"APK contains matching {expected} bootstrap")
PY
  rm -rf "$temp"
  trap - EXIT
done
