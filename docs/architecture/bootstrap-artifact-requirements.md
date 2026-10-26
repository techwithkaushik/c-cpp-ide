# Bootstrap artifact requirements

## Decision

Build a project-owned Termux-compatible bootstrap for this IDE. Do not download an official Termux
bootstrap and assume it can be extracted under this app's package identity. The Termux package
maintainer documentation explains that the package identity is configured through
`TERMUX_APP_PACKAGE` during bootstrap builds and that the bootstrap is intended for the matching
Termux app context. Its current bootstrap archives are tens of megabytes compressed and significantly
larger after installation; a complete compiler package set increases that further.
See the official [Termux package maintainer documentation](https://github.com/termux/termux-packages/wiki/For-maintainers).

## Required release artifact

Each supported ABI must have a separately generated archive with:
- an app-specific package/prefix configuration;
- base shell and required utilities;
- package manager and its runtime dependencies;
- compiler package metadata plus a compatible C/C++ compiler package set;
- all required headers, linker support, libc++ and shared libraries;
- a software bill of materials or package inventory and third-party notices;
- a version, SHA-256 digest, compressed byte count and maximum expanded byte count.

The release manifest must be served over HTTPS and validated before any archive is extracted. Artifact
metadata validation is implemented in `BootstrapArtifact`; the installer must also verify the
downloaded bytes against SHA-256, enforce the size limit while downloading, extract into staging, and
activate only after shell/C17/C++20 smoke tests pass.

## Build pipeline

1. Pin an upstream `termux-packages` revision and record it in the build log.
2. Build the bootstrap in the upstream-supported container workflow with an explicit app package
   identity for this IDE; confirm the resulting prefix and all embedded paths match that identity.
3. Add the minimal package set and dependencies; do not include unrelated language runtimes.
4. Run archive and dependency checks, generate checksums, package inventory and license notices.
5. Publish immutable versioned artifacts and a manifest.
6. Test each archive on physical Android 10/API 29 ARMv7 and ARM64 devices.
7. Only after device tests pass, enable the artifact URL in the app's release manifest.

## Hard release gate

A bootstrap build completing in CI is not proof that the binaries execute inside this IDE. Android
linker behavior, app package prefix, ABI, dynamic dependencies, executable mapping and SELinux must
be tested on a real device. Until then, the UI must report the embedded environment as unavailable,
and must not silently fall back to Termux while claiming standalone operation.
