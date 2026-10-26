# C/C++ IDE for Android

A native Android C/C++ IDE foundation built with Kotlin, Jetpack Compose, Kotlin DSL, Version Catalogs, AIDL, and JNI/C++.

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
