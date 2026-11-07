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
include(":app", ":core:contracts", ":core:ipc", ":core:jni", ":core:logging", ":editor", ":terminal")
include(":runtime:toolchains", ":runtime:linux")
include(":workspace:api", ":workspace:impl", ":workspace:ui")
include(":lsp:api")
include(":ui:design-system")
