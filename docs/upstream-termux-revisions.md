# Upstream Termux Revision Lock

Reviewed on: 2026-10-28

This project consumes upstream Termux components. Keep source revisions explicit and update them only after reviewing source, build compatibility, licenses, and runtime behavior.

| Component | Repository | Revision | Use |
|---|---|---|---|
| Published terminal libraries | https://github.com/termux/termux-app | `v0.119.0-beta.3` | JitPack aggregate dependency in `:feature:terminal`; terminal emulator is supplied transitively by terminal-view |
| Termux app source audit | https://github.com/termux/termux-app | `8629e632fcb95da272221be327db653fb24befe9` | Verified upstream default-branch head on 2026-10-28; source audit reference, not the dependency coordinate |
| Bootstrap and package build system | https://github.com/termux/termux-packages | `21700256be43a0c8aae38bacecdac87c5ded4cc6` | Custom app-identity bootstrap build workflow; verified upstream default-branch head on 2026-10-28 |

## Revision policy

- The terminal library uses the published Termux beta because a raw commit-hash JitPack coordinate did not resolve in CI. The release page currently documents `v0.119.0-beta.3` as a JitPack coordinate. Re-check dependency resolution in CI before treating it as integrated.
- Termux stable release and upstream development source are different tracks. Do not label a beta dependency as stable.
- The Termux packages repository advances frequently. The workflow pin above was the default-branch head checked on 2026-10-28; refresh it and review bootstrap release changes before a release.
- The custom bootstrap must be rebuilt for `com.techwithkaushik.cppide`; never substitute the official `com.termux` bootstrap without proving prefix compatibility.
- The custom bootstrap workflow is manual. A source pin does not mean a custom bootstrap has been built or validated.
- The integration is not complete until Gradle resolves the published library, the app builds, the bootstrap is generated for the custom prefix, and physical-device shell/process smoke tests pass.
- Preserve upstream license/copyright files and review transitive dependencies before distribution.

## Integration boundary

The published Termux terminal library is a dependency only; the app's terminal UI has not yet been wired to a managed shell session. The upstream `termux-shared` and app modules have not yet been integrated because they carry lifecycle and platform assumptions that need deliberate adaptation. Bootstrap installation, environment construction, and process execution are not considered complete until corresponding upstream code is integrated and real device smoke tests pass.
