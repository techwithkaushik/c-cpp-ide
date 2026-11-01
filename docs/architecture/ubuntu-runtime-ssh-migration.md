# Ubuntu runtime + direct local/SSH execution plan

**Branch:** `feature/ubuntu-runtime-ssh`  
**Status:** Implementation in progress. The app-owned rootfs importer, official Ubuntu Base downloader, pinned upstream PRoot downloader, local shell launcher, and direct password-based SSH shell are implemented. Device-level validation and full terminal emulation remain outstanding.

## Product boundary

This branch targets an app-owned Ubuntu/Linux userspace. It must not install, download, invoke, or read a separately installed Termux environment. It must not use `/data/data/com.termux`, Termux bootstrap artifacts, Termux package repositories, or Termux-specific environment variables as runtime dependencies.

The rootfs is stored under `files/linux-runtime/distributions/ubuntu/`, with staging and PRoot files kept under `files/linux-runtime/`.

The app creates every path. Archive-provided paths may never determine the installation destination.

## First-run setup

1. On startup, inspect the persisted runtime record and validate the active environment first.
2. If a valid environment exists, go straight to the IDE; do not show setup or run a bootstrap stage again.
3. Otherwise show **Download Ubuntu** and **Import rootfs from storage**.
4. Download mode uses a versioned app-controlled manifest containing distro release, ABI, size, SHA-256, and artifact URL. No URL text box is shown.
5. Import mode uses Android Storage Access Framework and copies the selected archive into private staging. It must not assume a `content://` URI is a filesystem path.
6. Validate archive traversal, links, entry count, expanded size, free space, distro identity, loader, shell and ABI before extraction/activation.
7. Prepare a proven Android-compatible userspace execution layer (for example, a tested proot build) inside the app-managed environment. Extracting Ubuntu alone is not considered runnable.
8. Run a shell test, then C17 and C++20 compile-and-run smoke tests. Mark READY only after they pass.
9. Promote staging to active transactionally; keep the previous known-good runtime until the new runtime passes all tests. Persist each completed step so interruption resumes from the next verified step.

## Execution target picker: local or SSH

The target is selected **before** opening a terminal or running a build. The terminal and editor must not require the user to open a local shell first and manually configure SSH.

### Local Linux (default)

- Launch a shell inside the active app-owned rootfs through the validated Android-compatible execution layer.
- Resolve the rootfs, HOME, TMPDIR, PATH, working directory, compiler and language servers from one runtime descriptor.
- Never fall back to Android's host shell when the local Linux runtime is unavailable; show a repair/setup action instead.

### SSH host

- Provide a first-class **Connect over SSH** target in the same target picker used by local Linux.
- Collect host, port (default 22), username, authentication method, and optional remote workspace path before launching the terminal.
- The current UI supports password authentication; the session layer accepts key material, but private-key import and Keystore-backed credential storage are still pending. Never log credentials.
- Verify host keys and show a first-connect fingerprint confirmation. Do not silently accept changed host keys.
- After connection, create the terminal channel directly and open the remote workspace. Do not show or require a local terminal as a setup prerequisite.
- Builds, compiler/LSP commands, and terminal sessions run on the selected remote host when SSH is the active target. Show connection/reconnection state and actionable errors.
- Local and SSH targets use the same target/session interface, but remote paths are not treated as local Android files. Workspace transfer/synchronization must be explicit.

## Runtime/session contract

- A target record has a stable ID, type (`LOCAL_LINUX` or `SSH`), display name, working directory, and connection/runtime metadata.
- Session creation is asynchronous and cancellable. Keep the terminal UI responsive.
- Opening the terminal reattaches to a live session; it must not reinstall the rootfs or recreate a still-running shell.
- Session exit removes only that session. Closing the UI does not implicitly kill a persistent session; explicit session close/app exit follows the lifecycle policy.
- A direct SSH terminal is a first-class target and may be opened from the IDE's terminal button or run/build action without first opening another terminal.

## CI policy

- One active workflow, `.github/workflows/android-ci.yml`, handles Android build verification for pull requests and pushes to `main`.
- Ubuntu/rootfs artifact publishing must be a separate explicit release action only after its own artifact tests exist; ordinary app commits must not start a second long-running bootstrap build.
- This branch removes the Termux bootstrap workflow. Do not add a workflow that rebuilds a Linux rootfs on every app push.
- The Android CI must not run a Termux source/vendor verification step.

## Required implementation gates

The migration is not complete until all of these are true:

- The app build no longer depends on `:integrations:termux-runtime`, `:integrations:termux`, or `:integrations:termux-upstream:*`.
- Terminal sessions use the IDE's Linux target/session interface, not Termux bootstrap or Termux-specific shell setup.
- Fresh install, resume after interruption, corrupted archive, low storage, and rollback tests pass.
- A clean device with no Termux app or Termux data installed can install Ubuntu and launch a real shell.
- Local Ubuntu and direct SSH terminal paths both open without a preliminary terminal/setup shell.
- C17/C++20 compile-and-run succeeds on each ABI claimed by the release.
- CI is green and on-device validation is recorded before this branch is marked ready to merge.

## Current limitation

This document defines the migration contract. The current app still contains Termux-derived terminal modules and the existing terminal UI depends on them. They must be replaced with the IDE's own session implementation before the branch can satisfy the full no-Termux requirement; this draft must not be described as a completed Ubuntu runtime.
