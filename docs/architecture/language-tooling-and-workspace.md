# File-aware language services and toolchain resolution

## Product scope

Code Studio is a multi-language, VS Code-inspired IDE, not a C/C++-only application. C/C++ remains one supported language family; it must not define the app name, default workspace, project creation flow, or generic build panel copy.

## Required runtime lifecycle

- First-run setup installs and validates the selected Linux userspace plus the Android-compatible execution runtime. A distro is Active only after a real shell smoke test exits successfully.
- Opening a project or ordinary text file must not download a compiler automatically.
- Opening a source file detects the language from the filename and workspace configuration. If the matching language server, formatter, linter, or runtime is missing, show the specific missing capability and offer an explicit install action.
- Installing one language's tools must not install unrelated language toolchains.
- Reuse already installed tools. A tool should be downloaded/installed once per selected distro unless the user requests an update.
- LSP diagnostics, completion, hover, formatting, and linting must be wired through the language server/tool process, not simulated UI state.

## File-to-tool mapping

The initial catalog is in `app/src/main/java/com/techwithkaushik/cppide/LanguageToolingCatalog.kt`. It identifies common file extensions for C/C++, Python, JavaScript, TypeScript, Rust, Java, Kotlin, HTML, CSS, JSON, YAML, shell, and Markdown. The catalog is metadata only: the installer must check the chosen distro's package manager and actual package availability before presenting a command as supported.

Text, Markdown, JSON, and unknown extensions must remain editable even when no compiler or language server is installed. Makefile and CMake files may help identify a C/C++ build workspace, but they must not force the entire IDE into C/C++ mode.

## Tool resolution priority

For each tool role (runtime, compiler, language server, formatter, linter, debugger), resolve in this order:

1. User-configured absolute executable path, after validating that the file exists and is executable.
2. Executable installed in the selected distro's rootfs.
3. Install candidate from the selected distro's package manager, only after user consent.
4. Report unavailable if no compatible package/artifact exists.

The `ToolPathPreferences` class persists user-provided absolute executable paths in app preferences. The settings UI must expose separate fields for each language/tool role, a Browse action where possible, a Validate action, and a Clear action. The resolved path must be supplied to the process launcher/LSP client; saving a path alone does not make the tool active.

Pre-downloaded tools can be located outside the rootfs. Before executing them, validate architecture, executable permission, runtime dependencies, and compatibility with Android/Linux userspace. Do not assume an arbitrary desktop Linux binary will run on Android.

## Workspace and diagnostics integration

1. Replace the sample in-memory file list with a filesystem-backed workspace abstraction.
2. On file open, detect language and load actual file bytes.
3. Resolve workspace settings (e.g. project manifest, compile database, config files) before choosing server arguments.
4. Resolve configured tool paths; probe installed tools; then offer targeted installation only for missing roles.
5. Start or reuse a long-lived LSP process per workspace/language and route JSON-RPC messages over stdio.
6. Convert LSP diagnostics into Problems entries and map file URIs/ranges to editor locations.
7. Run formatters and linters as explicit file/workspace actions; show progress, exit code, stdout/stderr, and errors.
8. Compile/run only when the language has an execution/build provider and required tools are validated.

## Setup completion gate

The setup screen must not transition to the Projects/Editor screen with a success state until:
- rootfs identity matches the selected distro;
- the Android-compatible PRoot launcher and dependencies are present;
- the launcher starts the selected rootfs shell successfully;
- a smoke-test command returns exit code 0 with expected output;
- the runtime's rootfs path, HOME, TMPDIR, PATH, and working directory are validated.

If any gate fails, preserve downloaded/extracted files, show a recoverable error, and do not display Linux as Active.

## External tool paths

A user may point to already downloaded executables. Persist absolute paths per language and role, validate them before use, and allow the user to select them without re-downloading. For tools located inside the Linux rootfs, keep paths distro-relative in workspace configuration where possible; do not persist temporary staging paths.

## Validation

- Unit-test extension detection, dotfiles, unknown files, and language IDs.
- Unit-test path persistence, clearing, invalid path rejection, and missing/non-executable files.
- Test install-on-demand without network activity for already-present tools.
- Test user overrides take precedence over rootfs tools.
- Test one language install never changes another language's installed state.
- Test LSP diagnostics and completion against actual source files in a physical Android runtime.
