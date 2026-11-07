# KCode Architecture and Migration Plan

> **Canonical specification:** [Technical Architecture](technical-architecture.md). This file tracks the current migration baseline and execution status; the specification describes the intended end state.

## 1. Architecture decision

KCode remains a single Android Gradle monorepo. Existing working modules are the migration baseline. New architectural boundaries will be introduced incrementally, with regression tests and CI validation at each step.

The application is the composition root. Features communicate through stable contracts; UI code should not own runtime, language-server, terminal-process, or Git business logic.

## 2. Verified current module baseline

| Current Gradle module | Current responsibility | Target ownership |
|---|---|---|
| `:app` | Android app, Compose shell, startup and dependency composition | `app` |
| `:core:contracts` | Shared editor diagnostics contract and editor problem model | `core:contracts` |
| `:core:aidl` | AIDL contracts for process/service communication | `core:ipc` (after migration) |
| `:core:jni` | Native C/C++ and PTY bridge | `core:jni` |
| `:core:lsp` | Existing language-server integration | `lsp` |
| `:core:designsystem` | Shared Compose design system | `ui:design-system` |
| `:core:logging` | Shared logging | `core:logging` |
| `:feature:editor` | Sora editor integration and editor UI | `editor` plus UI adapter |
| `:feature:terminal` | Terminal UI and terminal sessions | `terminal` plus UI adapter |
| `:feature:explorer` | File/project explorer | `workspace` plus UI adapter |
| `:services:daemon` | Isolated compiler daemon | `runtime` / process service boundary |
| `:integrations:toolchain` | Toolchain discovery | `runtime:toolchains` |
| `:integrations:linux-runtime` | PRoot, rootfs, Linux process and SSH integration | `runtime` |

The target names are architectural ownership labels, not an instruction to move every source file immediately. Preserve existing Gradle paths until a migration has a clear benefit and tests.

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
