# Code Studio — Multi-language IDE for Android

A VS Code-inspired, multi-language Android IDE built with Kotlin, Jetpack Compose, AIDL, and native integrations. The architecture targets file-aware language tooling, a managed Linux userspace, terminal sessions, and project-level toolchain configuration.

## Module architecture

```text
c-cpp-ide/
├── app/                         # Compose app shell, navigation, Hilt composition root
├── services/
│   └── daemon/                  # Isolated compiler/debugger process and AIDL service implementations
├── core/
│   ├── aidl/                    # Stable IPC contracts shared by client and daemon
│   ├── jni/                     # C++20, CMake, Tree-sitter and PTY JNI bridge
│   ├── lsp/                     # LSP protocol models, transport and client orchestration
│   └── designsystem/            # Material 3 theme and reusable editor-oriented UI primitives
├── feature/
│   ├── editor/                  # Workspace, tabs, editor state and code rendering
│   ├── terminal/                # Terminal UI and PTY session presentation
│   └── explorer/                # Workspace file tree and Git status presentation
├── gradle/
│   └── libs.versions.toml       # Central dependency and plugin catalog
├── settings.gradle.kts          # Central module registry and repository policy
└── build.gradle.kts              # Root plugin declarations
```

## Process and data boundaries

- `:app` and feature modules own UI state and presentation; long-running compiler/debugger processes must never run on the UI thread.
- `:services:daemon` is intended to run in a dedicated Android process (`android:process=":ide_daemon"`) and exposes the AIDL contracts.
- `:core:aidl` is the IPC contract module; keep its types stable and parcelable-friendly.
- `:core:lsp` owns LSP framing/JSON and request lifecycle. The daemon should own local toolchain process I/O and relay bounded events to clients.
- `:core:jni` contains native C++20 integrations (Tree-sitter/PTY); native calls must not block Compose's main thread.

## Foundation status

This commit establishes the module registry, dependency catalog, and initial compiler/LSP IPC contracts. Module implementations, Android manifests, native dependencies, and executable toolchain packaging are intentionally staged for later steps.


## Runtime and bootstrap direction

The standalone IDE must work without requiring a separately installed Termux app and must not ask users to paste a compiler/rootfs URL. Linux setup completion must mean the selected userspace and Android-compatible execution runtime have passed a real shell smoke test—not just that a rootfs archive was extracted. The selected direction is an app-managed Ubuntu rootfs bootstrap with ABI selection and a project-controlled, versioned manifest. The rootfs is only a userspace base: the execution layer and Android-compatible compiler/runtime must be proven before it can be marked ready.

Read [ADR 0002 — Ubuntu rootfs bootstrap](docs/architecture/adr-0002-ubuntu-rootfs-bootstrap.md) for the bootstrap flow, security constraints, and device validation gates. The first-run setup has two paths—[Download from Internet or Use Downloaded Rootfs from Storage](docs/architecture/rootfs-setup-screen.md)—and both use the same validation and installation pipeline. The existing `:integrations:toolchain` implementation contains archive/download safety primitives, but no production Ubuntu rootfs artifacts or validated Android execution layer are claimed yet.

## Optional Termux integration spike

Termux remains a separate optional integration experiment, not a prerequisite for the standalone runtime. **The cross-app transport has not yet been validated on-device.** AIDL describes communication inside the IDE; it does not grant access to Termux's private files or implement a Termux service.

Read these architecture records before adding a Termux adapter:

- [ADR 0001 — Termux integration architecture](docs/architecture/adr-0001-termux-integration.md)
- [Termux capability spike — on-device validation matrix](docs/architecture/termux-capability-spike.md)

The first spike evaluates the documented Termux command API for finite commands. Persistent clangd LSP, terminal, and debugger sessions require a separately proven, authorized bidirectional transport. They must not be implemented using an unverified cross-UID socket, localhost TCP, root, SELinux changes, or direct reads of Termux private data. Do not mark these capabilities supported until their validation gates pass.


## Current Termux spike

The current branch includes a temporary diagnostic screen and a minimal `:integrations:termux` adapter. The screen checks whether Termux is visible, whether its RUN_COMMAND service resolves, whether the IDE permission is granted, and whether a fixed harmless command returns the expected marker.

This remains a **capability spike, not a device-validated production compiler integration**. The adapter has an initial argv-based C/C++ compile-and-run spike using Termux clang/clang++, with bounded arguments and executable output restricted to Termux home or usr/tmp. This is one language adapter, not the IDE's product scope. It does not yet provide workspace synchronization, a code editor flow, persistent LSP streams, terminal PTY, or debugging. The compile/run path has unit tests but still requires a successful CI run and real-device validation. See [the validation matrix](docs/architecture/termux-capability-spike.md) before treating any capability as supported.
