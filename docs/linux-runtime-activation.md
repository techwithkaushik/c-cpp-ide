# Ubuntu runtime activation on Android

## Runtime boundary

The IDE owns its Linux userspace inside its private app directory. It must never read or write another application's private data. A rootfs archive is only filesystem data; extraction alone does not make it executable. A compatible Android PRoot binary and a successful shell smoke test are required.

## Current implementation

- Ubuntu Base 24.04.5 download is architecture-selected from the current app process (armhf for 32-bit ARM, arm64 for a 64-bit app process).
- The rootfs download is checked against Ubuntu's official `SHA256SUMS` before extraction.
- The importer accepts `.tar`, `.tar.gz`, and `.tar.xz`, limits entries and expanded size, rejects archive path traversal and escaping symlinks, stages the new rootfs, and keeps the prior rootfs until promotion succeeds.
- The PRoot downloader uses a fixed upstream Android release URL and checks ELF architecture. That upstream release does not publish a SHA-256 digest; this is a documented supply-chain limitation. A production release should publish and pin a project-controlled digest for a current PRoot build.
- The local shell uses the native PTY bridge in `:core:jni`, the Android system linker, and the app-owned PRoot/rootfs paths.
- The runtime setup screen runs a real shell smoke test before reporting the runtime as ready.
- SSH opens a shell channel directly from the terminal panel. First-use host keys require explicit SHA-256 fingerprint confirmation; changed pinned keys are rejected.

## Storage

```text
files/linux-runtime/
  distributions/ubuntu/   # active Ubuntu rootfs
  bin/proot               # Android PRoot executable
  staging/                # extraction/install staging and rollback
  tmp/                    # PRoot host-side temporary files
  runtime.properties      # installed runtime metadata
```

All paths are derived from the current app's private files directory. The runtime does not rely on a shared external directory.

## Terminal lifecycle

- A shell is a long-lived PTY-backed process, not a command-per-process mock.
- Hiding the terminal panel detaches the UI but does not recreate a live shell.
- Multiple tabs own separate local or SSH sessions.
- Shell exit removes only that tab; the previously selected tab becomes active, and the terminal panel closes when the final session exits.
- Local PTY input supports Ctrl-C through the controlling terminal. The current Compose renderer is text-oriented and is not yet a complete VT/xterm emulator; full cursor addressing, colors, alternate screen, and resize-aware rendering remain future work.

## Remaining release gates

1. Replace the old upstream PRoot binary with a current, reproducibly built Android artifact and a published SHA-256 digest.
2. Run the complete download/install/rollback flow on physical Android 10+ devices.
3. Verify the PRoot shell smoke test and apt operation on ARM32.
4. Add C17 and C++20 compile-and-run tests inside Ubuntu.
5. Implement full terminal escape-sequence rendering and remote workspace/build integration.
6. Add private-key import tests, credential-vault design, and SSH reconnect tests.
7. Verify corrupted archives, low storage, interruption, and recovery.

A green Gradle build is necessary but not sufficient for release; the PRoot execution path must pass on a real device.
