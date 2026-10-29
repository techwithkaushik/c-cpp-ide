# Embedded environment migration checklist

This checklist is the implementation sequence for removing the current external Termux-app dependency.

## Phase 0 — preserve a working baseline

- [x] Keep the existing Termux API path isolated in `:integrations:termux` while the replacement is developed.
- [x] Add an explicit architecture decision and acceptance criteria.
- [ ] Capture baseline APK size, build output and current CI results.

## Phase 1 — runtime and package provenance

- [ ] Select the exact bootstrap distribution and verify its license/provenance.
- [ ] Confirm that its packages can be relocated to an app-owned prefix; do not assume Termux app binaries are relocatable.
- [ ] Build or obtain Android-native bootstrap artifacts for ARMv7 and ARM64 first.
- [ ] Produce a signed/versioned manifest with SHA-256 hashes, archive sizes and package versions.
- [ ] Include required third-party notices and a repeatable artifact-generation workflow.

Exit gate: no placeholder archives and no unverified compiler binaries.

## Phase 2 — installer and environment provider

- [ ] Add ABI/API compatibility checks and first-run setup states.
- [ ] Download and verify artifacts before extraction.
- [ ] Extract into staging with strict archive-entry validation.
- [ ] Configure app-owned PREFIX/HOME/PATH/TMPDIR/LD_LIBRARY_PATH.
- [ ] Run shell, clang version, C17 and C++20 smoke tests.
- [ ] Activate atomically; preserve the previous environment on failure.
- [ ] Add unit tests for bad digests, traversal, truncated downloads, cancellation and retry.

Exit gate: installer only returns READY after both smoke programs execute successfully.

## Phase 3 — in-app terminal

- [ ] Choose a terminal emulator component compatible with the app's Android/Kotlin stack and license.
- [ ] Start the embedded shell with the environment provider, not Termux RUN_COMMAND.
- [ ] Implement input, output, resize, interrupt and process/session lifecycle.
- [ ] Ensure closing the terminal does not kill a compile job unexpectedly, and avoid orphan processes.

Exit gate: shell works with Termux absent on supported devices.

## Phase 4 — editor and build integration

- [ ] Add an IDE-owned workspace and source-file creation/open/save flow.
- [ ] Route Compile & Run to the embedded build service.
- [ ] Use C17/C++20, argument arrays, bounded output and timeouts.
- [ ] Show compiler diagnostics separately from program output.
- [ ] Remove Termux-specific paths, connection status and RUN_COMMAND gating from the primary UI.
- [ ] Keep a clearly labeled legacy integration only until replacement acceptance criteria pass.

Exit gate: both smoke tests and user-created projects compile and run without Termux installed.

## Phase 5 — release validation

- [ ] CI builds APK and tests all pure Kotlin installer/command logic.
- [ ] Test fresh install, interrupted setup, upgrade, low storage and offline build.
- [ ] Test Android 10/API 29 ARMv7 and ARM64 physical devices.
- [ ] Verify package notices, bootstrap hashes and reproducible release notes.
- [ ] Measure initial APK size, download size, installed runtime size and update size.

CI passing is necessary but not sufficient; real device validation is mandatory.
