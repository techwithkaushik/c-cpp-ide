# Embedded Termux Runtime for Code Studio

## Decision

Code Studio must run its own app-private Termux-compatible runtime. It must not use the external Termux app's `RUN_COMMAND` intent, require the Termux app to be installed, or launch Termux for builds.

## Upstream findings

- Termux's `RUN_COMMAND` intent is explicitly an external-app-to-Termux IPC interface and requires the Termux permission plus user opt-in. It is not embedded runtime integration.
- Termux's `TermuxInstaller` installs a bootstrap archive into a staging prefix, restores executable permissions and symlinks from `SYMLINKS.txt`, atomically moves the staging prefix into place, and writes the shell environment.
- Termux bootstrap binaries are built for a specific package identity/prefix. A bootstrap built for `com.termux` or AndroidIDE's package must not be assumed to work under Code Studio's application ID.
- Termux package-maintainer docs describe generating custom bootstrap archives by setting `TERMUX_APP_PACKAGE` and rebuilding the packages/bootstrap for the target identity.

## Required implementation for Code Studio

1. Build and publish a reproducible bootstrap for `com.techwithkaushik.cppide` from the Termux package build system. Include only the base packages required for shell/package management initially; install clang, Python and language tooling as optional packages.
2. Pin the bootstrap release URL, version and SHA-256 in a dedicated runtime configuration file. Do not download an unpinned archive or trust a checksum fetched from the same untrusted location as the archive.
3. Add an app-owned bootstrap installer that:
   - uses an app-private prefix under `filesDir`;
   - extracts into a staging directory and validates paths against zip-slip;
   - reads `SYMLINKS.txt` using the upstream format and recreates links safely;
   - applies executable permissions;
   - runs the bootstrap second-stage script if required by the generated archive;
   - atomically promotes staging only after validation;
   - records install state and supports safe retry/repair.
4. Add an app-owned process launcher that configures `PREFIX`, `HOME`, `TMPDIR`, `PATH`, loader variables and working directory for the Code Studio prefix. Verify the actual shell execution path on Android; ordinary JVM `ProcessBuilder` alone must not be assumed to handle Termux's linker/exec requirements.
5. Gate the Welcome → Bootstrap → Ready → Projects navigation on real smoke tests. A ready state requires a working shell and successful command exit, not merely a completed download.
6. Keep projects and source files in app-owned storage; do not change or write to the user's existing Termux installation.
7. Add tests for interrupted extraction, malformed archive, symlink handling, checksum mismatch, retry, missing ABI artifact and shell smoke-test failure.

## ABI and packaging

Generate and test bootstrap artifacts for each Android ABI that Code Studio officially supports. Do not label an ABI as supported until its bootstrap and native execution path pass on a device/emulator. The app currently has `applicationId = com.techwithkaushik.cppide`; the bootstrap build configuration must use the same identity/prefix.

## Upstream references

- Termux app: https://github.com/termux/termux-app
- Termux installer: https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java
- Termux package maintainer/bootstrap documentation: https://github.com/termux/termux-packages/wiki/For-maintainers
- RUN_COMMAND (external IPC only; deliberately not used by Code Studio): https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

## Status

This document records the verified integration design. It does not claim that the custom bootstrap artifact, native execution path, or device smoke tests already exist. Those are implementation gates, not optional polish.
