# Standalone C/C++ toolchain

The IDE's target is compiling C17 and C++20 without Termux installed or running. This requires an
Android-hosted compiler executable for each supported device ABI, target headers, linker support,
C runtime integration, libc++ for C++, and all required shared libraries.

The new `:integrations:toolchain` module provides an ABI-aware private-runtime manager. It:
- chooses ARM64, ARMv7, x86_64, or x86 based on the device;
- installs to app-private `noBackupFilesDir`;
- requires a reviewed SHA-256 digest;
- bounds archive size, expanded size, and entry count;
- rejects absolute paths and traversal;
- stages extraction before activation and checks compiler launchers and a runtime library directory.

## Current limitation

**No runtime archive is bundled yet.** The manager therefore reports `ARCHIVE_MISSING`; it cannot
compile by itself today. The existing app continues using the current Termux integration until
real, reproducible, Android-hosted runtime archives are built, tested on-device, and accompanied
by provenance and third-party license notices. Do not add fake binaries, empty placeholders, or
desktop NDK host executables to make the status appear ready.

Release gates: compile/run C17 and C++20 on API 29 ARMv7 and ARM64; test Unicode/spaced paths,
diagnostics, failures and timeouts; verify no Termux, root, or network is required for local builds.
