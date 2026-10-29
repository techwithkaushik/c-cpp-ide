#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${ROOT}/integrations/termux-upstream"

required=(
  "terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java"
  "terminal-emulator/src/main/jni/Android.mk"
  "terminal-view/src/main/java/com/termux/view/TerminalView.java"
  "termux-shared/src/main/java/com/termux/shared/shell/am/AmSocketServer.java"
  "termux-shared/src/main/AndroidManifest.xml"
  "termux-am-library/src/main/java/com/termux/am/Am.java"
  "LICENSE.md"
  "termux-shared/LICENSE.md"
  "termux-am-library/LICENSE"
)

for relative in "${required[@]}"; do
  if [[ ! -s "${BASE}/${relative}" ]]; then
    echo "ERROR: vendored Termux source is missing: integrations/termux-upstream/${relative}" >&2
    exit 1
  fi
done

for module in terminal-emulator terminal-view termux-shared termux-am-library; do
  count="$(find "${BASE}/${module}/src/main" -type f 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "${count}" -eq 0 ]]; then
    echo "ERROR: ${module} has no committed source/resources." >&2
    exit 1
  fi
  echo "OK: ${module} contains ${count} committed source/resource files"
done

if grep -RniE 'com\.termux\.termux-app:|com\.termux:termux-(app|shared|am)|jitpack\.io' \
  "${ROOT}/settings.gradle.kts" "${ROOT}/build.gradle.kts" \
  "${ROOT}/app" "${ROOT}/feature" "${ROOT}/integrations" --include='*.gradle' --include='*.gradle.kts'; then
  echo "ERROR: external Termux Maven/JitPack dependency found; use local vendored modules." >&2
  exit 1
fi

echo "Termux source verification passed. CI will compile the checked-in source without fetching it."
