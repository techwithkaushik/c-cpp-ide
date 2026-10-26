# Embedded Termux-compatible build environment

## Goal

The IDE must install and run its own shell, package manager, Clang toolchain, headers and libraries.
The Termux Android app must not be required or detected as a prerequisite. The user should edit
C17/C++20 source and press **Compile & Run** without leaving the IDE.

This is an embedded distribution, not a wrapper around another installed app. The environment lives
under the IDE's private files directory and is preserved across app launches and upgrades where
compatible.

## Architecture decision

Use a versioned, app-owned Termux-compatible distribution with a package prefix owned by this app.
Integrate a terminal emulator UI as a view inside the IDE, but keep terminal rendering separate from
the shell/process supervisor. Compile and run commands must execute through the embedded environment
runner, never through Termux RUN_COMMAND, Termux intents, or paths under
`/data/data/com.termux`.

Do **not** copy binaries from an installed Termux app at runtime. Do not bundle a desktop-host NDK
compiler executable. Package binaries must be built or selected for Android and the device ABI, with
their runtime paths and dependencies validated for the IDE's private prefix.

## First-run setup

1. Detect the device ABI and Android API level.
2. Show the runtime version, estimated download/storage size and setup progress.
3. Download a versioned bootstrap artifact from a project-controlled release endpoint.
4. Verify the artifact against a SHA-256 digest from a versioned manifest before extraction.
5. Extract into a staging directory. Reject absolute paths, traversal, symlinks unless explicitly
   supported and validated, excessive file counts, and excessive expanded size.
6. Configure the app-owned prefix and environment. Never assume the Termux app's fixed prefix.
7. Install only the initial package set: shell/base utilities, package manager, Clang, C/C++ runtime
   headers/libraries, and required linker/runtime dependencies.
8. Run health checks: shell starts; compiler reports its version; a C17 smoke test compiles and
   executes; a C++20 smoke test compiles and executes.
9. Atomically activate the environment only after all checks pass. Keep the previous known-good
   environment until activation succeeds.

If setup is interrupted, retain a recoverable state and allow retry. Do not report setup as ready
until all smoke tests pass. Network access is only required for initial setup/package installation
and updates; local builds should work offline after the required packages are installed.

## Runtime layout

The exact prefix is an implementation detail, but the ownership boundary should be:

- `files/embedded-env/<distribution-version>/<abi>/` — versioned runtime
- `files/embedded-env/staging/` — incomplete downloads and extraction
- `files/workspace/` — project files owned by the IDE
- `files/cache/` — disposable package/download cache

The terminal and build runner receive environment variables from a single environment provider:
`HOME`, `PREFIX`, `PATH`, `TMPDIR`, `LD_LIBRARY_PATH`, and any required Android-specific
variables. Do not hardcode Termux app data paths in the editor or build runner.

## Minimal initial package set

- a compatible POSIX shell and essential base utilities
- package manager and package metadata
- Clang driver and target tools
- C headers and Android target/linker support
- libc++ headers and runtime for C++20
- shared-library dependencies required by the selected distribution
- optional build tools such as `make` only when requested

Avoid installing Python, Node.js, Rust, GUI desktop packages or other language toolchains by default.
They are outside the initial C/C++ IDE scope.

## Terminal and editor integration

The terminal is an in-app pane backed by a supervised process session. It is not a fake terminal
that only displays build output. The initial version should support shell input/output, interrupt,
working-directory changes and resize. The editor's Compile & Run action should call a typed build
service that invokes the selected compiler with an argument list, not a concatenated shell command.
Compiler output and program output should appear in the result/terminal panel.

## Security and reliability

- Keep the environment and project files in app-private storage by default.
- Treat source files, command arguments, package metadata and downloaded archives as untrusted.
- Use TLS, a pinned/versioned manifest digest and archive integrity checks.
- Never run package setup with root, disable SELinux, or depend on localhost TCP.
- Do not execute downloaded code until artifact verification and installation checks pass.
- Apply timeouts and bounded output capture to compile/run processes.
- Do not expose arbitrary app-private paths to external apps.
- Make runtime updates transactional and recoverable.

## Important Android compatibility gate

An Android ABI match alone does not guarantee that a Linux/Termux package can execute inside an
ordinary Android app. The package's dynamic linker, libc assumptions, executable location, SELinux
policy, Android API level and native dependencies must all be validated on target devices. Writable
app-private directories can also have execution restrictions on some Android configurations.

The current project has an early standalone runtime manager, but no verified embedded distribution
artifact. Do not mark the environment as ready or advertise Termux independence until the actual
bootstrap artifact passes on-device tests.

## Acceptance criteria

- App installs and opens with Termux absent.
- First-run setup installs the environment and can recover from cancellation/failure.
- Shell opens inside the app and accepts commands.
- C17 and C++20 smoke tests compile and execute successfully.
- Compile diagnostics and program stdout/stderr are visible in the IDE.
- Builds still work after network is disabled, once setup is complete.
- Reopening the app does not reinstall packages.
- App upgrades preserve workspace and either reuse or safely migrate the runtime.
- Tested on Android 10 ARMv7 (API 29) and ARM64; CI alone is not considered device validation.
