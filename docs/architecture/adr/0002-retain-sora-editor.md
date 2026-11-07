# ADR 0002: Retain Sora as the current editor engine

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

KCode currently integrates Sora Editor. Replacing the editor engine while editor, completion, diagnostics, and LSP integration are still being stabilized would combine infrastructure migration with user-visible behavior changes.

## Decision

Retain Sora as the current editing engine. Define KCode-owned document and editor contracts around it, and keep Compose presentation behind adapters. Reconsider replacement only with benchmark data, feature-parity criteria, and a migration plan.

## Alternatives considered

- Replace Sora immediately with a custom editor engine.
- Keep Sora coupled directly to all feature and UI logic.
- Retain Sora behind stable KCode-owned boundaries.

The third option is selected.

## Consequences

- Existing editor functionality can be improved without an engine rewrite.
- KCode contracts reduce long-term coupling to Sora APIs.
- Any future engine replacement must pass editing, performance, accessibility, and integration acceptance tests.
