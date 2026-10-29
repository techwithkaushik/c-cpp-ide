#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_ROOT="${BOOTSTRAP_ARTIFACTS_DIR:-$ROOT/bootstrap-artifacts}"
OUTPUT_ROOT="$ROOT/app/build/generated/bootstrapAssets"

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

  python3 - "$abi" "$archive" "$metadata" <<'PY'
import hashlib, json, pathlib, sys
abi, archive, metadata = sys.argv[1:]
try:
    data = json.loads(pathlib.Path(metadata).read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"Invalid bootstrap metadata {metadata}: {exc}")
if data.get("abi") != abi:
    raise SystemExit(f"{metadata}: ABI mismatch: expected {abi}, got {data.get('abi')!r}")
version = data.get("version")
if not isinstance(version, str) or not version.strip():
    raise SystemExit(f"{metadata}: missing non-empty version")
expected = data.get("sha256")
if not isinstance(expected, str) or len(expected) != 64 or any(c not in "0123456789abcdefABCDEF" for c in expected):
    raise SystemExit(f"{metadata}: sha256 must be 64 hex characters")
digest = hashlib.sha256(pathlib.Path(archive).read_bytes()).hexdigest()
if digest.lower() != expected.lower():
    raise SystemExit(f"{archive}: SHA-256 mismatch (expected {expected}, got {digest})")
print(f"Validated {abi} bootstrap {version}: sha256={digest}")
PY

  destination="$OUTPUT_ROOT/$flavor/bootstrap"
  mkdir -p "$destination"
  install -m 0644 "$archive" "$destination/bootstrap.zip"
  install -m 0644 "$metadata" "$destination/bootstrap.json"
done

echo "Prepared four ABI-specific bootstrap asset sets."
