# ADR 0004: Separate terminal sessions from Linux runtime installation

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The terminal consumes processes and files from the app-managed Linux runtime, but terminal session lifecycle and distribution/rootfs installation have different state, failure, and recovery requirements.

## Decision

Keep terminal emulation, PTY sessions, input/output, resize, and process cleanup in the terminal subsystem. Keep PRoot, rootfs, bootstrap, environment, and toolchain installation in the runtime subsystem. Connect them through explicit runtime/process contracts.

## Alternatives considered

- Let the terminal feature own rootfs installation and repair.
- Let runtime own terminal UI and emulator state.
- Separate the subsystems behind explicit contracts.

The third option is selected.

## Consequences

- Runtime installation can be repaired without coupling it to terminal UI.
- Multiple terminal sessions can consume a validated runtime.
- Tests can independently cover runtime transactions and PTY/session lifecycle.
