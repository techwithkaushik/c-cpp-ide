pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "CppIDE"
include(":app", ":services:daemon", ":core:aidl", ":core:jni", ":core:lsp", ":core:designsystem", ":core:logging", ":feature:editor", ":feature:terminal", ":feature:explorer")
include(":integrations:toolchain", ":integrations:linux-runtime")
