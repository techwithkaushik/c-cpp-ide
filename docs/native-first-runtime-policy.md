# Native-First Runtime Policy

**Status:** Accepted architecture constraint  
**Applies to:** Code Studio embedded Termux runtime (`architecture/termux-integration`)

## Goal

Match the performance and compatibility characteristics of Termux as closely as possible. Keep command execution, process management, terminal I/O and toolchains native. Kotlin/Java should be a thin Android integration layer, not a second shell/runtime implementation.

## Language and responsibility split

| Area | Preferred implementation | Notes |
|---|---|---|
| Android screens, lifecycle, permissions, navigation | Kotlin + Jetpack Compose | Keep UI responsive; no filesystem extraction or process waits on the main thread. |
| Runtime core, command/session model | C or C++ where it improves control and reuse | Define a small stable C ABI for Kotlin/JNI boundary. |
| Process launch, pipes, signals, PTY, terminal I/O | Native C/C++ (or upstream Termux-compatible native implementation) | Must be validated on Android 10+ for every supported ABI/API. |
| Terminal emulator/rendering | Native core where practical; Android surface/input integration may remain Kotlin | Reuse a proven terminal implementation rather than writing a slow Java terminal parser. |
| Bootstrap extraction, SHA-256, metadata | Native implementation preferred for high-volume work, or existing Kotlin implementation as a temporary bootstrap stage | Security correctness matters more than language purity; no duplicated implementations. |
| Compiler, linker, shell, coreutils, package manager | Upstream Termux native binaries/packages | Do not replace with Java/Kotlin emulations. |
| Clang, Python, Node.js, LSPs, Tree-sitter | Native binaries/libraries from the custom-prefix package ecosystem | Optional profiles; don't inflate base install unnecessarily. |
| Android-only lifecycle, permission prompts, accessibility, Compose state | Kotlin | These are platform responsibilities and are not performance-critical command paths. |

Native-first does not mean rewriting everything in C/C++ just to reduce Kotlin line count. Keep Kotlin where Android APIs and lifecycle integration are materially better; keep hot paths and process/runtime semantics native.

## Upstream reuse is mandatory (do not reimplement Termux)

The project must reuse the existing upstream Termux ecosystem and implementation wherever technically compatible. This is not a greenfield shell or Linux distribution project.

- Build the bootstrap and packages using the pinned `termux/termux-packages` build system; consume upstream Bash, core utilities, apt/dpkg, compilers, runtimes and language-server packages.
- Reuse or adapt the relevant upstream Termux app/shared-library code for installation, environment construction, execution compatibility and terminal behavior after auditing source, API compatibility and licenses.
- Do not write a replacement shell, package manager, libc, linker, compiler toolchain or terminal parser when a suitable upstream component exists.
- Keep only the IDE-specific Android UI, project/editor workflow, runtime coordinator, and the smallest compatibility adapter that upstream integration actually requires.
- A copied upstream component must retain its required license and notices. Pin the upstream revision and record local patches; avoid copying large modules until the integration contract and dependencies are understood.
- The custom app still owns its private prefix, home, package database and lifecycle. Reusing Termux modules does not mean accessing another installed Termux app's private files or depending on its exported command API.
- Do not assume the official `com.termux` bootstrap or repository artifacts work under `com.techwithkaushik.cppide`; build and validate custom-prefix artifacts against the exact app paths and ABI.

## Runtime architecture

- Build the Termux-compatible bootstrap and packages for the app's package identity, ABI and prefix.
- Keep the prefix under app-private storage. The runtime's compiled prefix and runtime layout must be exactly compatible.
- Ship the required native adapter libraries through normal APK native-library packaging and load them with Android-supported mechanisms.
- Use a narrow JNI boundary with explicit argument arrays, environment entries, working directory, stdin/stdout/stderr, exit status, cancellation and bounded output.
- Do not implement shell parsing by concatenating user strings. If a shell is requested, pass the script as a separate argument to the selected shell.
- No external Termux app, RUN_COMMAND, root, SELinux changes, or unsupported platform-security bypasses.
- Do not assume that a binary extracted to app-writable storage is executable because chmod +x succeeded. Android 10+ W^X/linker compatibility is a blocking gate.
- Use upstream Termux-compatible execution behavior only after reviewing the pinned source, license, target SDK and API/ABI constraints. If the native execution path is not supported, document the blocker rather than silently weakening security.

## Performance acceptance gates

Record baseline measurements on physical devices, with the same ABI/API and release build:

1. Cold start to first prompt.
2. Warm start to first prompt.
3. `printf runtime-ok` end-to-end latency.
4. Spawn/exit latency for a child process.
5. PTY input-to-visible-output latency.
6. Sustained stdout throughput and memory use with bounded buffering.
7. C/C++ compile time and generated binary execution.
8. CPU and memory while idle, and during a long-running build.

Compare against a matched Termux environment on the same device when possible. Report median and p95 over repeated runs; don't publish an unmeasured native speed claim. Investigate material regressions before shipping.

## Implementation order

1. Keep bootstrap installer isolated and secure.
2. Pin upstream Termux source revision and document license/provenance.
3. Implement a native runtime adapter behind a stable interface.
4. Add native unit tests for argument/environment handling, stream framing, cancellation and child cleanup.
5. Build an Android smoke-test app for every supported API/ABI and prove shell + child process execution through the production adapter.
6. Connect runtime state to Welcome → Bootstrap → Smoke Test → Ready → Projects.
7. Add package profiles (base, C/C++, Python, Node.js, language servers) only after the base runtime passes.
8. Add terminal, editor Build/Run and LSP integrations through the same native session API.

## Explicitly not accepted

- Java/Kotlin ProcessBuilder as the final hot-path process engine.
- Executing extracted native binaries without an Android 10+ compatibility test.
- Copying official com.termux artifacts and assuming custom package-prefix compatibility.
- Shipping Clang, Python, Node.js and all language servers in the base APK.
- Performance claims based only on implementation language or a successful archive extraction.
