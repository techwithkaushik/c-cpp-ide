# Code Studio — Ubuntu + SSH IDE for Android

Code Studio is a Kotlin/Jetpack Compose Android IDE focused on C/C++, Python, web files, and an app-owned Linux runtime.

## Runtime direction

This branch uses an app-owned Linux runtime: the Gradle build contains no external terminal-app runtime or vendored terminal emulator. It never reads or writes another app's private data. The app owns the Linux runtime under:

```text
files/linux-runtime/
├── distributions/ubuntu/   # Ubuntu Base rootfs
├── bin/proot               # Android PRoot executable
├── staging/                # private transactional staging
└── runtime.properties      # installed runtime metadata
```

### Ubuntu setup

Open **Settings → Runtime & Bootstrap**.

- **Download official Ubuntu Base 24.04.5** downloads the architecture-matched Ubuntu Base archive and verifies it against Ubuntu's official `SHA256SUMS`.
- **Download pinned Android PRoot** downloads the fixed upstream `proot-rs v0.1.0` Android asset. That old upstream release does not publish an asset digest; the app pins the URL/version, enforces a size limit, and validates the ELF architecture. This remains a supply-chain and compatibility limitation that should be replaced by a project-built, digest-published PRoot release before a production release.
- Import options are available for a local Ubuntu rootfs archive or a PRoot archive with an explicit SHA-256.
- A real shell smoke test must pass before setup reports the runtime as ready.

Ubuntu Base is a minimal userspace, not a complete compiler toolchain. Use Ubuntu's package manager from the Ubuntu shell to install the tools you need. The app does not fall back to Android's host shell if the runtime is missing or fails.

## Terminal targets

The terminal panel supports:

- **Local Ubuntu** — starts a persistent shell through the native PTY bridge and app-owned PRoot runtime.
- **Connect SSH** — opens a remote SSH shell directly from the terminal panel without first opening a local shell.
- Multiple live tabs that remain in the app process when the bottom panel is hidden and reopened.
- First-connection SSH SHA-256 fingerprint confirmation. Trusted fingerprints are saved app-privately; a changed host key is rejected.
- Password or private-key authentication. First-use host keys require explicit SHA-256 fingerprint confirmation; a changed key is rejected. Private-key bytes and passwords are not persisted; a Keystore-backed credential vault remains future work.

The terminal view has a bounded VT-style parser for common cursor/erase sequences, text output, line-oriented input, and Ctrl-C support. Full color/alternate-screen rendering, resize propagation, and editor/build-to-remote-workspace integration are not yet complete; this is not yet feature parity with a desktop terminal emulator.

## Module layout

- `:app`: Compose IDE shell and settings
- `:feature:editor`: editor UI
- `:feature:terminal`: local Ubuntu and SSH terminal UI/session tabs
- `:integrations:linux-runtime`: rootfs validation/install, PRoot setup, local PTY shell, SSH shell and host-key pinning
- `:core:jni`: native C++20 editor helpers and kernel PTY bridge
- `:integrations:toolchain`: compiler/tool discovery

## CI policy

Only `.github/workflows/android-ci.yml` is the automatic Android CI workflow on this branch. It runs Linux runtime unit tests and builds/verifies the signed ARM32 APK. It does not rebuild a bootstrap or rootfs on every app commit. Other ABI flavors remain disabled from release verification until they are intentionally re-enabled.

## Current validation status

CI compilation and tests are required for every PR update. A passing CI build does not replace testing on a physical Android device. Before this branch is ready to merge, verify:

1. Ubuntu download/checksum/extraction and rollback on the target device.
2. PRoot startup and the shell smoke test on Android 10+ ARM32.
3. Real interactive local shell, Ctrl-C, exit, tab persistence and process cleanup.
4. SSH first-connect fingerprint confirmation, trusted reconnect and changed-key rejection.
5. C17/C++20 compile-and-run inside Ubuntu.
6. No runtime access to another app's private data and no fallback to Android's host shell.
