# Vendored Termux source modules

The IDE compiles the following upstream source modules directly from this repository:

- `terminal-emulator`
- `terminal-view`
- `termux-shared`
- `termux-am-library` (required by `termux-shared`)

## Pinned upstream sources

- Termux app modules: [termux/termux-app](https://github.com/termux/termux-app), revision `8629e632fcb95da272221be327db653fb24befe9`.
- Termux AM library: [termux/termux-am-library](https://github.com/termux/termux-am-library), release tag `v2.0.0`.

Source and applicable license files are checked into this repository. Android CI verifies the checked-in files and compiles them without cloning upstream repositories or pushing generated source changes. `scripts/vendor-termux-source.sh` is a maintainer tool for refreshing the pinned copies; review any upstream change before committing it.

There are no Termux Maven or JitPack artifacts in the IDE's Gradle dependencies. Google Maven and Maven Central remain configured for ordinary Android and Java libraries used by these modules. `scripts/verify-termux-vendor.sh` checks that the required source, native build files, manifests, and licenses are present and fails CI if a Termux Maven/JitPack dependency is reintroduced.

## Runtime status

Vendoring the terminal UI/emulator code does not by itself install a usable Linux userland. The bootstrap must be built for package `com.techwithkaushik.cppide` and its prefix `/data/data/com.techwithkaushik.cppide/files/usr`, then validated on-device. Do **not** install the stock Termux bootstrap: its binaries and scripts can contain hard-coded `/data/data/com.termux/files/usr` paths. Rewriting only `SYMLINKS.txt` does not make those binaries relocatable, so the installer rejects that foreign absolute prefix. The existing Termux installation is not modified or required.

The remaining runtime work is to produce and pin a package-specific bootstrap per supported Android ABI, provide a trusted SHA-256 manifest, and verify actual shell launch on Android 10 and newer. CI compiles all four vendored Android library modules and runs `:integrations:termux-runtime:testDebugUnitTest`, including checksum, path traversal, app-specific symlink-prefix, foreign-prefix rejection, archive-size, and entry-count checks. A passing JVM test suite is not a substitute for the on-device shell smoke test.
