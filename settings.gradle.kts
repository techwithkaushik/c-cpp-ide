pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "KCode"
include(":app", ":services:daemon", ":core:contracts", ":core:ipc", ":core:jni", ":core:logging", ":feature:editor", ":feature:terminal", ":feature:explorer")
include(":integrations:toolchain", ":integrations:linux-runtime")
include(":workspace:api", ":workspace:impl")
include(":lsp:api")
include(":ui:design-system")
