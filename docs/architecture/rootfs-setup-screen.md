# Rootfs setup screen: download or import

- **Status:** UI/flow specification
- **Date:** 2026-10-27
- **Scope:** Standalone Ubuntu rootfs first-run setup

## Setup screen

Show two equally clear choices:

1. **Download from Internet**
   - Supporting text: “Download a supported Ubuntu rootfs for this device.”
   - Display selected Ubuntu release, ABI, compressed download size, and required free storage before starting.
   - Choose the URL from a project-controlled, versioned manifest. Never ask users to type or paste a URL.
   - Show download progress, downloaded/total bytes when known, speed, and Cancel. Offer pause/resume only if genuinely supported.
   - Verify the pinned SHA-256 and archive metadata before extraction.
   - If download fails, preserve a safe retry path and never leave a partial archive looking installed.

2. **Use Downloaded Rootfs from Storage**
   - Supporting text: “Choose a rootfs archive already saved on your phone.”
   - Button: **Select Rootfs Archive**.
   - Launch Android Storage Access Framework with ACTION_OPEN_DOCUMENT and supported archive MIME types. Do not request broad storage permission just to pick a file.
   - Accept only explicitly supported archive formats. Initially prefer one format, such as .tar.xz or .tar.gz, rather than guessing from filenames.
   - Read the selected URI through ContentResolver into a bounded app-private staging file; never assume a content:// URI is a filesystem path.
   - Show the selected filename and size, then validate metadata, ABI compatibility, integrity where a trusted digest is available, and safe extraction limits.
   - If no trusted metadata accompanies the archive, do not silently trust a digest supplied by the archive itself. Explain that authenticity cannot be established and require a supported verification route.

## Shared installation pipeline

Both choices must converge on the same installer; only the source of archive bytes differs.

Source selection -> bounded staging -> integrity/metadata validation -> safe extraction -> runtime provisioning -> C17/C++20 smoke tests -> activate runtime.

Requirements:

- Use unique staging paths and transactional activation; never overwrite the working runtime before the replacement passes all checks.
- Enforce compressed size, expanded size, archive entry count, path traversal, duplicate-entry and symlink/hardlink policies.
- Do not trust archive filenames, MIME type, /etc/os-release, or archive-provided checksums as proof of authenticity.
- Do not report success until the execution layer and compiler have passed compile-and-run checks.
- Keep runtime files under app-private storage.
- Clearly report unsupported ABI, corrupt archive, insufficient storage, digest mismatch, and runtime smoke-test failures.
- Test interrupted downloads/imports, low storage, duplicate taps, and retry/rollback.

## Rootfs artifact format contract

A generic Ubuntu rootfs is not necessarily directly executable on Android. The artifact contract must state:

- Ubuntu release and rootfs provenance.
- Supported ABI and minimum Android/API assumptions.
- Archive format and exact compressed size.
- Expected SHA-256 delivered through a trusted project-controlled manifest.
- Execution-layer requirements (for example, a validated proot-based arrangement if chosen).
- Whether clang/clang++, linker, headers and required runtime libraries are included or provisioned afterward.
- Expected expanded size and tested storage budget.

Do not advertise an ABI until the complete rootfs + execution layer + compiler pipeline passes on a physical device.

## Acceptance criteria

- The setup screen exposes exactly the two source choices above.
- Internet setup uses a manifest-selected source; there is no raw URL input.
- Storage import uses the Android system picker and works with content:// URIs.
- Both paths call the same validator and installer.
- Malformed, incompatible, oversized, or untrusted artifacts fail safely.
- The previous working runtime remains intact if an update fails.
- Success is shown only after C17 and C++20 programs compile and run.
