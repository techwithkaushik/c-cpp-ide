# ADR 0003: Keep a single Gradle monorepo and migrate incrementally

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

KCode already contains app, editor, terminal, LSP, native, runtime, and service modules. Renaming or moving all modules at once would create broad build and dependency churn before stable boundaries are established.

## Decision

Keep one Android Gradle monorepo and preserve existing module paths during the initial migration. Introduce contracts and new modules only when a boundary is justified by dependency direction, independent testing, or ownership.

## Alternatives considered

- Split each subsystem into separate repositories immediately.
- Rename every existing module to match the target directory tree in one change.
- Preserve the monorepo and migrate incrementally.

The incremental monorepo approach is selected.

## Consequences

- Existing CI and module paths remain stable while contracts are introduced.
- Module moves require explicit dependency and test validation.
- The target architecture is an ownership guide, not a requirement to create a Gradle module for every folder.
