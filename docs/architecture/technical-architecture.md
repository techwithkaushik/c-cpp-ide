# KCode Studio — Technical Architecture

**Status:** Living architecture specification  
**Implementation branch:** `feature/ubuntu-runtime-ssh`  
**Repository:** `techwithkaushik/kcode`

This document defines the intended architecture of KCode Studio and the constraints that govern its incremental implementation. It is a design target, not a claim that every capability described below already exists.

## 1. Product direction

KCode is a native Android development environment with a desktop-class coding workflow. Its initial engineering priorities are C/C++ and Python, followed by JavaScript/TypeScript, HTML, CSS, JSON, TOML, YAML, and plain text.

The product should provide:
- A responsive code editor with syntax highlighting, completion, diagnostics, hover, navigation, formatting, and code actions.
- Integrated terminal sessions backed by PTY and the app-managed Linux runtime.
- Project/workspace management and file operations.
- Toolchain and language-server discovery with explicit installation and health states.
- Debugging, Git/SCM, search, and a permissioned extension platform as later capabilities.

The Android app remains a native Kotlin/Jetpack Compose application. The architecture must respect Android lifecycle, process, storage, ABI, and background-execution constraints.

## 2. Repository and module strategy

KCode remains a single Android Gradle monorepo. Gradle modules represent stable build and dependency boundaries; not every source folder needs to become a module.

### 2.1 Existing implementation baseline

The current implementation is organized in these modules:

| Gradle path | Current responsibility |
|---|---|
| `:app` | Android application, Compose shell, startup, dependency composition |
| `:core:contracts` | Shared editor diagnostics contract and editor problem model |\n| `:core:aidl` | AIDL contracts for service/process communication |
| `:core:jni` | Native C/C++ and PTY bridge |
| `:core:lsp` | Existing language-server integration |
| `:core:designsystem` | Shared Compose design system |
| `:core:logging` | Shared logging |
| `:feature:editor` | Sora editor integration and editor UI |
| `:feature:terminal` | Terminal UI and terminal sessions |
| `:feature:explorer` | File and project explorer |
| `:services:daemon` | Isolated compiler/process daemon |
| `:integrations:toolchain` | Toolchain discovery and integration |
| `:integrations:linux-runtime` | PRoot, rootfs, Linux process, and SSH integration |

These paths are the compatibility baseline. Do not rename or move modules merely to make the directory tree look like the target design. Migrate only when a stable interface and measurable benefit exist.

### 2.2 Target source ownership

The following is the intended ownership layout. It may be implemented using fewer, larger Gradle modules where that is safer.

```text
kcode/
├── app/                         # Android composition root
├── editor/
│   ├── api/                     # Document/editor contracts
│   ├── engine/                  # Editing model and engine adapters
│   ├── rendering/               # Rendering integration
│   ├── syntax/                  # Syntax and Tree-sitter integration
│   ├── input/                   # Keyboard, pointer, gesture handling
│   ├── completion/              # Completion presentation/adapters
│   └── decorations/             # Diagnostics, markers, inlays
├── terminal/
│   ├── api/
│   ├── emulator/
│   ├── pty/
│   ├── session/
│   └── process/
├── lsp/
│   ├── protocol/
│   ├── client/
│   ├── server/
│   ├── diagnostics/
│   ├── completion/
│   ├── hover/
│   └── workspace/
├── ui/
│   ├── design-system/
│   ├── components/
│   ├── navigation/
│   ├── panels/
│   ├── dialogs/
│   └── screens/
├── workspace/
│   ├── api/
│   ├── project/
│   ├── filesystem/
│   ├── explorer/
│   └── configuration/
├── runtime/
│   ├── api/
│   ├── proot/
│   ├── rootfs/
│   ├── bootstrap/
│   ├── toolchains/
│   └── environment/
├── debug/
│   ├── dap/
│   ├── lldb/
│   └── breakpoints/
├── scm/
│   ├── git/
│   └── changes/
├── search/
│   ├── text/
│   └── symbols/
├── extensions/
│   ├── api/
│   ├── host/
│   ├── runtime/
│   ├── registry/
│   ├── installer/
│   └── vscode-compat/
├── core/
│   ├── common/
│   ├── contracts/
│   ├── events/
│   ├── logging/
│   ├── settings/
│   ├── storage/
│   ├── ipc/
│   └── lifecycle/
├── build-logic/
├── gradle/
└── docs/
```

### 2.3 Existing-to-target ownership map

| Existing module | Intended ownership |
|---|---|
| `:app` | `app` |
| `:core:aidl` | `core:ipc` after a separately tested migration |
| `:core:jni` | `core:jni` |
| `:core:lsp` | `lsp` |
| `:core:designsystem` | `ui:design-system` |
| `:core:logging` | `core:logging` |
| `:feature:editor` | `editor` plus UI adapter |
| `:feature:terminal` | `terminal` plus UI adapter |
| `:feature:explorer` | `workspace` plus UI adapter |
| `:services:daemon` | Runtime/process service boundary |
| `:integrations:toolchain` | `runtime:toolchains` |
| `:integrations:linux-runtime` | `runtime` |

## 3. Dependency and layering rules

1. `:app` is the composition root. It wires implementations and platform services; it should not become the home of feature business logic.
2. Feature implementations depend on stable contracts, not on other feature implementation details.
3. UI modules render state and dispatch user intent through feature-facing interfaces.
4. The editor engine must not depend on Compose UI.
5. LSP protocol/client code must not depend on editor rendering or Compose.
6. Terminal session management must not own Linux distribution installation or rootfs mutation.
7. Runtime modules must not depend on UI.
8. Debugging through DAP/LLDB is independent of LSP.
9. Extension code receives explicit capabilities rather than unrestricted access to application internals.
10. Avoid circular Gradle dependencies. If two modules need each other, extract the shared contract into a lower-level API module.
11. Filesystem, process, network, archive, and language-server operations must not block the Android main thread.
12. Use structured concurrency and explicit cancellation for long-running work.
13. Attach document versions and workspace identity to asynchronous language results so stale responses can be discarded.
14. Keep Android platform APIs behind adapters where that enables deterministic JVM tests.

## 4. Core subsystem designs

### 4.1 Application and UI

The app owns application startup, lifecycle integration, dependency injection composition, navigation setup, and platform entry points.

Compose UI is divided by responsibility:
- Design system: colors, typography, dimensions, shapes, and theme.
- Reusable components: editor chrome, menus, tabs, toolbars, status items, panels, dialogs.
- Screens: welcome, workspace, editor, settings, runtime setup, and project flows.
- Navigation: routes and navigation state.

UI must observe immutable state and send typed user actions. It must not directly launch shell commands, mutate rootfs files, or manage LSP transports.

### 4.2 Editor

Keep Sora Editor as the current editing engine while stabilizing its integration. Replacing it is a separate decision that requires benchmark and feature-parity evidence.

The editor boundary should expose:
- Open, close, and change document operations.
- Text, selection, cursor, and editing transactions.
- Undo/redo and save state.
- Language and document identity.
- Diagnostics, completion, hover, symbols, navigation, formatting, and code actions.
- Editor decorations such as diagnostic ranges, gutter markers, and inlays.

Editor engine state and document operations must remain independent of Compose rendering. UI adapters may translate engine state into Compose presentation.

### 4.3 Language Server Protocol

The LSP subsystem owns:
- Server executable discovery and launch configuration.
- JSON-RPC transport and framing.
- Initialize, initialized, shutdown, exit, restart, and failure recovery.
- Document open/change/save/close synchronization.
- Completion, hover, diagnostics, definition/references, symbols, formatting, and code actions.
- Cancellation, timeout handling, and stale-result rejection.

A server session is scoped to a workspace and language configuration. A document change increments its version. Results for an older document version must not overwrite newer diagnostics or completion state.

The LSP layer publishes typed results. It does not decide how menus, popups, highlights, or panels are drawn.

### 4.4 Terminal

The terminal subsystem owns terminal emulation and interactive sessions:
- VT emulation and rendering adapter.
- PTY creation and lifecycle.
- Input, output, scrollback, and resize.
- Session attach/detach and process exit reporting.
- Signals and terminal control sequences.

The terminal UI may display runtime status but must not implement distribution installation. Preserve the existing Termux terminal emulator integration and PTY bridge unless tests demonstrate a concrete replacement is better.

### 4.5 Linux runtime and toolchains

Runtime owns:
- PRoot execution and ABI-specific native binaries.
- Distribution/rootfs acquisition, extraction, verification, and repair.
- Bootstrap installation and package environment.
- Runtime environment variables and path mapping.
- Compiler, debugger, and language-server toolchain discovery.
- Runtime process supervision and health checks.

Rootfs changes should be transactional where possible: stage, validate, then activate; on failure preserve the last known-good runtime. Installation state must be explicit and recoverable after process death or device restart.

Terminal sessions consume runtime interfaces. Runtime must not import terminal UI classes.

### 4.6 Debugging

The debug subsystem owns DAP transport and LLDB integration:
- Launch and attach configurations.
- Breakpoints and source mapping.
- Continue, pause, step-in, step-over, and step-out.
- Threads, stack frames, scopes, variables, and debug console.

Debug protocol state is separate from LSP state. Editor decorations are provided through editor-facing contracts.

### 4.7 Workspace, SCM, and search

Workspace owns project identity, filesystem access, project configuration, and explorer data.

SCM owns Git operations and models: status, diff, stage, unstage, commit, branch, history, and conflict state.

Search owns text and symbol search. These systems expose state and operations through contracts; screens and panels remain in UI.

### 4.8 Extension platform and VS Code compatibility

Build a native, versioned KCode Extension API first. The extension host owns manifest validation, lifecycle, registry, installation, version compatibility, and capability grants.

Extension permissions must be explicit and least-privilege. Do not execute untrusted extension code with unrestricted app or filesystem access. Define resource limits and a disable/recovery path before enabling third-party execution.

VS Code compatibility is an adapter over the KCode extension host. It is not a second extension runtime. Support only documented APIs with conformance tests; clearly report unsupported APIs. VSIX parsing/installation does not by itself mean an extension is compatible.

## 5. Build system policy

- Keep dependency versions centralized in `gradle/libs.versions.toml`.
- Use Kotlin DSL and Gradle Version Catalog aliases consistently.
- Introduce `build-logic` convention plugins only after auditing all current plugin declarations and module differences.
- Keep Android application, Android library, Compose, Kotlin, native, and test conventions separate where their requirements differ.
- Do not upgrade AGP, Kotlin, Gradle, NDK, compileSdk, or targetSdk as incidental cleanup.
- Do not add a convention plugin that merely hides a one-line declaration or makes configuration harder to debug.
- Keep release signing configured by Gradle and repository secrets; do not move signing logic into CI workflow scripts.
- Keep APK and bootstrap/runtime artifacts in separate CI workflows and separate artifacts.

## 6. Android compatibility and distribution

The current application identity is:
- Application ID: `com.techwithkaushik.cppide`
- Kotlin namespace baseline: `com.techwithkaushik.cppide`

Preserve these values during product/repository rebranding. Changing the application ID creates a different Android application identity and can prevent existing installs from upgrading. Any package migration requires a separate compatibility and data-migration plan.

The project must retain explicit ABI handling for supported native components. ARM32 is a first-class validation target because the project supports 32-bit Android userspace devices. Validate native libraries and executables for each claimed ABI; host architecture alone does not prove Android userspace compatibility.

## 7. Testing strategy and definition of done

A feature is not complete merely because Kotlin compilation succeeds.

For each change:
1. Build every affected Gradle module.
2. Run unit tests for domain logic and adapters.
3. Run integration tests for process, PTY, runtime, and LSP boundaries using deterministic fixtures where possible.
4. Run Android lint for affected modules.
5. Verify ABI-specific native outputs and package contents.
6. Verify ARM32 release assembly and signing in CI.
7. Test lifecycle and recovery on physical Android devices, including Android 10 where supported.
8. Confirm existing editor, terminal, Ubuntu/PRoot, SSH, and project flows still work.

Subsystem-specific acceptance criteria:

| Subsystem | Minimum acceptance criteria |
|---|---|
| Editor | Text input, selection, undo/redo, save, language switching, no UI freeze |
| LSP | Initialize/shutdown, document sync, completion, hover, diagnostics, cancellation, stale-result rejection |
| Terminal | Start/stop, input/output, resize, scrollback, Ctrl-C, process cleanup |
| Runtime | Rootfs verification, interrupted install recovery, rollback, correct ABI executable |
| Workspace | Open/create project, safe file operations, persist workspace identity |
| Debug | DAP lifecycle, breakpoint mapping, stepping, stack and variable retrieval |
| SCM | Correct status/diff/stage/commit behavior and error reporting |
| Extensions | Manifest validation, API version check, permission enforcement, disable/recovery |

## 8. Incremental implementation roadmap

### Phase 0 — Baseline and architecture documentation
- [x] Rename Gradle root project to KCode.
- [x] Update visible app label and repository documentation.
- [x] Record existing modules and migration ownership.
- [ ] Confirm CI result after the branding commits.
- [ ] Capture a reproducible baseline build/test matrix.

### Phase 1 — Build and dependency hygiene
- [ ] Audit all module plugins and dependency directions.
- [ ] Remove duplicated plugin classpaths only after verifying plugin resolution.
- [ ] Add missing plugin aliases to the Version Catalog where appropriate.
- [ ] Introduce only the convention plugins that reduce duplication without hiding meaningful differences.
- [ ] Add dependency-graph checks for forbidden module directions.

### Phase 2 — Stable contracts and UI boundaries
- [ ] Define document, workspace, terminal-session, runtime, and LSP contracts.
- [ ] Move behavior behind interfaces without changing current UI or runtime behavior.
- [ ] Add contract-level tests and adapter tests.

### Phase 3 — Editor and LSP reliability
- [ ] Verify document open/change/save/close synchronization.
- [ ] Add deterministic LSP integration fixtures.
- [ ] Verify completion, hover, diagnostics, navigation, formatting, and code actions end-to-end.
- [ ] Reject stale responses and cancel obsolete requests.
- [ ] Integrate diagnostics and completion into the editor surface rather than unrelated bottom panels.

### Phase 4 — Runtime and terminal reliability
- [ ] Separate runtime installation state from terminal session state.
- [ ] Verify PTY lifecycle, resize, signals, and cleanup.
- [ ] Verify Ubuntu rootfs integrity and interrupted-install recovery.
- [ ] Verify SSH host-key handling and connection lifecycle.
- [ ] Test ABI-specific PRoot and bootstrap artifacts.

### Phase 5 — Debug, SCM, and search
- [ ] Introduce DAP/LLDB contracts and integration tests.
- [ ] Add Git operations behind SCM interfaces.
- [ ] Add text and symbol search with cancellation and result navigation.

### Phase 6 — Extension platform
- [ ] Publish the first versioned KCode Extension API.
- [ ] Implement manifest validation and capability policy.
- [ ] Implement extension lifecycle and installation.
- [ ] Add VS Code API adapters only with conformance tests.

## 9. Architecture decision records

Important decisions should be captured in separate records under `docs/architecture/adr/`. At minimum:
- Preserve Android application ID during rebranding.
- Retain Sora as the current editor engine until a measured replacement decision.
- Keep a single Gradle monorepo with incremental module boundaries.
- Separate terminal session lifecycle from Linux runtime installation.
- Build native KCode extension contracts before VS Code compatibility.

Each ADR should state context, decision, alternatives, consequences, and status. Superseded decisions should remain in history and link to the replacing ADR.

## 10. Change discipline

- Make one coherent architectural change per commit or small commit series.
- Keep changes on `feature/ubuntu-runtime-ssh` until explicitly asked to merge.
- Do not modify `main` during feature implementation.
- Do not add placeholder screens, fake service responses, TODO-only implementations, or dummy data to make a feature appear complete.
- Do not claim a feature or CI job is complete without a test or run result that demonstrates it.
- Update this document and `docs/architecture/README.md` when implementation status or architectural ownership changes.
