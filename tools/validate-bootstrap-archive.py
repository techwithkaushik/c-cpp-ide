#!/usr/bin/env python3
"""Validate a standalone C/C++ IDE bootstrap ZIP before it can be published.

This is a structural/security gate, not proof that binaries run on Android.
The archive layout must match StandaloneToolchain's current contract:
  bin/sh, bin/clang, bin/clang++, lib/, include/
"""
from __future__ import annotations

import argparse
import stat
import sys
import zipfile
from pathlib import PurePosixPath

MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_EXPANDED_BYTES = 1024 * 1024 * 1024
MAX_ENTRIES = 20_000
REQUIRED_FILES = ("bin/sh", "bin/clang", "bin/clang++")
REQUIRED_DIRS = ("lib", "include")


def validate(path: str) -> list[str]:
    errors: list[str] = []
    try:
        archive_size = __import__("os").path.getsize(path)
        if archive_size <= 0 or archive_size > MAX_ARCHIVE_BYTES:
            return [f"archive size outside allowed range: {archive_size} bytes"]
        with zipfile.ZipFile(path) as zf:
            infos = zf.infolist()
            if not infos:
                return ["archive is empty"]
            if len(infos) > MAX_ENTRIES:
                return [f"archive has too many entries: {len(infos)}"]
            expanded = 0
            files: set[str] = set()
            dirs: set[str] = set()
            for info in infos:
                raw = info.filename
                if not raw or "\x00" in raw or "\\" in raw:
                    errors.append(f"invalid archive path: {raw!r}")
                    continue
                item = PurePosixPath(raw)
                if item.is_absolute() or any(part in ("", ".", "..") for part in raw.rstrip("/").split("/")):
                    errors.append(f"unsafe archive path: {raw!r}")
                    continue
                mode = (info.external_attr >> 16) & 0xFFFF
                if stat.S_ISLNK(mode):
                    errors.append(f"symbolic links are not allowed in bootstrap ZIP: {raw}")
                    continue
                if info.flag_bits & 0x1:
                    errors.append(f"encrypted ZIP entries are not allowed: {raw}")
                    continue
                if info.is_dir() or raw.endswith("/"):
                    dirs.add(raw.rstrip("/"))
                else:
                    files.add(raw)
                    expanded += info.file_size
                if expanded > MAX_EXPANDED_BYTES:
                    errors.append("expanded archive exceeds 1 GiB limit")
                    break
                if info.file_size < 0 or info.compress_size < 0:
                    errors.append(f"invalid entry size: {raw}")
            for name in REQUIRED_FILES:
                if name not in files:
                    errors.append(f"required executable is missing: {name}")
            for name in REQUIRED_DIRS:
                if not any(p == name or p.startswith(name + "/") for p in files | dirs):
                    errors.append(f"required directory is missing or empty: {name}")
            if "bin/clang" in files and "bin/clang++" in files:
                # Do not extract or execute untrusted files in this validator.
                pass
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        errors.append(f"cannot read bootstrap ZIP: {exc}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", help="path to candidate bootstrap ZIP")
    args = parser.parse_args()
    errors = validate(args.archive)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"OK: {args.archive} passed structural bootstrap validation")
    print("NOTE: Android ABI, linker dependencies, shell execution and C17/C++20 smoke tests remain separate release gates.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
