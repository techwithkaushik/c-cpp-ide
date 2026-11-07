plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.techwithkaushik.cppide"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.techwithkaushik.cppide"
        minSdk = 26
        targetSdk = 36
        // Derive release identity directly from the checked-out Git branch and commit.
        val gitBranch = providers.exec {
            commandLine("git", "branch", "--show-current")
            isIgnoreExitValue = false
        }.standardOutput.asText.get().trim()
            .ifBlank { System.getenv("GITHUB_HEAD_REF") ?: System.getenv("GITHUB_REF_NAME").orEmpty() }

        val distroName = gitBranch
            .substringAfter('/', gitBranch)
            .substringBefore("-runtime-ssh")
            .substringBefore('-')
            .ifBlank { error("Unable to derive distro name from Git branch: '$gitBranch'") }

        val shortCommitSha = providers.exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
            isIgnoreExitValue = false
        }.standardOutput.asText.get().trim()
            .also { if (it.isBlank()) error("Unable to determine the current Git commit SHA") }

        val isShallowCheckout = providers.exec {
            commandLine("git", "rev-parse", "--is-shallow-repository")
            isIgnoreExitValue = false
        }.standardOutput.asText.get().trim().toBoolean()

        check(!isShallowCheckout) {
            "Full Git history is required to calculate versionCode. " +
                "Fetch the branch with complete history (actions/checkout: fetch-depth: 0)."
        }

        val relatedBranchCommitCount = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = false
        }.standardOutput.asText.get().trim().toIntOrNull()
            ?.takeIf { it > 0 }
            ?: error("Unable to determine a positive current branch commit count")

        versionCode = relatedBranchCommitCount
        versionName = "$distroName-$shortCommitSha"
        logger.lifecycle(
            "App version: versionName=$versionName, versionCode=$versionCode, branch=$gitBranch"
        )
    }
    flavorDimensions += "bootstrapAbi"
    productFlavors {
        create("arm32") { dimension = "bootstrapAbi"; ndk { abiFilters += "armeabi-v7a" }; buildConfigField("String", "BOOTSTRAP_ABI", "\"armeabi-v7a\"") }
        create("arm64") { dimension = "bootstrapAbi"; ndk { abiFilters += "arm64-v8a" }; buildConfigField("String", "BOOTSTRAP_ABI", "\"arm64-v8a\"") }
        create("x86") { dimension = "bootstrapAbi"; ndk { abiFilters += "x86" }; buildConfigField("String", "BOOTSTRAP_ABI", "\"x86\"") }
        create("x64") { dimension = "bootstrapAbi"; ndk { abiFilters += "x86_64" }; buildConfigField("String", "BOOTSTRAP_ABI", "\"x86_64\"") }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        create("release") {
            val signingStore = System.getenv("SIGN_KEY_STORE") ?: error("SIGN_KEY_STORE must point to the restored repository keystore")
            val signingAlias = System.getenv("SIGNING_KEY_ALIAS") ?: error("SIGNING_KEY_ALIAS repository secret is required")
            val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: error("SIGNING_KEY_PASSWORD repository secret is required")
            val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: error("SIGNING_STORE_PASSWORD repository secret is required")
            val keystoreFile = file(signingStore)
            require(keystoreFile.isFile) { "Release keystore does not exist: $signingStore" }
            storeFile = keystoreFile
            keyAlias = signingAlias
            keyPassword = signingKeyPassword
            storePassword = signingStorePassword
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildFeatures { compose = true; buildConfig = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
configurations.configureEach { exclude(group = "com.google.guava", module = "listenablefuture") }
dependencies {
    implementation(project(":core:ipc"))
    implementation(project(":ui:design-system"))
    implementation(project(":core:logging"))
    implementation(project(":core:contracts"))
    implementation(project(":editor"))
    implementation(project(":terminal"))
    implementation(project(":workspace:impl"))
    implementation(project(":workspace:ui"))
    implementation(project(":runtime:toolchains"))
    implementation(project(":runtime:linux"))
    implementation(project(":runtime:process"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.commons.compress)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
}
