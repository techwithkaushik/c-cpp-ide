# Linux runtime activation on Android

## Decision

Use a private, app-owned Linux userspace and a native Android-compatible PRoot runtime. Do not require a separate Termux installation, root, a localhost TCP server, or a VM.

A rootfs archive is only filesystem data. Extraction must never mark Linux as active by itself. Activation requires a compatible PRoot executable, valid distro rootfs, a successful non-interactive shell smoke test, and a working terminal process/session.

## Runtime architecture

1. Select a distro and Android-compatible architecture.
2. Download the distro-specific archive to an app-private `linux/downloads/<distro>/` directory.
3. Verify the archive against a trusted SHA-256 manifest (and a signature where the upstream publishes one). Keep downloads in `.part` files until verification passes.
4. Safely extract to `linux/rootfs/<distro>-staging/`; validate `etc/os-release`, required paths, and archive contents; atomically replace only that distro's rootfs.
5. Build and bundle an Android-compatible PRoot executable for each supported ABI under `app/src/main/jniLibs/<abi>/libproot.so`, with every required shared library in the same ABI directory. Android 10+ may block execution from ordinary app data directories, so the launcher resolves the bundled executable from `ApplicationInfo.nativeLibraryDir`. Do not use a generic GNU/Linux release binary: Android uses Bionic and has different linker/runtime requirements.
6. Launch the distro shell with a controlled environment and required Android filesystem bindings. The exact PRoot arguments must be tested against the bundled PRoot build and Android API level; never assume `chroot` works without root.
7. Run an activation smoke test through the same process launcher the terminal will use, e.g. execute the distro's `/bin/sh -c 'printf ...; test -x /usr/bin/env'`. Capture exit code, stdout, stderr, and timeout.
8. Mark the distro Active only after the smoke test succeeds. Otherwise preserve the extracted rootfs and show a repairable runtime error.

## Terminal integration

The terminal panel should own a long-lived process/session, not just display sample output. Start PRoot with the selected rootfs, set HOME and working directory to app-private paths inside that rootfs, connect stdin/stdout/stderr to the terminal UI, support resize/interrupt/exit, and terminate child processes when the session is closed. Commands entered in the terminal and Compile & Run must use the same selected distro/runtime.

The initial smoke test should run before the UI reports success. The launcher now contains a real shell smoke-test path and an interactive process entry point. The PRoot binary and ABI-specific native dependencies still need to be bundled and validated; until then setup must remain inactive. The next phase is runtime artifact packaging and device validation, then terminal stream/PTY integration and file-aware language tool installation.

## Distribution support policy

- Ubuntu is the default and remains supported only when its official archive and SHA-256 manifest match the selected Android architecture.
- Debian must use a pinned, architecture-specific rootfs source with verifiable SHA-256 and an extractor that supports that archive's compression format.
- Arch Linux ARM must use the correct generic ARMv7/AArch64 rootfs and verify the upstream signature using a pinned/trusted public key. A published MD5 alone is not sufficient integrity protection.
- Never let selecting one distro download or install another distro's archive. Store each distro under its own named directory and isolate its downloads, staging, backups, metadata, and installed-state checks.
- A distro whose download source, checksum/signature verification, architecture mapping, and runtime smoke test are not implemented must be shown as unavailable/not yet supported, not as a working download option.

## Validation matrix

Test on physical Android devices for each supported ABI/API level:
- interrupted download and retry;
- checksum mismatch and malformed archive;
- archive traversal and unsafe symlink rejection;
- existing rootfs preservation after failed upgrade;
- PRoot executable starts on Android/Bionic;
- `/bin/sh` smoke test passes inside each distro;
- terminal input/output, Ctrl-C, exit status, process cleanup;
- compile and execute a small C17 and C++20 program;
- low storage, app restart, and Android background process restrictions.

## Research references

- Termux execution environment and Android Bionic differences: https://github.com/termux/termux-packages/wiki/Termux-execution-environment
- Termux PRoot-Distro design and rootless userspace: https://github.com/termux/proot-distro
- Termux PRoot package recipe and Android-specific build/dependencies: https://github.com/termux/termux-packages/tree/master/packages/proot
- Andronix's open installer, architecture checks, rootfs downloads and SHA-256 validation: https://github.com/AndronixApp/andronix
- Arch Linux ARM official rootfs downloads and signatures: https://archlinuxarm.org/about/downloads

- Current upstream PRoot release and source: https://github.com/proot-me/proot/releases/tag/v5.5.0 and https://github.com/proot-me/proot
- Android executable packaging constraint: native payloads should be packaged in ABI-specific jniLibs and resolved from nativeLibraryDir, not executed from a writable app data directory.
