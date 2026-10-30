# Embedded Termux Runtime for Code Studio

## Decision

Code Studio must run its own app-private Termux-compatible runtime. It must not use the external Termux app's `RUN_COMMAND` intent, require the Termux app to be installed, or launch Termux for builds.

## Upstream findings

- Termux's `RUN_COMMAND` intent is explicitly an external-app-to-Termux IPC interface and requires the Termux permission plus user opt-in. It is not embedded runtime integration.
- Termux's `TermuxInstaller` installs a bootstrap archive into a staging prefix, restores executable permissions and symlinks from `SYMLINKS.txt`, atomically moves the staging prefix into place, and writes the shell environment.
- Termux bootstrap binaries are built for a specific package identity/prefix. A bootstrap built for `com.termux` or AndroidIDE's package must not be assumed to work under Code Studio's application ID.
- Termux package-maintainer docs describe generating custom bootstrap archives by setting `TERMUX_APP_PACKAGE` and rebuilding the packages/bootstrap for the target identity.

## Required implementation for Code Studio

### Packaging model: follow AndroidIDE/Termux, not a bootstrap build on every APK build

AndroidIDE follows the Termux packaging model: it consumes a prebuilt bootstrap ZIP, verifies its SHA-256 during the Android build, embeds the ZIP bytes into an ABI-specific native library using an assembly `.incbin`, and ships that library inside the APK. At first launch, the installer loads the native library for the device ABI and extracts the embedded ZIP into the app-private prefix. The bootstrap is not rebuilt as part of each normal APK build.

Code Studio should adopt that same packaging pattern, with one non-negotiable compatibility constraint: the archive must be built for `com.techwithkaushik.cppide` and the matching prefix. We must not embed AndroidIDE's own archive or stock Termux's archive and assume that rewriting `SYMLINKS.txt` fixes compiled-in paths.

The custom package-specific bootstrap must be produced and audited once per supported ABI, then published as immutable versioned artifacts. Normal APK CI should only download the pinned artifacts, verify checksums, embed the matching ABI archive, build the APK, and fail closed if any required archive or checksum is missing. Bootstrap source-building belongs in a separate maintainer/provisioning operation only when intentionally upgrading the bootstrap—not in every APK CI run. Do not create a release checksum from an unverified archive.

### Implementation steps

1. Produce and publish a reproducible bootstrap for `com.techwithkaushik.cppide` from the Termux package build system. Include only the base packages required for shell/package management initially; install clang, Python and language tooling as optional packages.
2. Pin each bootstrap release URL, version, upstream revision, target ABI and SHA-256 in a dedicated runtime configuration file. Do not download an unpinned archive or trust a checksum fetched from the same untrusted location as the archive.
3. **Implemented in the app build:** `app/src/main/cpp/bootstrap_zip.S` embeds the staged ABI-specific ZIP with `.incbin` into `libtermux-bootstrap.so`. Each app flavor passes its own archive path to CMake, `jniLibs.useLegacyPackaging` enables native-library extraction, and `EmbeddedBootstrap` reads the archive bytes through JNI. The ZIP is staged under `app/build/generated/bootstrapNative/`; only `bootstrap.json` is packaged as an asset, avoiding a second copy of the large archive in the APK.
4. Add an app-owned bootstrap installer that:
   - uses an app-private prefix under `filesDir`;
   - extracts into a staging directory and validates paths against zip-slip;
   - reads `SYMLINKS.txt` using the upstream format and recreates links safely;
   - applies executable permissions;
   - runs the bootstrap second-stage script if required by the generated archive;
   - atomically promotes staging only after validation;
   - records install state and supports safe retry/repair.
5. Add an app-owned process launcher that configures `PREFIX`, `HOME`, `TMPDIR`, `PATH`, loader variables and working directory for the Code Studio prefix. Verify the actual shell execution path on Android; ordinary JVM `ProcessBuilder` alone must not be assumed to handle Termux's linker/exec requirements.
6. Gate the Welcome → Bootstrap → Ready → Projects navigation on real smoke tests. A ready state requires a working shell and successful command exit, not merely a completed download.
7. Keep projects and source files in app-owned storage; do not change or write to the user's existing Termux installation.
8. Add tests for interrupted extraction, malformed archive, symlink handling, checksum mismatch, retry, missing ABI artifact and shell smoke-test failure.

## ABI and packaging

Generate and test bootstrap artifacts for each Android ABI that Code Studio officially supports. Do not label an ABI as supported until its bootstrap and native execution path pass on a device/emulator. The app currently has `applicationId = com.techwithkaushik.cppide`; the bootstrap build configuration must use the same identity/prefix.

## Upstream references

- Termux app: https://github.com/termux/termux-app
- Termux installer: https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java
- Termux package maintainer/bootstrap documentation: https://github.com/termux/termux-packages/wiki/For-maintainers
- RUN_COMMAND (external IPC only; deliberately not used by Code Studio): https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

## Status

- Native `.incbin` embedding, ABI-specific CMake input paths, JNI byte access, and setup-screen integration are implemented.
- `scripts/prepare-bootstrap-assets.sh` validates package identity, prefix, ABI, version and SHA-256, then stages the ZIP for native embedding. Release packaging refuses artifacts that do not carry `approvedForDistribution: true`.
- The bootstrap generation workflow emits `bootstrap.zip` plus `bootstrap.json`, but marks each result unapproved. A maintainer must audit the archive and successfully test shell execution on a device before setting that approval flag.
- For local debug/device smoke tests only, `BOOTSTRAP_ALLOW_UNAPPROVED=1` permits staging an unapproved artifact; release CI does not set this override. The debug setup UI permits installation of an unapproved artifact only in a debuggable build.
- No package-compatible archive has yet been confirmed as approved, and native compilation plus on-device shell/compiler tests remain to be verified by CI and a real device. Do not treat vendored terminal UI modules or a successful APK build as proof of a working Linux runtime.
