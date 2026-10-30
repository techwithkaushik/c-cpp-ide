# Embedded Termux Runtime for Code Studio

## Decision

Code Studio must run its own app-private Termux-compatible runtime. It must not use the external Termux app's `RUN_COMMAND` intent, require the Termux app to be installed, or launch Termux for builds.

## Upstream findings

- Termux's `RUN_COMMAND` intent is explicitly an external-app-to-Termux IPC interface and requires the Termux permission plus user opt-in. It is not embedded runtime integration.
- Termux's `TermuxInstaller` installs a bootstrap archive into a staging prefix, restores executable permissions and symlinks from `SYMLINKS.txt`, atomically moves the staging prefix into place, and writes the shell environment.
- Termux bootstrap binaries are built for a specific package identity/prefix. A bootstrap built for `com.termux` or AndroidIDE's package must not be assumed to work under Code Studio's application ID.
- Termux package-maintainer docs describe generating custom bootstrap archives by setting `TERMUX_APP_PACKAGE` and rebuilding the packages/bootstrap for the target identity.

## Required implementation for Code Studio

### Packaging model: automatic CI build, APK embedding, automatic first-run installation

AndroidIDE follows the Termux packaging model: Gradle downloads or consumes an ABI-matched bootstrap, verifies its SHA-256, embeds it into `libtermux-bootstrap.so`, and ships it inside the APK. When the runtime setup screen appears, Code Studio automatically verifies and extracts the embedded bootstrap into app-private storage, then runs a shell smoke test. The user does not build, download, select, or import a bootstrap manually.

Code Studio cannot directly reuse AndroidIDE's bootstrap because the archive must match `com.techwithkaushik.cppide` and its compiled prefix. The Android CI pipeline therefore builds the four custom bootstraps from a pinned Termux packages commit in parallel, caches each result by source/package/architecture, validates the archive and creates a checksum manifest, then makes the release job wait for all four results. The release job downloads each generated artifact internally, verifies it, embeds only the matching ABI archive, and uploads each APK separately. No manual bootstrap provisioning step is required.

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
- The terminal setup screen automatically installs the embedded bootstrap when shown and exposes a retry only if setup fails. The app validates package identity, ABI, prefix, checksum and then requires the shell smoke test to pass before marking the runtime ready.
- For local debug/device smoke tests only, `BOOTSTRAP_ALLOW_UNAPPROVED=1` permits staging an unapproved artifact; release CI does not set this override. The debug setup UI permits installation of an unapproved artifact only in a debuggable build.
- Android CI retains a quick synthetic-fixture smoke job for native packaging, but those debug APKs are explicitly test fixtures, not usable runtime builds. The dependent release job builds and embeds real package-specific bootstrap archives automatically.
- No package-compatible archive has yet been confirmed as approved, and native compilation plus on-device shell/compiler tests remain to be verified by CI and a real device. Do not treat vendored terminal UI modules or a successful APK build as proof of a working Linux runtime.
