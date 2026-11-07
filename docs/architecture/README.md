# KCode Architecture and Migration Plan

> **Canonical specification:** [Technical Architecture](technical-architecture.md). This file tracks the current migration baseline and execution status; the specification describes the intended end state.

## 1. Architecture decision

KCode remains a single Android Gradle monorepo. Existing working modules are the migration baseline. New architectural boundaries will be introduced incrementally, with regression tests and CI validation at each step.

The application is the composition root. Features communicate through stable contracts; UI code should not own runtime, language-server, terminal-process, or Git business logic.

## 2. Verified current module baseline

| Current Gradle module | Current responsibility | Target ownership |
|---|---|---|
| `:app` | Android application, Compose shell, startup and dependency composition | `app` |
| `:core:contracts` | Editor diagnostics, Linux runtime/toolchain access, workspace identity | `core:contracts` |
| `:core:ipc` | Compiler and LSP Binder/AIDL contracts | `core:ipc` |
| `:core:jni` | Native editor engine, PTY and terminal screen bridge | `core:jni` |
| `:core:logging` | Shared structured developer logging | `core:logging` |
| `:lsp:api` | Language service status and session lifecycle contract | `lsp/api` |
| `:ui:design-system` | Compose design tokens and theme primitives | `ui/design-system` |
| `:feature:editor` | Sora editor adapter, TextMate grammar and clangd integration | `editor` |
| `:feature:terminal` | Terminal UI, local PTY session, SSH shell session and terminal components | `terminal` |
| `:integrations:toolchain` | Linux toolchain discovery and bootstrap artifact handling | `runtime:toolchains` |
| `:integrations:linux-runtime` | PRoot, rootfs install/validation and guest compatibility | `runtime` |
| `:workspace:api` | Workspace repository and project model contracts | `workspace/api` |
| `:workspace:impl` | App-private project storage and safe file operations | `workspace/impl` |
| `:workspace:ui` | Project file explorer UI | `workspace/explorer` |

The former `:feature:explorer` and `:services:daemon` modules were removed because they contained no implementation (the daemon module only declared a service class that did not exist). The Binder contracts remain in `:core:ipc`; a real daemon module will be reintroduced when its service implementation is migrated from working app code and has lifecycle tests.

The target names are architectural ownership labels, not an instruction to create empty modules. Preserve working functionality and introduce new Gradle boundaries only when code and tests justify them.

## 3. Target capability boundaries

### App
- Application entry point and lifecycle.
- Dependency injection composition.
- Feature registration and startup recovery.
- Android platform adapters.

### Core
- Common types and stable contracts.
- Typed application events.
- Logging, settings, storage, IPC, and lifecycle primitives.
- No feature-specific UI.

### UI
- Jetpack Compose design system, navigation, screens, panels, menus, dialogs, and settings.
- Render state and dispatch user intent through feature interfaces.
- No direct shell, PRoot, LSP transport, or Git process management.

### Editor
- Document model, editing transactions, selection, cursor, undo/redo.
- Sora integration retained as the current editing engine.
- Syntax parsing, Tree-sitter integration, decorations, folding, and editor actions.
- Editor engine must not depend on Compose UI.

### LSP
- JSON-RPC and LSP lifecycle.
- Server discovery, initialization, shutdown, restart, and cancellation.
- Completion, hover, diagnostics, navigation, formatting, semantic tokens, and code actions.
- Publishes versioned document results; does not render editor UI.

### Terminal
- Terminal emulator, PTY sessions, input/output, scrollback, resize, and process lifecycle.
- UI state is separate from runtime installation and rootfs management.

### Runtime
- PRoot, Linux distributions, rootfs transactions, bootstrap, environment, toolchains, and process execution.
- Runtime state and validation are explicit and recoverable.
- No dependency on Compose UI.

### Debug
- DAP client, LLDB integration, breakpoints, stack frames, variables, stepping, and debug console.
- Independent from LSP.

### Workspace, SCM, and Search
- Workspace/project model, filesystem access, explorer, and project configuration.
- Git status, diff, stage, commit, branch, and history.
- Text and symbol search.

### Extensions
- Versioned native KCode Extension API, manifest validation, lifecycle, registry, installer, and capability permissions.
- VS Code compatibility is an adapter layer over the native extension host, not a second extension runtime.
- Unsupported VS Code APIs must be reported explicitly; compatibility claims require tests.

## 4. Dependency rules

1. `:app` is the composition root.
2. Feature modules depend on contracts, not one another's implementation details.
3. UI depends on feature-facing interfaces and state models.
4. Editor engine does not depend on Compose.
5. LSP does not depend on editor rendering.
6. Terminal does not own Linux runtime installation.
7. Runtime does not depend on UI.
8. Debug and LSP remain separate protocols and services.
9. Extension code receives declared capabilities, not unrestricted application internals.
10. No circular Gradle dependencies.
11. Use coroutines/Flow for asynchronous work; never block the main thread with filesystem, process, or language-server I/O.
12. Keep document versions attached to asynchronous diagnostics and completion results to reject stale responses.

## 5. Migration sequence

### Stage A — Stabilize and document the existing baseline
- Preserve the existing application ID and Kotlin package namespace to maintain upgrade compatibility.
- Rename the Gradle root project and user-visible app label to KCode.
- Record existing modules, CI tasks, ABI flavors, and runtime limitations.
- Establish architecture tests and module ownership.

### Stage B — Build conventions
- Introduce `build-logic` convention plugins only after confirming the current plugin configuration.
- Centralize Android library/application, Compose, Kotlin, native, and test conventions.
- Keep dependency versions in `gradle/libs.versions.toml`.
- Do not upgrade AGP, Kotlin, Gradle, or SDK versions as part of unrelated refactoring.

### Stage C — Contracts and UI boundaries
- Extract stable contracts for workspace, editor, terminal, runtime, LSP, and extension services.
- Move Compose-only design code under UI ownership without changing visual behavior.
- Keep adapters at module boundaries.

### Stage D — Editor and LSP
- Retain Sora as the current editor engine while fixing editor/LSP synchronization.
- Verify open/change/close document lifecycle and document versioning.
- Implement cancellation, completion, hover, diagnostics, navigation, formatting, and code actions incrementally.
- Add integration tests using a deterministic language-server fixture.

### Stage E — Terminal and runtime
- Preserve the working PTY, Ubuntu, PRoot, and SSH flows.
- Separate terminal session state from runtime installation state.
- Validate lifecycle, process cleanup, terminal resize, Ctrl-C, SSH host-key verification, and rootfs rollback on physical Android devices.

### Stage F — Debug, SCM, search, and extensions
- Add DAP and LLDB behind explicit contracts.
- Add Git and search capabilities without coupling them to UI internals.
- Design and version the native extension API before implementing marketplace distribution.
- Build VS Code compatibility only for APIs covered by conformance tests.

## 6. Quality gates

For each migration:
- Build affected Gradle modules.
- Run their unit and integration tests.
- Run Android lint where configured.
- Verify the ARM32 CI build and other supported ABI jobs.
- Keep APK and runtime distribution artifacts separate.
- Test Android 10+ and the project's low-memory target devices.
- Do not mark a capability complete based solely on compilation.

## 7. Compatibility policy

The Android application ID and existing Kotlin package namespace currently remain `com.techwithkaushik.cppide`. Changing them would create a different Android application identity and can prevent existing installations from upgrading in place. A package migration must be separately planned, tested, and communicated.

Repository and product branding can change independently of Android application identity.

## 8. Immediate next engineering tasks

1. Verify the KCode rename and current branch CI.
2. Audit plugin configuration and the actual Gradle module dependency graph.
3. Add build conventions without changing toolchain versions.
4. Define editor/LSP contracts around the existing Sora implementation.
5. Add integration tests for document synchronization and editor diagnostics.

## 10. Gradle plugin audit — 2026-11-07

- [x] Reviewed the root plugin declarations and all existing Android module build scripts.
- [x] Moved the existing KSP plugin version (`2.3.13`) into `gradle/libs.versions.toml`.
- [x] Registered KSP in the root plugin block with `apply false` and applied it in `:app` through the Version Catalog alias.
- [x] Preserved the existing AGP, Kotlin, KSP, Gradle, SDK, and NDK versions.
- [ ] Verify the KSP catalog migration and ARM32 release build in GitHub Actions. The GitHub commit-status endpoint currently reports no status entries, so this change is not yet marked CI-verified.
- [ ] Audit source-level dependency directions before extracting new API modules.
- [ ] Introduce `build-logic` only after the dependency and plugin audit identifies conventions that can be shared safely.

### Audit observations

The current project has meaningful differences between Android application, Compose library, native JNI, AIDL, and plain Android library modules. A single broad convention plugin would risk hiding those differences. Keep the existing build scripts explicit for now; first establish dependency direction and test coverage, then extract only proven shared conventions.


## 9. Shared editor contract extraction — 2026-11-07

- [x] Added `:core:contracts` as a dependency-light Android library module.
- [x] Moved the shared `EditorProblem` model into the contracts module without introducing Sora, LSP4J, Compose, or runtime dependencies.
- [x] Defined `EditorDiagnostics` as a read-only `StateFlow` contract.
- [x] Updated `ClangdProjectSession` to implement the shared diagnostics contract.
- [x] Kept the existing `feature.editor.EditorProblem` source name as a typealias for compatibility.
- [x] Exposed `:core:contracts` through the editor module's public API dependency.
- [ ] Verify Gradle configuration, editor compilation, and ARM32 release CI before migrating additional contracts.

This is the first live contract migration. Do not move terminal, workspace, or runtime implementations until their public call sites and dependency graph have been inspected.


## 11. Runtime, toolchain, and workspace boundary migration — 2026-11-07

- [x] Added `LinuxRuntimeEnvironment` and `LinuxToolchainAccess` contracts under `:core:contracts`.
- [x] Made the Linux runtime paths and toolchain discovery implementations implement those contracts.
- [x] Removed concrete Linux runtime and toolchain dependencies from `:feature:editor`; the app composition root now supplies the implementations.
- [x] Added `:workspace:api` with `WorkspaceRepository` and `WorkspaceProject`.
- [x] Added `:workspace:impl` and moved project storage out of `:app`.
- [x] Updated the app to consume the workspace repository interface while keeping the existing app-private storage layout and metadata format.
- [ ] Validate all new module and dependency changes with the ARM32 release workflow.
- [ ] Move terminal runtime installation/session orchestration behind runtime-owned interfaces; current terminal screens still call runtime integration classes directly.
- [ ] Move remaining app-owned workspace orchestration and navigation state behind workspace-facing contracts.

### Current dependency boundary

The editor no longer imports `:integrations:linux-runtime` or `:integrations:toolchain`; those concrete modules are wired by `:app`. Workspace persistence now lives in `:workspace:impl` and is accessed through `:workspace:api`. Terminal/runtime separation is not yet complete and remains an explicit migration item.


## 12. LSP and UI design-system ownership migration — 2026-11-07

- [x] Added `:lsp:api` with a protocol-agnostic language service status and workspace-scoped session lifecycle contract.
- [x] Adapted `ClangdProjectSession` to the language-server API while preserving the existing `EditorLspStatus` source name as a typealias.
- [x] Removed the empty legacy `:core:lsp` module from Gradle settings; the actual Sora/clangd adapter remains with the editor until a protocol-independent client implementation is extracted.
- [x] Moved the Compose design token source from `:core:designsystem` to `:ui:design-system` and updated all current consumers.
- [x] Updated app, editor, terminal, and explorer Gradle dependencies to the new design-system ownership.
- [ ] Verify the LSP API and design-system module migration with the ARM32 release workflow.
- [ ] Extract terminal/runtime orchestration behind `:core:contracts` APIs before removing terminal's direct runtime integration dependency.


## 13. Terminal session ownership migration — 2026-11-07

- [x] Moved the real local PTY session implementation from `:integrations:linux-runtime` into `:feature:terminal`.
- [x] Moved SSH host configuration, host-key pinning, and SSH shell session implementation into `:feature:terminal`.
- [x] Kept Android supplementary-group compatibility repair in the Linux runtime module and made terminal PTY startup consume that runtime-owned helper.
- [x] Removed the JSch dependency from `:integrations:linux-runtime`; SSH dependency remains with the terminal module.
- [ ] Verify terminal session relocation and ABI-specific release packaging with CI.
- [ ] Replace terminal screens' direct construction of rootfs installers, PRoot validators, and runtime smoke tests with an app-provided runtime operations interface.
- [ ] Move terminal UI and runtime setup UI into separate feature surfaces without changing current workflows.


## 14. Terminal/runtime ownership map correction

The local PTY session and SSH shell/session implementation now belong to `:feature:terminal`. PRoot, rootfs acquisition/extraction, executable validation, runtime smoke tests, and guest compatibility remain in `:integrations:linux-runtime`. The remaining direct calls from terminal UI into runtime installers are an adapter boundary still to be completed; this migration does not claim that separation is finished.


## 15. IPC module ownership migration

- [x] Moved compiler and LSP AIDL contracts from `:core:aidl` to `:core:ipc`.
- [x] Preserved the generated AIDL package `com.techwithkaushik.cppide.ipc` so Binder interface names remain compatible.
- [x] Updated app, daemon, and settings Gradle references to `:core:ipc`.
- [ ] Verify AIDL generation and ARM32 release build in CI.
