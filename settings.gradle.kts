pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "CppIDE"
include(":app", ":services:daemon", ":core:aidl", ":core:jni", ":core:lsp", ":core:designsystem", ":feature:editor", ":feature:terminal", ":feature:explorer")
include(":integrations:toolchain", ":integrations:termux-runtime")
include(":integrations:termux-upstream:terminal-emulator")
include(":integrations:termux-upstream:terminal-view")
include(":integrations:termux-upstream:termux-shared")
