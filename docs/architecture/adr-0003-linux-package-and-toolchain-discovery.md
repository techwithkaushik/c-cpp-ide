# ADR 0003: Linux distribution owns package installation and tool discovery

- **Status:** Accepted as the implementation contract; runtime execution remains device-validation gated
- **Date:** 2026-10-28
- **Scope:** First-run Linux setup, package management, compiler/tool discovery, and imported toolchains

## Decision

The IDE's setup installs or imports a Linux userspace. Once a runtime is activated, normal development tools are installed *inside that distribution*, using its own package manager and repositories (for example, `apt` inside Debian/Ubuntu). The Android host must never run the distribution's package manager directly, and the IDE must not pretend that extraction alone installed a runnable Linux environment.

The IDE should also support manually extracted SDKs/toolchains. A tool does not have to be registered in the package database if its executable is inside the active rootfs and its path is configured. This follows the useful VS Code behavior of discovering configured executables/toolchains, but it is a custom Android/Linux integration rather than a copy of VS Code internals.

## Runtime layers

1. **Distribution bootstrap:** Download the pinned official Ubuntu rootfs, or import a user-selected compatible rootfs archive.
2. **Execution layer:** Enter the rootfs through a validated, unprivileged Android-compatible mechanism. Never silently fall back to Android's host shell.
3. **Package manager:** Detect the distribution from its extracted identity and use its native package manager *inside* the runtime. Initially support Ubuntu/Debian apt only when the runtime actually contains a working apt and package sources; other distributions may be usable for manually installed tools without package-manager automation.
4. **Tool discovery:** Resolve commands from the active runtime's configured `PATH`, then common manual SDK locations such as `/usr/local/bin`, `/opt/<toolchain>/bin`, and user-configured paths. Resolve symlinks only when the final path remains inside the rootfs. Never execute an Android-host binary by mistake.
5. **Editor/build integration:** Show discovered tool path, version, and availability. Build/run/LSP/debug features must invoke tools through the same runtime process launcher and must report missing/incompatible executables instead of inventing a successful result.

## Package installation UX

The setup screen must explain that the Linux environment is separate from Android. After the rootfs has passed execution smoke tests, a Linux Environment view should show:

- Distribution and release, architecture, runtime status, rootfs storage use.
- Package-manager status and refresh/install actions (only if supported).
- Suggested packages such as `clang`, `clang++`, `make`, `cmake`, `gdb`, `git`, and language servers where compatible.
- A toolchain list with executable path, version, source (distribution package, manual path, or user-configured path), and validation status.
- A manual toolchain path picker/configuration action. A picked archive is data to inspect, not something to execute automatically.

Package changes are explicit user actions, cancellable, bounded by time/output limits, and reported with exit status. Do not run a full system upgrade silently during first launch. Keep package install state separate from rootfs activation; failed package installs must not destroy the known-good rootfs.

## Security and path rules

- A package command is an argv array, never a shell-concatenated string built from user input.
- Package manager runs only within the active rootfs through the validated runtime launcher.
- Tool discovery validates that a candidate resolves inside the rootfs; reject path traversal and symlink escapes.
- User-configured paths are rootfs paths, not arbitrary Android absolute paths.
- Bound directory traversal, candidate count, command output, and command duration.
- Do not require root on Android, SELinux changes, localhost TCP, or access to another app's private files.
- A manually extracted binary may be found but still fail to execute because of ABI, loader, or libc mismatch. Discovery is not proof of runtime compatibility; execute a bounded version/smoke check before marking a tool ready.

## Acceptance criteria

- Setup installs/imports a Linux rootfs and marks it ready only after the execution layer and shell checks pass.
- Terminal opens a real shell inside that rootfs.
- Package installation commands execute inside the selected distribution, not on Android.
- Tools installed by the distro package manager and tools manually extracted into configured paths can both be discovered.
- The UI distinguishes discovered, executable, and validated tools.
- Unsupported distributions still permit manual tool discovery where safe, while package-manager controls remain disabled with an explanation.
- No capability is marked verified until exercised on a real device for each advertised ABI.
