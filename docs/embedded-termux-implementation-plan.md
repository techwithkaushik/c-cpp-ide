# Embedded Termux Runtime — Implementation Plan

**Project:** Code Studio (com.techwithkaushik.cppide)  
**Branch:** architecture/termux-integration  
**Status:** Architecture and implementation gates; runtime is not yet production-ready.  
**Scope:** App-private Termux-compatible environment, no external Termux app, no Termux RUN_COMMAND IPC, Android 10+.

## 1. Non-negotiable architecture decisions

1. Code Studio owns its runtime, prefix, home, temporary files, package database and package lifecycle under its own app-private storage.
2. Never read, modify, or depend on another Termux installation.
3. Never use the external Termux app or its RUN_COMMAND permission/intent.
4. Build a bootstrap specifically for com.techwithkaushik.cppide. Do not assume a com.termux or AndroidIDE bootstrap has a compatible prefix, paths, package metadata or repository configuration.
5. Treat native execution on Android as an explicit compatibility gate. A successful download or extraction is not proof that shell commands can execute.
6. Do not disable SELinux, weaken Android security policy, or introduce a root requirement to make the runtime work.
7. Do not ship an installer that downloads and executes an unpinned archive or trusts a checksum served from the same untrusted location as the archive.
8. Keep the first-run environment small. Compiler and language tooling are separately installable packages, not all embedded in the APK.

## 2. Target architecture

    Compose UI
      Welcome -> RuntimeBootstrap -> RuntimeReady -> Projects / Editor
                                  |
                                  v
                           RuntimeCoordinator
                                  |
                 +----------------+----------------+
                 |                |                |
           BootstrapManager   PackageManager   RuntimeHealth
                 |                |                |
                 +----------------+----------------+
                                  |
                             ProcessRunner
                                  |
                       Native execution adapter
                                  |
           App-private Termux-compatible prefix and environment
           PREFIX / HOME / TMPDIR / PATH / LD_LIBRARY_PATH
                                  |
          bash + core utilities + apt/pkg + installed packages
            |           |            |          |
          clang      Python       Node.js     LSP/Tree-sitter

Suggested module boundaries (adapt names to the existing Gradle layout rather than creating duplicate layers):

- core:runtime-api: runtime state, command request/result models and interfaces.
- integrations:termux-runtime: bootstrap metadata, installer, environment builder and native execution adapter.
- services:daemon: optional process/session supervision; it must not be a security boundary or require exported IPC.
- feature:terminal: terminal UI and session lifecycle, consuming runtime APIs only.
- feature:editor: build/run and language tooling actions, consuming runtime APIs only.
- app: navigation and dependency wiring.

Keep filesystem/package operations off the UI thread. Expose structured progress and errors instead of passing shell output into navigation logic.

## 3. Phase 0 — repository and upstream audit

1. Inventory existing modules, app navigation, toolchain integration, daemon, permissions, ABI filters, CI and any leftover external-Termux code.
2. Remove stale integration source only after checking all references. Removing a Gradle dependency alone does not prove that obsolete source files are gone.
3. Confirm the app ID, supported ABIs, min SDK, target SDK, NDK version, native build system and signing/release setup.
4. Pin an upstream Termux source revision and review the relevant licenses and notices before copying or modifying code.
5. Produce an artifact manifest: upstream commit, build inputs, package identity, ABI, bootstrap version, archive SHA-256 and license inventory.
6. Add CI checks that fail if the manifest is missing or an unapproved external-Termux intent/permission is reintroduced.

**Exit gate:** audited dependency graph and explicit ABI support matrix.

## 4. Phase 1 — reproducible custom bootstrap

1. Build from the Termux package build system with TERMUX_APP_PACKAGE=com.techwithkaushik.cppide.
2. Clean/rebuild packages when the package identity changes; package prefixes and compiled paths can be embedded in package artifacts.
3. Use the upstream bootstrap-build path for a custom app identity. Do not point custom-prefix packages at official com.termux repositories and assume compatibility.
4. Start with only the base packages needed for a usable shell, basic utilities and package management. Keep Clang, Python, Node.js, language servers and Tree-sitter out of the base image.
5. Build only for explicitly supported Android ABIs. Publish immutable versioned artifacts, SHA-256 values and provenance from CI.
6. Store release URL, version, ABI and expected digest in a reviewed runtime manifest. The expected digest must be pinned in the app/release metadata or authenticated through a separately trusted release process.
7. Verify licensing and package-repository obligations before distributing bootstrap archives or packages.

**Exit gate:** reproducible custom-prefix artifacts, checksum validation, and a CI artifact for each claimed ABI. Do not claim 32-bit ARM or any other ABI support until native execution is tested on that ABI.

## 5. Phase 2 — secure bootstrap installer

Implement a state machine such as:

NOT_INSTALLED -> DOWNLOADING -> VERIFYING -> EXTRACTING -> CONFIGURING -> SMOKE_TESTING -> READY

Any failure transitions to a typed error state; retry starts from a safe checkpoint.

Installer requirements:

1. Use an app-private directory rooted under Context.filesDir. Never install to shared/external storage.
2. Download to a temporary file with size limits, timeouts, cancellation and progress reporting.
3. Verify the pinned SHA-256 before opening the archive.
4. Extract to a new staging prefix. Reject absolute paths, .. traversal, unexpected path separators, duplicate/conflicting entries, symlink escapes and excessive entry counts/uncompressed sizes.
5. Handle the upstream SYMLINKS.txt format explicitly. Validate both link target and destination; reject destinations outside staging and unsafe absolute targets. Preserve only links required by the bootstrap contract.
6. Apply executable permissions to the expected bootstrap paths only. Do not assume permissions alone make Android execution work.
7. Run a documented second-stage setup only if required by the exact generated bootstrap artifact; do not execute arbitrary archive scripts without verification and a known contract.
8. Validate required files and links, expected package identity/prefix and architecture.
9. Promote staging to the final prefix only after validation, using same-filesystem atomic rename where supported. Keep the previous known-good prefix until promotion succeeds when implementing repair/upgrade.
10. Persist schema-versioned install metadata atomically: bootstrap version, ABI, digest, prefix, install timestamp and health-check result.
11. Make install/retry idempotent. Handle process death, low storage, cancellation, malformed archive and failed promotion.
12. Never mark the runtime ready until a real command succeeds through the production execution adapter.

**Exit gate:** unit tests for path traversal, malformed symlink data, digest mismatch, truncated download, disk-full/interrupt recovery, retry and failed promotion.

## 6. Phase 3 — Android 10+ native execution and W^X

### 6.1 Understand the restriction

Android 10 (API 29) changed execution behavior for apps targeting API 29+: executing files from the writable app home directory is a W^X violation, and the platform restricts direct execution from that location. Executable code is expected to be packaged in the APK or handled through a compatible, reviewed execution design. Execute-only system mappings are a separate hardening feature and must not be confused with the writable-home restriction.

**Do not treat these as acceptable fixes:** disabling SELinux, root-only workarounds, globally weakening process protections, changing app target SDK as a supposed permanent solution, or blindly applying chmod +x/mprotect to bypass the platform.

### 6.2 Execution adapter investigation

1. Study the pinned Termux implementation for its supported native execution path (including the relevant linker/exec wrapper behavior) and the exact Android API/ABI conditions.
2. Trace the full path from app code to shell to a child executable: executable path, dynamic linker, ELF interpreter, shared-library resolution, environment and exit status.
3. Reproduce this path in a small native smoke-test app for every supported API/ABI before integrating it into the editor.
4. Prefer a supported upstream-compatible design with documented security assumptions. If the required execution path is not compatible with the current target SDK/device policy, stop and document the blocker rather than quietly shipping a security bypass.
5. Keep native libraries shipped in the APK under normal Android packaging/loading rules. Do not unpack arbitrary native libraries and use writable-file dlopen as a workaround for W^X.
6. Use argument arrays for direct command execution where possible; avoid building shell commands by concatenating user input. When a shell is needed, pass a script/command as a distinct argument and keep the working directory explicit.
7. Build an environment per command/session: PREFIX, HOME, TMPDIR, PATH, required loader variables, locale and working directory. Do not leak unrelated host environment variables.
8. Capture stdout/stderr separately, propagate exit code and signal, support cancellation/timeouts, close pipes and reap child processes.
9. Bound concurrent processes and output buffers. Avoid loading unbounded compiler output into memory.
10. Run the shell smoke test under the same execution adapter used by Compile & Run; a separate test path is not sufficient.

**Exit gate:** on-device tests prove sh -c 'printf runtime-ok' and a child process run successfully with expected output and exit code on each supported API/ABI. No root, external Termux, SELinux changes or unsupported policy relaxation.

## 7. Phase 4 — package management and toolchains

1. Define package profiles and estimated storage:
   - **Base:** shell, basic utilities, package manager and certificate/time configuration required for downloads.
   - **C/C++:** Clang/Clang++, lld, make or a compatible build tool, pkg-config and clangd as needed.
   - **Python:** interpreter and pip only if supported by the selected repository/package policy.
   - **Node.js:** Node.js and npm/corepack only if needed.
   - **Editor support:** language servers and Tree-sitter runtime/grammars appropriate to supported languages.
2. Install packages through the configured package manager and a repository built for the custom package identity. Do not silently reuse a repository whose packages have incompatible prefixes.
3. Add a package catalog with package ID, version constraints, dependencies, ABI compatibility, download size, installed size and license metadata.
4. Use a single package-operation lock. Prevent concurrent install/update/remove operations and show progress/cancel state.
5. Handle partial downloads, package-manager locks, disk space and package database recovery.
6. Make package updates explicit and recoverable. Do not update the entire runtime in the background without user-visible state.
7. Treat downloaded packages and build scripts as executable supply-chain inputs: HTTPS, repository signature verification, pinned release metadata, and no disabled signature checks.
8. Do not promise a package is available until it exists in the configured custom repository for the target ABI.

**Exit gate:** package install/remove/retry tests, repository signature verification and reproducible package profile manifests.

## 8. Phase 5 — process sessions, terminal and build/run

1. Implement a session manager with session IDs, working directory, environment snapshot, stdin/stdout/stderr streams, exit status and cancellation.
2. Support interactive terminal sessions separately from one-shot build/run commands.
3. Avoid holding an Activity or Compose state object from a long-running process. Use a lifecycle-aware repository/service abstraction and reconnectable session state.
4. On process death, mark sessions interrupted and provide an explicit restart path; do not report them as still running.
5. For C/C++, compile into a project-local build directory, surface diagnostics with file/line/column, then execute the produced binary only after the native execution gate has passed.
6. For Python/Node.js, invoke the configured interpreter/runtime with a file path or explicit arguments. Show missing-tool state and a package-install action rather than crashing.
7. Use process groups/session tracking where supported so cancellation terminates descendants, not just the top-level shell.
8. Cap logs and keep build output streaming. Separate Build, Problems and Terminal models.
9. Treat project source as user content. Avoid silently executing project tasks on open; require an explicit Run/Build action.

**Exit gate:** build/run integration tests with success, compile error, runtime error, cancellation, timeout and process-tree cleanup.

## 9. Phase 6 — LSP and Tree-sitter

1. Start LSP servers as managed child processes using the same runtime adapter and per-project working directory.
2. Implement Language Server Protocol framing correctly: Content-Length: N followed by exactly N UTF-8 bytes; do not parse LSP as line-delimited JSON.
3. Support initialize/initialized/shutdown/exit, document open/change/close, diagnostics, cancellation and server stderr logging.
4. Map project URIs to the IDE filesystem consistently and handle file paths with spaces/non-ASCII characters.
5. Add one server at a time (for example clangd first, then Python and TypeScript/JavaScript) and test initialization plus a real diagnostic response.
6. Keep Tree-sitter parsing separate from LSP semantics. Pin parser/grammar versions and use an ABI-compatible native library or a maintained runtime with explicit Android support.
7. Bound parser work and debounce edits; parsing must not block the UI thread.
8. Allow optional tooling to be installed/removed without invalidating the base shell.

**Exit gate:** protocol-level tests and on-device smoke tests for server startup, diagnostics, document updates, shutdown and missing-server recovery.

## 10. Phase 7 — app flow and UX

1. Welcome screen checks runtime state.
2. If missing or unhealthy, show a focused bootstrap screen with progress, current phase, cancel and retry.
3. On a successful real shell smoke test, persist runtime state and transition to Ready.
4. Ready routes to Projects. Allow creating a project or opening a single file; the editor should not be blocked by optional LSP/compiler packages.
5. Expose tool availability and install actions contextually. Do not make users choose a Linux distribution: this is a fixed Code Studio runtime, not a PRoot distro manager.
6. Back navigation must behave normally. Do not launch an external terminal app.
7. Keep UI strings, runtime configuration and package metadata separate from business logic.
8. Do not show Installed or Ready based only on downloaded bytes or an existing prefix directory.

**Exit gate:** navigation tests for fresh install, existing healthy install, failed install, retry, back press, offline mode and missing optional packages.

## 11. Phase 8 — security, reliability and CI

- Verify SHA-256 and repository signatures before installing executable artifacts.
- Keep bootstrap and package URLs/version metadata reviewable and immutable per release.
- Test archive traversal, malicious symlink entries, oversized archives and truncated content.
- Use least-privilege Android permissions; do not request storage permission for app-private runtime storage.
- Never expose the runtime through an exported service or implicit broadcast.
- Redact tokens/credentials from command logs and package-manager output.
- Add unit tests for installer, environment builder, state machine, process results and package catalog.
- Add instrumentation tests for install and smoke tests on API 29 and a current Android API.
- Run a device matrix for each claimed ABI, including low-memory and low-storage scenarios.
- CI should build the Android app, run unit tests, validate bootstrap manifest/checksum metadata, and reject unsupported ABI claims.
- Maintain license notices, source revisions, SBOM/package inventory and reproducible build instructions.

## 12. Suggested delivery order and definition of done

| Milestone | Deliverable | Definition of done |
|---|---|---|
| M0 | Repo audit and CI baseline | No unresolved legacy integration references; baseline build status recorded |
| M1 | Custom bootstrap pipeline | Artifact generated for declared ABI(s), pinned digest and provenance |
| M2 | Secure installer | Staging, verification, safe symlinks, retry/repair and tests |
| M3 | Native execution adapter | Shell and child-process smoke tests pass on API/ABI matrix |
| M4 | Welcome/bootstrap/ready flow | UI only reports ready after real execution smoke test |
| M5 | Package manager profiles | Base and optional tools install from the custom package source |
| M6 | Build/run + terminal | C/C++ and scripting smoke projects run end-to-end |
| M7 | LSP + Tree-sitter | At least one real server and parser validated on-device |
| M8 | Release hardening | Security review, licenses, CI and supported-device matrix documented |

**Production-ready means all applicable exit gates pass.** A documentation commit, a green Kotlin compile, a bootstrap download, or a successful extraction alone does not establish that an embedded Linux runtime works on Android.

## 13. Upstream references

- Termux app: https://github.com/termux/termux-app
- Termux bootstrap installer: https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/TermuxInstaller.java
- Termux package maintainer and bootstrap build instructions: https://github.com/termux/termux-packages/wiki/For-maintainers
- Android 10 behavior changes and app-home execution restriction: https://developer.android.com/about/versions/10/behavior-changes-10
- Android security best practices: https://developer.android.com/privacy-and-security/security-best-practices

## Current implementation status

This plan is committed as an implementation specification. It does **not** claim that the custom bootstrap artifact, W^X-compatible native execution path, package repository, on-device tests, or working C/C++/Node.js/Python/LSP support have already been implemented. Each remains gated by the milestones above.
