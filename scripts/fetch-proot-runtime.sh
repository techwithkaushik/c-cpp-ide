#!/usr/bin/env bash
set -euo pipefail

# Bundle the Android/Bionic-compatible PRoot package published by the official Termux
# package repository. Package metadata supplies SHA256; never execute an unverified payload.
ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BASE="https://packages-cf.termux.dev/apt/termux-main"
DEST="$ROOT/app/src/main/jniLibs"
mkdir -p "$DEST"

command -v curl >/dev/null
command -v dpkg-deb >/dev/null
command -v python3 >/dev/null
command -v xz >/dev/null

fetch_arch() {
  local termux_arch="$1" android_abi="$2"
  local index="$WORK/Packages-$termux_arch.xz"
  local metadata="$WORK/Packages-$termux_arch"
  local stanza="$WORK/stanza"
  curl --fail --location --retry 3 "$BASE/dists/stable/main/binary-$termux_arch/Packages.xz" -o "$index"
  xz -dc "$index" > "$metadata"
  python3 - "$metadata" "$stanza" "$termux_arch" <<'PY'
import sys
path, out, arch = sys.argv[1:]
data = open(path, encoding="utf-8", errors="replace").read().split("\n\n")
matches = []
for block in data:
    fields = {}
    for line in block.splitlines():
        if line and not line[0].isspace() and ": " in line:
            key, value = line.split(": ", 1)
            fields[key] = value
    if fields.get("Package") == "proot" and fields.get("Architecture") == arch:
        matches.append(fields)
if not matches:
    raise SystemExit(f"No official proot package found for Termux architecture {arch}")
# Choose the highest version using dpkg's version comparator in the shell step below;
# repository indices are ordered newest first, so take the first matching stanza.
item = matches[0]
for key in ("Version", "Filename", "SHA256"):
    if not item.get(key):
        raise SystemExit(f"Missing {key} in official proot package metadata")
open(out, "w").write("\n".join(f"{k}: {item[k]}" for k in ("Version", "Filename", "SHA256")))
PY
  local filename version sha deb extract
  filename="$(sed -n 's/^Filename: //p' "$stanza")"
  version="$(sed -n 's/^Version: //p' "$stanza")"
  sha="$(sed -n 's/^SHA256: //p' "$stanza")"
  [[ "$filename" == pool/* && "$sha" =~ ^[a-fA-F0-9]{64}$ ]]
  deb="$WORK/proot-$termux_arch.deb"
  curl --fail --location --retry 3 "$BASE/$filename" -o "$deb"
  echo "$sha  $deb" | sha256sum --check --status || { echo "SHA256 mismatch for $filename" >&2; exit 1; }
  extract="$WORK/extract-$termux_arch"
  mkdir -p "$extract" "$DEST/$android_abi"
  dpkg-deb -x "$deb" "$extract"
  local prefix="$extract/data/data/com.termux/files/usr"
  local proot
  proot="$(find "$prefix" -type f -path '*/bin/proot' -print -quit)"
  [[ -n "$proot" ]] || { echo "proot executable missing from $filename" >&2; exit 1; }
  install -m 0755 "$proot" "$DEST/$android_abi/libproot.so"

  # Resolve packaged shared libraries to stable names in nativeLibraryDir. Keep all required
  # runtime files in the APK so it never depends on a separately installed Termux app.
  local found_talloc=""
  found_talloc="$(find "$prefix" -type f -name 'libtalloc.so*' -print -quit)"
  [[ -n "$found_talloc" ]] || { echo "libtalloc dependency missing from $filename" >&2; exit 1; }
  install -m 0644 "$found_talloc" "$DEST/$android_abi/libtalloc.so"
  local shmem=""
  shmem="$(find "$prefix" -type f -name 'libandroid-shmem.so*' -print -quit)"
  if [[ -n "$shmem" ]]; then install -m 0644 "$shmem" "$DEST/$android_abi/libandroid-shmem.so"; fi
  local loader=""
  loader="$(find "$prefix" -type f -path '*/libexec/proot/loader' -print -quit)"
  if [[ -n "$loader" ]]; then install -m 0755 "$loader" "$DEST/$android_abi/libproot-loader.so"; fi
  echo "Bundled official Termux PRoot $version for $android_abi"
}

# Build both common ARM variants so a 32-bit Android app process and 64-bit Android
# installations have the matching payload. Other ABIs intentionally fail closed for now.
fetch_arch arm armeabi-v7a
fetch_arch aarch64 arm64-v8a
