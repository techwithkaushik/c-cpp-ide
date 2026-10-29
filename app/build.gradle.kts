plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        versionCode = 1
        versionName = "0.1.0"
    }

    // Each distributable APK contains only native libraries and bootstrap data for one ABI.
    flavorDimensions += "bootstrapAbi"
    productFlavors {
        create("arm32") {
            dimension = "bootstrapAbi"
            ndk { abiFilters += "armeabi-v7a" }
            buildConfigField("String", "BOOTSTRAP_ABI", "\"armeabi-v7a\"")
        }
        create("arm64") {
            dimension = "bootstrapAbi"
            ndk { abiFilters += "arm64-v8a" }
            buildConfigField("String", "BOOTSTRAP_ABI", "\"arm64-v8a\"")
        }
        create("x86") {
            dimension = "bootstrapAbi"
            ndk { abiFilters += "x86" }
            buildConfigField("String", "BOOTSTRAP_ABI", "\"x86\"")
        }
        create("x64") {
            dimension = "bootstrapAbi"
            ndk { abiFilters += "x86_64" }
            buildConfigField("String", "BOOTSTRAP_ABI", "\"x86_64\"")
        }
    }

    sourceSets {
        getByName("arm32").assets.srcDir(layout.buildDirectory.dir("generated/bootstrapAssets/arm32"))
        getByName("arm64").assets.srcDir(layout.buildDirectory.dir("generated/bootstrapAssets/arm64"))
        getByName("x86").assets.srcDir(layout.buildDirectory.dir("generated/bootstrapAssets/x86"))
        getByName("x64").assets.srcDir(layout.buildDirectory.dir("generated/bootstrapAssets/x64"))
    }

    // Keep Java and Kotlin output compatible across CI and local builds.
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            val signingStore = System.getenv("SIGN_KEY_STORE")
            val signingAlias = System.getenv("SIGNING_KEY_ALIAS")
            val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
            if (!signingStore.isNullOrBlank()) {
                storeFile = file(signingStore)
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
                storePassword = signingStorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Android's dependency checker sees this empty compatibility artifact as a duplicate
// of the real ListenableFuture class already bundled in Guava. Exclude the stub.
configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

dependencies {
    implementation(project(":core:aidl"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:explorer"))
    implementation(project(":services:daemon"))
    implementation(project(":integrations:toolchain"))
    implementation(project(":integrations:termux-runtime"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    // ProfileInstaller requires this class at runtime on API 29 as well as newer Android.
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
