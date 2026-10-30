#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_ROOT="${BOOTSTRAP_ARTIFACTS_DIR:-$ROOT/bootstrap-artifacts}"
ASSET_OUTPUT_ROOT="$ROOT/app/build/generated/bootstrapAssets"
NATIVE_OUTPUT_ROOT="$ROOT/app/build/generated/bootstrapNative"

declare -A FLAVOR_ABI=(
  [arm32]=armeabi-v7a
  [arm64]=arm64-v8a
  [x86]=x86
  [x64]=x86_64
)

fail() { echo "::error::$*" >&2; exit 1; }

for flavor in arm32 arm64 x86 x64; do
  abi="${FLAVOR_ABI[$flavor]}"
  source_dir="$ARTIFACT_ROOT/$abi"
  archive="$source_dir/bootstrap.zip"
  metadata="$source_dir/bootstrap.json"

  [[ -s "$archive" ]] || fail "Missing bootstrap archive for $abi: $archive. Refusing to build an APK without its matching bootstrap."
  [[ -s "$metadata" ]] || fail "Missing pinned metadata for $abi: $metadata"
  size="$(stat -c '%s' "$archive")"
  (( size > 0 && size <= 536870912 )) || fail "$archive exceeds the allowed compressed size (512 MiB)."

  python3 - "$abi" "$archive" "$metadata" <<'PY'
import hashlib, json, pathlib, sys, zipfile
abi, archive, metadata = sys.argv[1:]
try:
    data = json.loads(pathlib.Path(metadata).read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"Invalid bootstrap metadata {metadata}: {exc}")
if data.get("abi") != abi:
    raise SystemExit(f"{metadata}: ABI mismatch: expected {abi}, got {data.get('abi')!r}")
if data.get("applicationId") != "com.techwithkaushik.cppide":
    raise SystemExit(f"{metadata}: applicationId must be com.techwithkaushik.cppide")
if data.get("prefix") != "/data/data/com.techwithkaushik.cppide/files/usr":
    raise SystemExit(f"{metadata}: bootstrap prefix does not match Code Studio")
if data.get("approvedForDistribution") is not True:
    raise SystemExit(f"{metadata}: bootstrap has not passed package-identity audit and device smoke tests")
version = data.get("version")
if not isinstance(version, str) or not version.strip() or len(version) > 64:
    raise SystemExit(f"{metadata}: missing or invalid version")
expected = data.get("sha256")
if not isinstance(expected, str) or len(expected) != 64 or any(c not in "0123456789abcdefABCDEF" for c in expected):
    raise SystemExit(f"{metadata}: sha256 must be 64 hex characters")
digest = hashlib.sha256()
with open(archive, "rb") as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(chunk)
actual = digest.hexdigest()
if actual.lower() != expected.lower():
    raise SystemExit(f"{archive}: SHA-256 mismatch (expected {expected}, got {actual})")
try:
    with zipfile.ZipFile(archive) as bundle:
        entries = bundle.infolist()
        if not entries:
            raise SystemExit(f"{archive}: bootstrap ZIP is empty")
        if bundle.testzip() is not None:
            raise SystemExit(f"{archive}: bootstrap ZIP contains a corrupt entry")
except zipfile.BadZipFile as exc:
    raise SystemExit(f"{archive}: not a valid ZIP archive: {exc}")
print(f"Validated {abi} bootstrap {version}: sha256={actual}")
PY

  native_destination="$NATIVE_OUTPUT_ROOT/$flavor"
  asset_destination="$ASSET_OUTPUT_ROOT/$flavor/bootstrap"
  mkdir -p "$native_destination" "$asset_destination"
  install -m 0644 "$archive" "$native_destination/bootstrap.zip"
  install -m 0644 "$metadata" "$asset_destination/bootstrap.json"



done

echo "Prepared four ABI-specific native bootstrap inputs and metadata assets."
