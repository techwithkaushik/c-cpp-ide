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
  if ! unzip -p "$apk" assets/bootstrap/bootstrap.zip > "$temp/bootstrap.zip"; then
    rm -rf "$temp"
    fail "$apk does not contain bundled bootstrap.zip"
  fi
  if ! unzip -p "$apk" assets/bootstrap/bootstrap.json > "$temp/bootstrap.json"; then
    rm -rf "$temp"
    fail "$apk does not contain bootstrap.json"
  fi
  python3 - "$abi" "$temp/bootstrap.zip" "$temp/bootstrap.json" <<'PY'
import hashlib, json, pathlib, sys, zipfile
expected_abi, archive, metadata = sys.argv[1:]
data = json.loads(pathlib.Path(metadata).read_text(encoding="utf-8"))
if data.get("abi") != expected_abi:
    raise SystemExit(f"APK ABI/bootstrap mismatch: expected {expected_abi}, got {data.get('abi')!r}")
expected_hash = data.get("sha256", "")
digest = hashlib.sha256()
with open(archive, "rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
actual_hash = digest.hexdigest()
if actual_hash.lower() != expected_hash.lower():
    raise SystemExit(f"Embedded bootstrap digest mismatch for {expected_abi}")
try:
    with zipfile.ZipFile(archive) as bundle:
        if not bundle.infolist() or bundle.testzip() is not None:
            raise SystemExit(f"Embedded bootstrap ZIP is empty or corrupt for {expected_abi}")
except zipfile.BadZipFile as exc:
    raise SystemExit(f"Embedded bootstrap is not a valid ZIP for {expected_abi}: {exc}")
print(f"APK contains the verified {expected_abi} bootstrap ({data.get('version')})")
PY
  rm -rf "$temp"
done
