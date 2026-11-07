# ADR 0001: Preserve Android application identity during rebranding

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The repository and product are being rebranded as KCode. Existing users may already have the app installed under the current Android application ID and package namespace.

## Decision

Keep the application ID and Kotlin namespace as `com.techwithkaushik.cppide` during repository and product rebranding. Change user-visible branding independently.

## Alternatives considered

- Change the application ID immediately to a KCode-specific value.
- Keep the existing identity until a separately planned migration is possible.

The immediate ID change was rejected because Android would treat the new ID as a different application, preventing normal in-place upgrades.

## Consequences

- Existing installations retain the possibility of upgrading in place.
- Internal package names may remain historically named during the transition.
- A future application ID migration requires a separate release, data migration, and user communication plan.
