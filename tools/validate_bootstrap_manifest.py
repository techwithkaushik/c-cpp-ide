#!/usr/bin/env python3
"""Validate a published standalone bootstrap manifest using only Python's stdlib."""
import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

SUPPORTED_ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
MAX_COMPRESSED = 512 * 1024 * 1024
MAX_EXPANDED = 1024 * 1024 * 1024


def validate_manifest(manifest):
    if not isinstance(manifest, dict):
        return "Manifest root must be a JSON object."
    if set(manifest) != {"schemaVersion", "runtimeVersion", "artifacts"}:
        return "Manifest must contain only schemaVersion, runtimeVersion, and artifacts."
    if manifest["schemaVersion"] != 1 or isinstance(manifest["schemaVersion"], bool):
        return "schemaVersion must be 1."
    version = manifest["runtimeVersion"]
    if not isinstance(version, str) or not re.fullmatch(r"[A-Za-z0-9._+-]{1,64}", version) or version in {".", ".."}:
        return "runtimeVersion is invalid."
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or not artifacts:
        return "artifacts must be a non-empty array."
    seen = set()
    expected = {"version", "abi", "downloadUrl", "sha256", "compressedSizeBytes", "maximumExpandedBytes"}
    for index, item in enumerate(artifacts):
        prefix = f"artifacts[{index}]"
        if not isinstance(item, dict) or set(item) != expected:
            return f"{prefix} has missing or unexpected fields."
        if item["version"] != version:
            return f"{prefix} version does not match runtimeVersion."
        abi = item["abi"]
        if abi not in SUPPORTED_ABIS:
            return f"{prefix} has unsupported ABI."
        if abi in seen:
            return f"Duplicate artifact ABI: {abi}."
        seen.add(abi)
        url = item["downloadUrl"]
        parsed = urlparse(url) if isinstance(url, str) else None
        if not parsed or parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            return f"{prefix} downloadUrl must be HTTPS without credentials."
        if not isinstance(item["sha256"], str) or not re.fullmatch(r"[A-Fa-f0-9]{64}", item["sha256"]):
            return f"{prefix} sha256 must contain 64 hexadecimal characters."
        compressed = item["compressedSizeBytes"]
        expanded = item["maximumExpandedBytes"]
        if isinstance(compressed, bool) or not isinstance(compressed, int) or not 1 <= compressed <= MAX_COMPRESSED:
            return f"{prefix} compressedSizeBytes is outside allowed limits."
        if isinstance(expanded, bool) or not isinstance(expanded, int) or not 1 <= expanded <= MAX_EXPANDED:
            return f"{prefix} maximumExpandedBytes is outside allowed limits."
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="JSON manifest file to validate")
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"Invalid manifest: {error}", file=sys.stderr)
        return 2
    error = validate_manifest(manifest)
    if error:
        print(f"Invalid manifest: {error}", file=sys.stderr)
        return 1
    print(f"Manifest valid: {len(manifest['artifacts'])} ABI artifact(s), runtime {manifest['runtimeVersion']}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
