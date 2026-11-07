# KCode

KCode is the Android IDE application in the KCode Studio ecosystem. It is built with Kotlin and Jetpack Compose and focuses on C/C++, Python, web development files, and an app-owned Linux runtime.

## Product direction

KCode is evolving into an extensible, desktop-class development environment for Android. The existing application is the foundation: working editor, terminal, Linux runtime, SSH, native integration, and build infrastructure should be preserved and improved incrementally.

Planned platform capabilities include:

- Professional code editing and Tree-sitter-based syntax support.
- Language Server Protocol (LSP): completion, hover, diagnostics, navigation, formatting, and code actions.
- Integrated terminal with persistent sessions.
- App-owned Linux environments and toolchains.
- Debug Adapter Protocol (DAP) and LLDB.
- Workspace, project explorer, Git, and search.
- Native KCode extensions and a separately implemented VS Code compatibility layer.

Features are considered complete only after implementation and validation; roadmap items are not claims of current support.

## Current architecture

The Gradle project currently contains these modules:

- `:app`: Android application and Compose IDE shell.
- `:core:aidl`: AIDL interfaces.
- `:core:jni`: native C/C++ helpers and PTY bridge.
- `:core:lsp`: language-server integration.
- `:core:designsystem`: shared Compose design system.
- `:core:logging`: application logging.
- `:feature:editor`: Sora-based editor UI and language integration.
- `:feature:terminal`: terminal UI, sessions, local Ubuntu and SSH workflows.
- `:feature:explorer`: project/file explorer.
- `:services:daemon`: isolated compiler daemon.
- `:integrations:toolchain`: compiler and tool discovery.
- `:integrations:linux-runtime`: Linux rootfs, PRoot, process and SSH runtime integration.

The target architecture and staged migration plan are maintained in [docs/architecture/README.md](docs/architecture/README.md).

## App-owned Linux runtime

This branch uses an app-owned Linux runtime. It does not require access to another terminal app's private data and does not fall back to Android's host shell when the Linux runtime is unavailable.

The app owns the Linux runtime under:

```text
files/linux-runtime/
├── distributions/ubuntu/   # Ubuntu Base rootfs
├── bin/proot               # Android PRoot executable
├── staging/                # private transactional staging
└── runtime.properties      # installed runtime metadata
```

### Ubuntu setup

Open **Settings → Runtime & Bootstrap**.

- **Download official Ubuntu Base 24.04.5** downloads the architecture-matched Ubuntu Base archive and verifies it against Ubuntu's official `SHA256SUMS`.
- **Download pinned Android PRoot** downloads the fixed upstream `proot-rs v0.1.0` Android asset. That upstream release does not publish an asset digest; the app pins the URL/version, enforces a size limit, and validates the ELF architecture. This is a supply-chain and compatibility limitation that must be replaced by a project-built, digest-published PRoot release before production.
- Import options are available for a local Ubuntu rootfs archive or a PRoot archive with an explicit SHA-256.
- A real shell smoke test must pass before setup reports the runtime as ready.

Ubuntu Base is a minimal userspace, not a complete compiler toolchain. Install required tools through Ubuntu's package manager from the Ubuntu shell.

## Terminal capabilities and limitations

The terminal panel supports:

- **Local Ubuntu** — starts a persistent shell through the native PTY bridge and app-owned PRoot runtime.
- **Connect SSH** — opens a remote SSH shell directly from the terminal panel.
- Multiple live tabs that remain in the app process when the bottom panel is hidden and reopened.
- First-connection SSH SHA-256 fingerprint confirmation. Trusted fingerprints are saved app-privately; a changed host key is rejected.
- Password or private-key authentication. Private-key bytes and passwords are not persisted; a Keystore-backed credential vault remains future work.

The terminal view currently has a bounded VT-style parser for common cursor/erase sequences, text output, line-oriented input, and Ctrl-C support. Full color/alternate-screen rendering, resize propagation, and editor/build-to-remote-workspace integration are not yet complete.

## Build and versioning

- Kotlin DSL and Gradle Version Catalog are used.
- Dependency versions are centralized in `gradle/libs.versions.toml`.
- The Android application ID and Kotlin package namespace remain `com.techwithkaushik.cppide` for now to preserve installed-app upgrade compatibility. A package/application ID migration requires a separate explicit migration plan.
- Release version identity is derived from the checked-out Git branch and commit history. CI must fetch complete Git history.

## CI policy

The current branch's `.github/workflows/android-ci.yml` runs Linux runtime and SSH integration tests and builds/verifies the signed ARM32 release APK. It does not rebuild a bootstrap or rootfs on every application commit. Other ABI release flavors remain disabled from release verification until intentionally re-enabled.

Gradle handles APK signing from repository secrets. Runtime distributions remain separately installed app-private assets rather than being bundled into the APK.

## Validation before merging

CI compilation and tests are required for every PR update. A passing CI build does not replace physical-device testing. Before this branch is ready to merge, verify:

1. Ubuntu download/checksum/extraction and rollback on the target device.
2. PRoot startup and shell smoke test on Android 10+ ARM32.
3. Interactive local shell, Ctrl-C, exit, tab persistence, and process cleanup.
4. SSH first-connect fingerprint confirmation, trusted reconnect, and changed-key rejection.
5. C17/C++20 compile-and-run inside Ubuntu.
6. No access to another app's private data and no fallback to Android's host shell.
