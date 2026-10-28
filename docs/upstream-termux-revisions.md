# Upstream Termux Revision Lock

Reviewed on: 2026-10-28

This project consumes upstream Termux components. Keep source revisions explicit and update them only after reviewing source, build compatibility, licenses, and runtime behavior.

| Component | Repository | Revision | Use |
|---|---|---|---|
| Terminal emulator and terminal view | https://github.com/termux/termux-app | `8629e632fcb95da272221be327db653fb24befe9` | Gradle dependencies in `:feature:terminal`, pinned to the inspected upstream master commit |
| Bootstrap and package build system | https://github.com/termux/termux-packages | `21700256be43a0c8aae38bacecdac87c5ded4cc6` | Custom app-identity bootstrap build workflow |

## Latest-source policy

- "Latest" means the latest upstream source revision reviewed and pinned at the time of this file's update, not a floating `master-SNAPSHOT` dependency.
- The Termux packages repository advances frequently. Before a release, check the upstream default-branch head and latest bootstrap release; update the lock and workflow pin together after compatibility review.
- The Termux app's stable release and its default-branch source revision are distinct. The terminal libraries currently use the reviewed source commit above, rather than pretending that the older published `0.118.0` library version is the latest source.
- The custom bootstrap must be rebuilt for `com.techwithkaushik.cppide`; never substitute the official `com.termux` bootstrap without proving prefix compatibility.
- Current bootstrap workflow is manual. A pinned revision does not mean a custom bootstrap has been built or validated.
- CI must confirm that the pinned JitPack artifacts resolve and compile. If the upstream multi-module build cannot be consumed from JitPack at this revision, vendor the exact upstream `terminal-emulator` and `terminal-view` source modules (with notices and tests) instead of silently falling back to an older artifact.
- Preserve upstream license/copyright files and review transitive dependencies before distribution.

## Integration boundary

Only the terminal emulator and view dependencies are wired at this stage. The upstream `termux-shared` and `app` modules have not yet been imported because they have AndroidIDE/app-specific dependencies and lifecycle assumptions that must be adapted deliberately. Bootstrap installation, environment construction, and process execution are not considered complete until the corresponding upstream code is integrated and real device smoke tests pass.
