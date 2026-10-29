# ADR 0002: Ubuntu rootfs as the standalone bootstrap base

- **Status:** Proposed implementation direction; compatibility and device validation required
- **Date:** 2026-10-27
- **Scope:** Standalone C/C++ IDE runtime bootstrap
- **Supersedes:** The assumption in ADR 0001 that the IDE must never bundle or provision a Linux distribution, for the standalone-runtime path only.

## Context

The standalone IDE must not require a separately installed Termux app and must not ask users to paste a toolchain/archive URL. A user-supplied URL is a poor bootstrap interface: it is error-prone, difficult to support, and creates an avoidable trust boundary.

Ubuntu rootfs is a viable base for an app-owned Linux userspace, but a root filesystem is not itself a working Android compiler runtime. A typical Ubuntu rootfs uses glibc and may not include clang, a linker, development headers, or an Android-compatible execution arrangement.

## Decision

Use a project-managed Ubuntu rootfs bootstrap flow as the default standalone-runtime direction. The app selects the appropriate artifact automatically from the device ABI and a versioned, project-controlled manifest. Users must never have to enter or choose a raw URL during normal setup.

The runtime pipeline is:

1. Detect the best supported device ABI using Android's supported ABI list.
2. Resolve that ABI against a pinned, versioned bootstrap manifest shipped with the app or fetched from a project-controlled HTTPS endpoint.
3. Obtain the matching Ubuntu rootfs artifact from a project-controlled mirror/release asset, with a pinned digest and strict compressed/expanded size limits.
4. Verify the archive digest before extraction; reject path traversal, duplicate paths, excessive entries, and oversized expansion.
5. Extract to a unique staging directory inside app-private storage, never shared storage.
6. Provision the execution layer and compiler toolchain required by the selected design. Evaluate a bundled/compatible proot implementation or an equivalent supported execution mechanism; do not assume ordinary glibc Ubuntu executables run directly on Android.
7. Configure the rootfs prefix, HOME, TMPDIR, PATH, linker/library paths, and package sources without hard-coded assumptions about Termux paths.
8. Run C17 and C++20 compile-and-execute smoke tests, plus a shell/toolchain health check.
9. Activate the runtime only after all checks pass; retain the prior known-good runtime until the replacement is verified.

## Security and reliability requirements

- No raw URL input in the normal user interface.
- No root, SELinux policy changes, or localhost TCP listeners.
- HTTPS only; pin expected SHA-256 and artifact metadata in a reviewed manifest. TLS alone is not artifact integrity.
- Prefer immutable versioned artifacts. Never follow redirects to an unvalidated scheme/host or allow embedded URL credentials.
- Use bounded download size, expanded size, archive entry count, process output, and command timeout.
- Use atomic/transactional staging and activation; concurrent setup attempts must not corrupt each other.
- Do not run package-manager commands as an implicit, unbounded first-launch side effect. If packages must be installed after rootfs extraction, pin repositories/package versions where feasible, surface progress/errors, and make retries safe.
- Store the rootfs and compiler only in app-private storage. Confirm that the chosen execution mechanism works under Android's filesystem and executable restrictions.
- Do not mark the runtime READY until the actual C17 and C++20 executables run successfully on the device.
- The runtime archive must be built and tested for every advertised ABI. Do not claim ARMv7 support based only on ARM64 testing.

## Compatibility gates

Before calling this production-ready, test at least:

- Android 10 / API 29 and a current supported Android version.
- `armeabi-v7a` and `arm64-v8a` on real devices.
- Rootfs extraction, low-storage and interrupted-download recovery.
- Offline compile/run after bootstrap has completed.
- A clean install with no Termux package installed.
- C17 and C++20 compile success, compiler diagnostics, runtime stdout/stderr, exit status, timeout, and process cleanup.
- Rootfs and package update/rollback behavior.

If a compatible execution layer cannot be proven on Android, stop and revise the runtime design rather than presenting a plain Ubuntu rootfs as executable.

## Implementation notes

The existing `:integrations:toolchain` code already has useful primitives for manifest validation, bounded HTTPS downloads, archive extraction, digest verification, staging, and compiler smoke tests. Those primitives must be adapted to the Ubuntu/proot runtime layout rather than treating a generic `bin/clang` + `lib/` ZIP as automatically compatible.

The current `BootstrapArtifact.downloadUrl` remains an internal manifest field, not a user-entered setting. A future manifest loader should select the correct artifact automatically. Do not add a URL text field to the UI.

## Consequences

- The standalone IDE becomes responsible for rootfs lifecycle, security updates, package/toolchain compatibility, and storage usage.
- Initial download may be large; communicate size and progress before starting.
- APK size can remain small by downloading the versioned rootfs on first setup, but first-run internet access is needed unless the artifact is bundled.
- Until artifact publishing and device gates pass, the UI must describe the standalone runtime as unavailable/experimental rather than implying Ubuntu bootstrap is complete.
