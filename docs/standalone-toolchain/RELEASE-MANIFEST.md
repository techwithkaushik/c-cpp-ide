# Standalone bootstrap release manifest

The runtime manifest is the release-time trust input for the in-app downloader. Do not publish a manifest until the archive has been built for this app's package/prefix and validated on physical Android devices.

## Shape

The canonical JSON Schema is [bootstrap-manifest.schema.json](./bootstrap-manifest.schema.json). The top-level object contains:

- `schemaVersion`: currently `1`.
- `runtimeVersion`: immutable runtime release version.
- `artifacts`: one object per supported ABI, with a matching version, HTTPS download URL, SHA-256, exact compressed byte count, and maximum expanded byte count.

## Release gates

1. Build a project-owned bootstrap for `armeabi-v7a` and `arm64-v8a`; do not republish an upstream Termux bootstrap as if it were relocatable.
2. Verify the artifact's package/prefix, Android linker, shared libraries, executable permissions, and licensing notices.
3. Run `tools/validate-bootstrap-archive.py` against each archive and retain its output in the release record.
4. Compute SHA-256 and exact archive size from the final uploaded bytes; copy those values into the manifest only after upload.
5. Run installation and C17/C++20 compile-and-execute smoke tests on Android 10/API 29 for ARMv7 and on a supported ARM64 device.
6. Publish the manifest and artifacts over HTTPS. The app still validates metadata, downloaded byte count, digest, archive paths, expansion limits, and compiler execution before activation.
7. Keep the previous runtime until the replacement has passed all checks; never silently fall back to an unverified binary.

## Current status

No production manifest or runtime artifacts are checked in here. This is intentional: a placeholder URL or fabricated digest would create a false sense of supply-chain security. Until the release gates pass, the app must report the standalone runtime as unavailable and clearly distinguish the existing Termux integration from standalone mode.
