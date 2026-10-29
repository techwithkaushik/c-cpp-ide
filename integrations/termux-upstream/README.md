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

There are no Termux Maven or JitPack artifacts in the IDE's Gradle dependencies. Google Maven and Maven Central remain configured for ordinary Android and Java libraries used by these modules.

## Runtime status

Vendoring the terminal UI/emulator code does not by itself install a usable Linux userland. The app-private bootstrap must still be provisioned for package `com.techwithkaushik.cppide`, and the terminal process must be validated on-device. The existing Termux installation is not modified or required.
