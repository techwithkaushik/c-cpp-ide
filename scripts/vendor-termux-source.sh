#!/usr/bin/env bash
set -euo pipefail

# Sync the exact upstream source revision used by the local Gradle modules.
# This is source vendoring only; no Termux Maven/JitPack artifacts are used.
readonly UPSTREAM_REPO="https://github.com/termux/termux-app.git"
readonly UPSTREAM_REV="8629e632fcb95da272221be327db653fb24befe9"
readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly DEST="${ROOT}/integrations/termux-upstream"
readonly TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

git -C "${TMP}" init -q
git -C "${TMP}" remote add origin "${UPSTREAM_REPO}"
git -C "${TMP}" fetch --depth 1 origin "${UPSTREAM_REV}"
git -C "${TMP}" checkout --quiet FETCH_HEAD

for module in terminal-view terminal-emulator termux-shared; do
  mkdir -p "${DEST}/${module}"
  rm -rf "${DEST}/${module}/src"
  cp -a "${TMP}/${module}/src" "${DEST}/${module}/src"
  cp "${TMP}/${module}/proguard-rules.pro" "${DEST}/${module}/proguard-rules.pro"
done

cp "${TMP}/LICENSE.md" "${DEST}/LICENSE.md"
cp "${TMP}/termux-shared/LICENSE.md" "${DEST}/termux-shared/LICENSE.md"
printf 'Vendored Termux sources at %s\n' "${UPSTREAM_REV}"
