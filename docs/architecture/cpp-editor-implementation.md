# C/C++ editor implementation plan

## Scope

The first fully integrated language is C/C++. Do not install or activate Python, Java, Rust, Node.js or other language toolchains as part of this phase. Keep the language catalog extensible so later languages can own independent settings and tools.

## Runtime ownership

The IDE's integrated terminal, compiler, formatter and language server must use the same selected runtime and workspace. For the app-private Ubuntu/Debian/Arch rootfs, invoke the package manager inside that rootfs through the validated PRoot runtime launcher. Do not silently route these commands to Android's host shell or a separately installed Termux app.

A package install is explicit and scoped to the selected capability. Opening a source file must never trigger downloads. Before offering an install command, inspect the selected distro and package manager. After installation, resolve the actual executable path and verify its version/exit status.

## C/C++ tool profile

| Role | Ubuntu / Debian package | Arch Linux package | Executable |
|---|---|---|---|
| Compiler | clang | clang | clang, clang++ |
| Language server | clangd | clang | clangd |
| Formatter | clang-format | clang | clang-format |
| Linter | clang-tidy | clang | clang-tidy |
| Debugger | lldb | lldb | lldb |

Ubuntu and Debian split these capabilities into packages. Arch provides clangd, clang-format and clang-tidy from the clang package. The package catalog is metadata only; runtime package availability must still be checked before installation.

## Editor and language-service behavior

- Keep Sora as the editing surface; do not replace it with a Compose TextField.
- Detect C and C++ independently for LSP document identifiers: `c` for .c and `cpp` for C++ sources. A plain .h header defaults to `cpp`; workspace compile commands must be allowed to override ambiguous header language.
- Use clangd for completion, diagnostics, hover, go-to-definition, references, rename and document symbols. Do not simulate these features in UI state.
- Keep syntax highlighting responsive and local to the editor. clangd diagnostics/completion are semantic services and must not block keystrokes.
- Prefer `compile_commands.json` when present. Otherwise derive a conservative compile configuration from workspace settings and show when include paths/defines are incomplete.
- Map diagnostics to file URI + zero-based LSP ranges, then to Sora document positions. Handle UTF-16 LSP character offsets correctly.
- Debounce didChange notifications, preserve document version ordering, and send didOpen/didChange/didSave/didClose at the correct lifecycle points.
- Start one reusable clangd process per workspace. Frame JSON-RPC using `Content-Length: N\r\n\r\n` and exactly N UTF-8 bytes. Drain stderr separately, bound message sizes, and stop the process on workspace/runtime shutdown.

## Language-specific settings

C/C++ settings must be independent from global editor appearance settings:

- C standard (default C17) and C++ standard (default C++20).
- Compiler executable overrides for clang and clang++.
- clangd executable override and additional clangd arguments.
- Include directories and preprocessor definitions.
- Warning flags and additional compiler arguments.
- clang-format style (workspace `.clang-format` takes precedence).
- Format-on-save and diagnostics toggles.
- Workspace compile database path.

Resolution order: explicit workspace configuration, then user language settings, then detected runtime defaults. Do not apply C++ settings to C translation units.

## Current implementation status

- [x] C/C++ package roles are cataloged separately for Ubuntu, Debian and Arch Linux.
- [x] C and C++ resolve to distinct clangd document language identifiers (`c` and `cpp`).
- [x] Sora TextMate support is added with local C and C++ grammars, matching indentation configuration and four editor themes.
- [ ] Runtime package installation, executable probing and compiler path resolution are not yet connected to the embedded rootfs.
- [ ] clangd process launch, JSON-RPC transport, editor diagnostics/completion and workspace lifecycle are not yet connected.
- [ ] Build/Run, format-on-save and C/C++ settings persistence still require integration and tests.

## Language Settings screen behavior

- A language dropdown is the entry point; C/C++ is the first enabled integration. Other catalog entries explicitly report that their integration is not implemented yet.
- Refresh rechecks the app-private embedded runtime, scans distro PATH and conventional manual toolchain locations, then executes each discovered tool's `--version` through the embedded shell/system-linker path. File existence alone is not a ready state.
- Required C/C++ tools are `clang`, `clang++` and `clangd`. The C/C++ settings form is hidden until all three pass execution probes.
- Optional `clang-format`, `clang-tidy` and `lldb` are displayed independently.
- Per-executable absolute Linux guest paths can override discovery. Saved overrides are revalidated on Refresh; invalid overrides are not silently replaced.
- The workspace is inspected for `.clangd`, `.clang-format`, `compile_commands.json`, `CMakeLists.txt`, `Makefile` and `meson.build`.
- C/C++ standards, compiler flags, clangd arguments, include paths and preprocessor definitions are persisted separately from editor appearance settings.

The embedded Termux terminal install command shown when tools are missing is `pkg update && pkg install clang lldb`. Refresh must verify real executables after installation; entering a command is not success.

## Required validation before calling C/C++ support complete

1. Install clang and clangd in each supported distro through the integrated terminal; verify versions from the same runtime used by Build/Run.
2. Compile and run a valid C17 program and a valid C++20 program.
3. Show compiler diagnostics for invalid C and C++ programs with correct file/line/column.
4. Open a project with `compile_commands.json`; verify include paths and defines.
5. Verify clangd initialize/initialized handshake, completion, hover and go-to-definition on a real source file.
6. Verify file paths containing spaces and non-ASCII characters.
7. Verify cancellation, process crash, runtime shutdown and workspace switching release clangd resources.
8. Run unit tests for language detection, settings persistence, JSON-RPC framing, UTF-16 position mapping and diagnostics mapping.

## Known integration gate

The repository currently has an AIDL LSP service contract, but that contract alone does not prove that a real persistent clangd process is connected to the editor. The implementation must not show a green/ready LSP status until the real process handshake and at least one real diagnostic/completion round trip pass. A finite one-shot command API is suitable for package/version probes and compile commands, but it is not a substitute for a persistent bidirectional LSP transport.
