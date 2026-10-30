#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/bootstrap-artifacts"
PACKAGE="com.techwithkaushik.cppide"
PREFIX="/data/data/$PACKAGE/files/usr"
python3 - "$OUT" "$PACKAGE" "$PREFIX" <<'PY'
import hashlib, json, pathlib, sys, zipfile
root, package, prefix = sys.argv[1:]
out = pathlib.Path(root)
abis = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
for abi in abis:
    folder = out / abi
    folder.mkdir(parents=True, exist_ok=True)
    archive = folder / "bootstrap.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("bin/", "")
        zf.writestr("bin/sh", "#!/system/bin/sh\nexit 0\n")
        zf.writestr("etc/", "")
        zf.writestr("lib/", "")
        zf.writestr("lib/libfixture.so", "not-a-real-library; CI packaging fixture only")
        zf.writestr("share/", "")
        zf.writestr("SYMLINKS.txt", "")
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    metadata = {
        "schemaVersion": 1,
        "applicationId": package,
        "prefix": prefix,
        "abi": abi,
        "version": "CI-fixture-not-for-installation",
        "sha256": digest,
        "approvedForDistribution": False,
        "status": "Synthetic test fixture. Never use as a runtime."
    }
    (folder / "bootstrap.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
print("Created synthetic ABI fixtures for native embedding tests only.")
PY
