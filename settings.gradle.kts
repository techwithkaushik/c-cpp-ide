pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CppIDE"

include(":app")
include(":services:daemon")
include(":core:aidl")
include(":core:jni")
include(":core:lsp")
include(":core:designsystem")
include(":feature:editor")
include(":feature:terminal")
include(":feature:explorer")
