# Vendored Termux source modules

Upstream project: https://github.com/termux/termux-app
Pinned upstream revision: 8629e632fcb95da272221be327db653fb24befe9

Local Gradle modules:
- terminal-view
- terminal-emulator
- termux-shared

These modules are compiled from this repository's source tree; the IDE does not depend on Termux Maven/JitPack artifacts. Google Maven and Maven Central remain configured for ordinary Android/Java transitive libraries. The Termux runtime/bootstrap is a separate concern and must be provisioned for package com.techwithkaushik.cppide.
