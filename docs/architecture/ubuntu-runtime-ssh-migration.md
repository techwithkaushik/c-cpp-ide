# Ubuntu runtime and direct SSH architecture

**Status:** Implementation in progress. The app-owned rootfs importer, official Ubuntu Base downloader, pinned upstream PRoot downloader, local PTY shell, and direct SSH shell are implemented. Physical-device validation and full terminal emulation remain outstanding.

## Product boundary

The app owns the Linux runtime under `files/linux-runtime/`. Runtime paths must be derived from the current app's private files directory. Never use another app's private prefix or data directory.

## First-run setup

1. Check the persisted runtime layout.
2. If the runtime exists, execute the shell smoke test; do not reinstall on every terminal open.
3. Otherwise offer a one-tap download of official Ubuntu Base 24.04.5 or an import from storage.
4. Download mode chooses armhf/arm64 based on the app process ABI and verifies Ubuntu's official `SHA256SUMS`.
5. Import mode uses Android Storage Access Framework and copies the selected archive into private cache; it does not treat a `content://` URI as a filesystem path.
6. Validate archive traversal, symlinks, entry count, expanded size, distro identity and shell paths.
7. Download the fixed upstream Android PRoot release or import a PRoot archive with an explicit SHA-256. The pinned upstream v0.1.0 release currently has no published asset digest; replace it with a project-built release before production.
8. Launch a real shell through the native PTY and PRoot. Do not mark the runtime ready unless the smoke test succeeds.
9. Preserve the previous rootfs until the replacement is extracted and validated.

## Target picker

The terminal offers **Local Ubuntu** and **Connect SSH** directly. A user does not need to open a local shell to configure SSH.

### Local Ubuntu

- PRoot starts the active rootfs with a controlled HOME, PATH, TMPDIR and required filesystem bindings.
- The shell is a long-lived native PTY process.
- The UI reattaches to existing tabs when the bottom panel is hidden and reopened.
- Missing or invalid runtime state opens setup/repair; there is no fallback to the Android host shell.

### SSH

- Supports password and private-key authentication in the current session layer.
- On first connection, the server's SHA-256 host-key fingerprint is shown for explicit confirmation before user authentication proceeds.
- Accepted fingerprints are stored app-privately; a changed key is rejected.
- The shell channel is opened directly from the terminal panel.
- Credentials are not persisted by the current UI. Private key bytes are kept in memory for the connection attempt.
- Remote workspace browsing, file synchronization and remote build/LSP integration remain incomplete.

## CI

Only `.github/workflows/android-ci.yml` is the automatic Android CI workflow for this branch. It runs Linux runtime unit tests and builds/verifies the ARM32 release APK. Rootfs downloads are not rebuilt on every app commit.

## Acceptance gates

- Clean install and official rootfs checksum verification.
- Archive traversal and unsafe symlink rejection.
- Rollback after a failed rootfs install.
- PRoot starts on Android 10+ ARM32 and the Ubuntu shell smoke test passes.
- Local shell input/output, Ctrl-C, exit, tab persistence and process cleanup.
- SSH first-connect confirmation, trusted reconnect and changed-key rejection.
- C17/C++20 compile-and-run inside Ubuntu.
- No runtime access to another application's private files.
- CI green and physical-device validation recorded before merge.
