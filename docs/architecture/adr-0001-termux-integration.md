# ADR 0001: Termux as the IDE toolchain provider

- **Status:** Proposed; architecture decision made, runtime validation pending
- **Date:** 2026-10-26
- **Scope:** `techwithkaushik/c-cpp-ide`
- **Decision owners:** Project maintainers

## Context

The IDE is a native Kotlin/Jetpack Compose application. Termux should provide the user's existing Linux-like environment, files, compiler toolchain, `clangd`, build tools, and debugger where installed. The IDE must not bundle a second Linux distribution or silently copy a project into a private sandbox.

Android apps normally run under different UIDs. An AIDL interface does not grant cross-app file access, process access, or permission to bind to an unexported service. The IDE must use an explicitly supported and user-authorized Termux integration surface. It must not depend on root, disabled SELinux, private Termux paths being directly readable by the IDE, or an undocumented socket/intent.

## Decision

### 1. Separate the IDE's internal IPC from the Termux boundary

Keep AIDL for **IDE app ↔ IDE daemon** communication. Do not treat the existing compiler/LSP AIDL interfaces as if Termux already implements them.

Introduce a future `:integrations:termux` module behind domain-level interfaces. It owns Termux discovery, capability checks, authorization status, command requests, process/session state mapping, and integration-specific errors. Features must not call Termux intents or APIs directly.

Suggested dependency direction:

```text
Compose features
      |
      v
core:domain (workspace, toolchain, process, diagnostics contracts)
      |
      v
integrations:termux (adapter; no UI dependencies)
      |
      +--> documented Termux RUN_COMMAND API (initial capability spike)
      |
      +--> future explicitly supported streaming/session transport
              (only after a tested, user-authorized contract exists)

app / IDE daemon -- AIDL --> IDE-owned service boundary
native JNI: Tree-sitter parsing and PTY/editor-local native work
Termux userland: clang/clang++, clangd, CMake/Ninja, Git, LLDB if installed
```

The IDE daemon is not a privileged proxy. It may orchestrate requests only through the selected integration adapter.

### 2. Use the documented Termux command API for the first integration spike

Evaluate Termux's documented `RUN_COMMAND` service/API as the first supported path for finite commands (toolchain discovery, version checks, compile/build, and short commands). Treat it as opt-in and fail closed if Termux is missing, outdated, not configured for external apps, or permission is denied.

The integration must:
- Explain the external-command access setting and required Android permission to the user; never change Termux settings silently.
- Use explicit executable paths and a structured argument list; do not concatenate user input into a shell command.
- Set a validated working directory and a small explicit environment allowlist.
- Use Termux's documented command execution API only; never access Termux's private home directly from the IDE process.
- Return actionable capability and permission errors rather than silently falling back to a bundled toolchain.
- Bound captured output and report exit status; support cancellation where the public API actually guarantees it.
- Never log source contents, environment secrets, or full command arguments by default.

The exact minimum Termux version, permission declaration, result delivery semantics, and API behavior must be confirmed against the current official Termux documentation and tested on-device before shipping. Do not hard-code assumptions from an old Termux release.

### 3. Do not claim streaming sessions are solved by RUN_COMMAND

An interactive terminal, persistent compiler process, debugger, and LSP require a long-lived, bidirectional byte stream with ordering, backpressure, cancellation, and process-exit events. A finite command/result API alone does not satisfy this contract.

Before implementing terminal or LSP transport, run a focused capability spike and choose one of:
1. An officially supported, user-authorized Termux API that provides persistent bidirectional sessions; or
2. A maintained Termux-side companion/plugin/Termux integration change with a documented, authenticated transport and a clear installation/update story.

Do **not** ship an abstract AF_UNIX cross-UID socket, localhost TCP listener, reflection into Termux internals, fabricated Android Context, root/SELinux changes, or shared-storage polling as the production transport. Shared storage/SAF may be used for project files the user explicitly selects, but is not a process or LSP transport.

If neither supported streaming option passes validation, finite build/run commands may ship first; terminal, debugger, and LSP must remain marked unsupported rather than using a fragile hidden channel.

### 4. Workspace access and file ownership

- A project inside Termux's private home is not directly readable by the IDE under normal Android app isolation.
- The IDE may access files through an explicitly supported Termux-mediated operation or through user-granted Storage Access Framework/shared-storage access.
- SAF access is URI-based and must preserve persisted URI grants. Do not convert arbitrary content URIs to guessed filesystem paths.
- Avoid silently duplicating a workspace: edits, build files, diagnostics, and Git status should refer to one clearly identified source of truth.
- For projects in Termux private home, the UI must show whether access is mediated by Termux or whether the project must be moved/opened through a supported location.

### 5. Tool responsibilities

| Capability | Preferred owner | Integration requirement |
|---|---|---|
| Editing, tabs, navigation, UI state | IDE (Kotlin/Compose) | No Termux dependency |
| Fast incremental syntax highlighting | IDE native layer (Tree-sitter via JNI) | Embedded parser can be packaged independently; do not require a Termux process for every keystroke |
| Compile/build and short commands | Termux toolchain | Documented command API, structured args, explicit workspace |
| Language server (`clangd`) | Termux toolchain | Requires validated persistent stdin/stdout streaming and LSP framing |
| Interactive terminal | Termux userland | Requires validated PTY/session transport; no fake terminal output |
| Debugging (`lldb`) | Termux if installed and supported | Requires real process/session control and verified Android/ABI compatibility |
| Project file access | SAF or Termux-mediated API | Explicit user grant / authorized access |
| IDE daemon lifecycle | IDE-owned service | AIDL only within the IDE's authorized app/service boundary |

Tree-sitter is not an LSP server. Embedding Tree-sitter in the IDE provides fast local parsing; `clangd` remains responsible for semantic diagnostics, completion, navigation, and refactoring.

## Security and reliability invariants

1. No root requirement, SELinux weakening, or Internet permission for local toolchain integration.
2. No direct reads/writes to another app's private data directory.
3. No arbitrary shell-string API. Commands are executable + argument vector + validated cwd + bounded environment.
4. No implicit workspace trust: commands run only after the user opens/trusts a workspace and the UI shows what will execute.
5. Authenticate the caller on any future exported service; validate package/signature or use Android permissions as appropriate. Do not rely on a caller-supplied package name alone.
6. Bound message sizes, stdout/stderr buffers, request counts, and session count. Apply backpressure.
7. Every process/session has an opaque ID, explicit lifecycle, cancellation path, timeout policy, and exit/error event.
8. LSP messages use correct JSON-RPC/LSP framing (`Content-Length` in bytes), incremental document versions, request cancellation, and serialized writes.
9. Never report a capability as available until a real probe succeeds.
10. Preserve the user's existing Termux installation and settings unless the user explicitly approves a documented change.

## Validation plan (must pass before production implementation)

### Gate A — documented API and authorization
- [ ] Verify current official Termux documentation for `RUN_COMMAND`, external-app permission, configuration, minimum supported version, and result delivery.
- [ ] Verify the target Android versions (minimum API 26; include Android 10/API 29) and supported Termux package variant.
- [ ] With external command access disabled, confirm a clear denial and no command execution.
- [ ] With access enabled and permission granted, execute a harmless version probe and verify output + exit code.
- [ ] Test Termux missing, outdated, stopped, denied, and misconfigured cases.
- [ ] Confirm no root, no SELinux change, and no network socket is required.

### Gate B — workspace correctness
- [ ] Open a workspace through an explicit supported grant or Termux-mediated operation.
- [ ] Read, create, modify, rename, and delete a test file; verify changes are visible from both intended views.
- [ ] Test paths with spaces, Unicode, symlinks, nested directories, and traversal attempts.
- [ ] Verify URI grants survive process death/reboot where supported.
- [ ] Prove the IDE never reads Termux's private files directly under its own UID.

### Gate C — build/run command semantics
- [ ] Compile a tiny C and C++ project using explicit argv and cwd.
- [ ] Verify stdout, stderr, exit code, large output truncation, non-ASCII output, and compiler failure.
- [ ] Verify cancellation/timeout behavior; if the public API cannot guarantee cancellation, mark it unsupported.
- [ ] Confirm no shell interpolation by testing spaces, quotes, semicolons, dollar signs, and newline characters in filenames/arguments.

### Gate D — persistent stream proof-of-concept (separate gate)
- [ ] Demonstrate a long-lived child process with bidirectional stdin/stdout/stderr.
- [ ] Prove ordered delivery, bounded buffering/backpressure, process death detection, cancellation, and reconnect/recovery.
- [ ] Launch `clangd`, complete initialize/initialized/shutdown/exit, and exchange a framed request/response.
- [ ] Demonstrate PTY resize and terminal input/output if a real terminal is planned.
- [ ] Verify authorization on Android 10 and current supported Android releases.
- [ ] Reject the transport if it needs an undocumented private API, a cross-UID abstract socket, localhost TCP, root, or SELinux changes.

### Gate E — release criteria
- [ ] CI builds all modules and runs unit tests.
- [ ] On-device smoke tests pass on ARM64 and the project's intended supported ABI(s).
- [ ] Permission, missing-tool, process-crash, low-memory, and app-update recovery paths are tested.
- [ ] User-facing capability matrix distinguishes available, denied, missing, and unsupported features.

## Consequences

**Positive:** Termux remains the source of truth for toolchains and user projects; the IDE avoids duplicating a Linux distribution; platform constraints are explicit; LSP/terminal work cannot silently depend on an unproven transport.

**Trade-offs:** The first milestone is a finite-command integration, not a complete terminal/LSP integration. Private-home workspace support and persistent streams require more validation and possibly a maintained Termux-side component.

## Implementation order

1. Documentation/API research and Gate A spike.
2. Define `:core:domain` contracts and `:integrations:termux` adapter only after API behavior is verified.
3. Implement capability probe + harmless version command + tests.
4. Validate workspace access separately.
5. Decide persistent stream transport only after Gate D evidence.
6. Wire compiler/build; then LSP; then terminal/debugger.
