#!/usr/bin/env bash
set -euo pipefail

# Pull only Android/Bionic binaries from the official Termux apt repository. Every .deb is
# SHA256-checked against its repository Packages index before extraction.
ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BASE="https://packages-cf.termux.dev/apt/termux-main"
DEST="$ROOT/app/src/main/jniLibs"
mkdir -p "$DEST"
for tool in curl dpkg-deb python3 xz sha256sum; do command -v "$tool" >/dev/null; done

fetch_package() {
  local arch="$1" package="$2" output="$3"
  local index="$WORK/Packages-$arch.xz" metadata="$WORK/Packages-$arch"
  local stanza="$WORK/${package}-$arch.stanza"
  if [[ ! -f "$metadata" ]]; then
    curl --fail --location --retry 3 "$BASE/dists/stable/main/binary-$arch/Packages.xz" -o "$index"
    xz -dc "$index" > "$metadata"
  fi
  python3 - "$metadata" "$stanza" "$arch" "$package" <<'PY'
import sys
path, out, arch, package = sys.argv[1:]
blocks = open(path, encoding="utf-8", errors="replace").read().split("\n\n")
items = []
for block in blocks:
    fields = {}
    for line in block.splitlines():
        if line and not line[0].isspace() and ": " in line:
            key, value = line.split(": ", 1)
            fields[key] = value
    if fields.get("Package") == package and fields.get("Architecture") == arch:
        items.append(fields)
if not items:
    raise SystemExit(f"Official Termux repository has no {package} package for {arch}")
# Packages index is generated with the current candidate first.
item = items[0]
for key in ("Version", "Filename", "SHA256"):
    if not item.get(key):
        raise SystemExit(f"Missing {key} in {package} metadata")
open(out, "w").write("\n".join(f"{k}: {item[k]}" for k in ("Version", "Filename", "SHA256")))
PY
  local filename version sha deb extract
  filename="$(sed -n 's/^Filename: //p' "$stanza")"
  version="$(sed -n 's/^Version: //p' "$stanza")"
  sha="$(sed -n 's/^SHA256: //p' "$stanza")"
  [[ "$filename" == pool/* && "$sha" =~ ^[a-fA-F0-9]{64}$ ]] || { echo "Invalid metadata for $package" >&2; exit 1; }
  deb="$WORK/$package-$arch.deb"
  curl --fail --location --retry 3 "$BASE/$filename" -o "$deb"
  echo "$sha  $deb" | sha256sum --check --status || { echo "SHA256 mismatch for $package" >&2; exit 1; }
  extract="$WORK/extract-$arch"
  mkdir -p "$extract"
  dpkg-deb -x "$deb" "$extract"
  echo "Fetched verified $package $version ($arch)"
}

fetch_arch() {
  local arch="$1" abi="$2"
  local prefix="$WORK/extract-$arch/data/data/com.termux/files/usr"
  fetch_package "$arch" proot "$WORK/proot-$arch"
  fetch_package "$arch" libtalloc "$WORK/talloc-$arch"
  fetch_package "$arch" libandroid-shmem "$WORK/shmem-$arch"
  local proot talloc shmem loader
  proot="$(find "$prefix" -type f -path '*/bin/proot' -print -quit)"
  talloc="$(find "$prefix" -type f -name 'libtalloc.so*' -print -quit)"
  shmem="$(find "$prefix" -type f -name 'libandroid-shmem.so*' -print -quit)"
  loader="$(find "$prefix" -type f -path '*/libexec/proot/loader' -print -quit)"
  [[ -n "$proot" && -n "$talloc" && -n "$shmem" && -n "$loader" ]] || {
    echo "Required PRoot payload (executable, loader, libtalloc, libandroid-shmem) missing for $abi" >&2
    exit 1
  }
  mkdir -p "$DEST/$abi"
  install -m 0755 "$proot" "$DEST/$abi/libproot.so"
  install -m 0755 "$loader" "$DEST/$abi/libproot-loader.so"
  install -m 0644 "$talloc" "$DEST/$abi/libtalloc.so"
  install -m 0644 "$shmem" "$DEST/$abi/libandroid-shmem.so"
  echo "Prepared verified PRoot runtime for Android ABI $abi"
}

fetch_arch arm armeabi-v7a
fetch_arch aarch64 arm64-v8a
