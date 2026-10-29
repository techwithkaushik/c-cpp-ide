# C/C++ IDE UI flow and interaction contract

- **Status:** UI implementation specification
- **Date:** 2026-10-27
- **Reference:** AndroidPE's publicly documented feature set and screenshots, adapted to this standalone C/C++ IDE. This is a design reference, not a request to copy proprietary assets or unverified implementation details.

## Product flow

First launch and normal navigation must follow this order:

1. Welcome screen
2. Rootfs setup screen (first launch or when runtime is missing/broken)
3. Project list
4. Project workspace/editor

After initial setup, relaunch should normally skip Welcome and Setup and open the Project List, unless the user explicitly opens setup/runtime management or the active runtime needs repair.

## 1. Welcome screen

- App identity: C/C++ IDE, short value statement, clean dark/light Material 3 styling.
- Primary action: **Get Started**.
- Secondary links: **Runtime setup** (if needed), **Settings**, **About / Licenses**.
- Do not request broad storage permission on this screen.
- Avoid automatically probing or requiring the Termux app. The product is standalone.
- Restore the last valid route after process recreation where appropriate.

## 2. Setup screen

Show exactly two rootfs source cards:
1. **Download Ubuntu from Internet** — URL and trusted digest come from an app-controlled, versioned manifest; user does not enter a URL.
2. **Use Downloaded Rootfs from Storage** — use Android Storage Access Framework picker (ACTION_OPEN_DOCUMENT).

Both options converge on the same validation, staging, extraction, runtime preparation, and smoke-test pipeline. Display progress, cancel/retry, storage estimate, detected distribution/release/ABI, and actionable failure reasons. Do not report ready merely because extraction finished. Rootfs inspection, shell startup, and required C17/C++20 compile-and-run tests must pass.

See rootfs-setup-screen.md for security and activation requirements.

## 3. Project list

- Header: app title, search, settings, and **New Project** action.
- List recently opened projects first; allow create/open/import project.
- Each project row/card shows project name, root folder, language/type if known, last opened, and a simple status indicator.
- Provide empty state with **Create C Project**, **Create C++ Project**, and **Open Existing Folder**.
- Support refresh, rename/remove-from-list, and recent-project persistence. Removing from recents must not silently delete source files.
- Open a project to enter the workspace.

## 4. Project workspace / editor

Use a mobile-first IDE layout inspired by AndroidPE's documented editor, tabbed files, tree-view explorer, and embedded terminal. Do not attempt to cram desktop-sized panes permanently onto a phone.

### Top app bar / status actions

- Navigation drawer button and current project/file context.
- File tabs for open files; show unsaved marker and close action.
- Actions: **Undo**, **Redo**, **Save**, **Build**, **Run**, and overflow menu for search, replace, go-to-line, settings, and file actions.
- Disable or clearly mark actions that are not currently available (for example, Run before a successful build).
- Save must write to the actual project file and report errors; show unsaved state until persistence succeeds.
- Undo/redo must operate on editor history, not just be decorative buttons.

### Navigation drawer

At minimum include:
- **File Explorer** (required)
- **Terminal** (required)
- Project overview
- Build / Problems
- Recent files
- Settings
- Runtime setup / diagnostics

File Explorer opens a project-rooted tree, supports expand/collapse, and offers New File, New Folder, Rename, Delete (with confirmation), and Refresh. Tapping a supported text file opens it in the editor tab. Do not treat arbitrary binary files as editable text; show an appropriate preview or unsupported-file message. File operations must stay inside the selected project root unless the user explicitly imports/opens another location.

### Editor pane

- Syntax-aware editor with line numbers, current-line indication, horizontal scrolling for long code, and a sensible monospace font.
- Support C and C++ source/header extensions initially; preserve file encoding and line endings when saving where feasible.
- Provide editor selection, cursor movement, keyboard-friendly shortcuts, and soft-keyboard-aware resizing.
- Multiple open files use tabs and retain unsaved edits when switching tabs.
- Use a proper editor component rather than a basic multiline text field for the final code editor. The editor component must provide working selection, caret, undo/redo, and large-file handling.

### Bottom tool window / bottom model bar

A persistent, compact bottom tool-window bar provides:
- **Terminal**
- **Build**
- **Problems**
- **Output**

Tap **Terminal** to open a bottom sheet / resizable bottom panel containing a real terminal session inside the app-managed active rootfs. It must not be a fake terminal, host Android shell, or Termux UI. Keep terminal session alive when the panel is collapsed if the session/runtime permits it; provide explicit close/restart controls.

Tap **Build** or **Run** to open the same bottom panel on the Build/Output tab. Stream build output while the process is running; show progress/state, elapsed time, exit code, stdout/stderr, and actionable compiler diagnostics. Provide Stop/Cancel when supported. A failed compile must not attempt to run a stale executable as though it were the latest build.

The panel should support drag-to-resize, collapse/expand, and a close action. On short screens, keyboard visibility and panel height must not make the editor unusable. Avoid nested scrolling traps.

## 5. Build and run behavior

- Build uses the active project and selected source/target configuration, not hard-coded Downloads paths.
- C uses C17; C++ uses C++20 by default unless the project explicitly configures another standard.
- Keep each invocation bounded, cancelable where possible, and associated with a process/session ID.
- Stream output to the bottom panel with bounded memory. Preserve enough recent output for debugging; do not accumulate unlimited logs.
- Run only after successful compilation of the current source/configuration. Show the actual exit status and program output.
- Support stdin input in the terminal/run panel where appropriate.
- Build, run, terminal, and editor state must survive ordinary rotation/recomposition without launching duplicate jobs.

## 6. UI state and persistence

Persist:
- First-run/setup completion and active runtime metadata.
- Recent projects and last opened project.
- Open file tabs, selected tab, and editor cursor/scroll position when safe.
- Bottom panel selected tab and expanded/collapsed state.
- Theme and editor preferences.

Do not persist sensitive terminal input/output unnecessarily. Do not persist “runtime ready” if a subsequent integrity/startup check shows the runtime is broken.

## 7. Accessibility and device constraints

- Target Android 10 / API 29 compatibility as a minimum; do not depend on newer APIs without guarded fallbacks.
- Support ARMv7 and ARM64 runtime availability separately; do not show a toolchain as supported until that ABI has been tested.
- Use touch targets large enough for phone use, clear content descriptions, adequate contrast, and predictable Back behavior.
- Respect IME insets; keyboard should resize workspace and bottom panel rather than cover the active input.
- Keep project/file I/O off the main thread and make loading/build states explicit.

## 8. Suggested implementation phases

1. Navigation/state model and Welcome -> Setup -> Projects routing.
2. Project persistence and project list / create / open.
3. Project-scoped file explorer and real file open/save.
4. Editor component integration with tested undo/redo and tabs.
5. Bottom tool-window shell and output streaming.
6. Runtime-backed terminal session.
7. Build/run pipeline connected to active project and compiler.
8. UI tests for navigation, file save/reopen, undo/redo, terminal panel, build success/failure, cancellation, rotation, and no-runtime/error states.

## Acceptance checklist

- [ ] First launch shows Welcome, then Setup.
- [ ] Setup offers exactly the two documented rootfs choices.
- [ ] Successful setup routes to Projects; returning launches skip completed onboarding.
- [ ] Project list can create/open projects and remembers recents.
- [ ] Opening a project shows an editor workspace.
- [ ] Drawer always offers File Explorer and Terminal.
- [ ] Tapping a source file opens it in an editor tab.
- [ ] Edit, save, undo, redo, and unsaved-state indication work against real files.
- [ ] Build and Run are accessible from the top/status action bar.
- [ ] Terminal opens in the bottom panel and connects to a real rootfs shell.
- [ ] Build process and result stream in the bottom panel.
- [ ] No fake success states, no silent Termux/host-shell fallback, and no hard-coded demo paths.
- [ ] Tests cover failure, cancellation, large output, and screen/keyboard changes.
