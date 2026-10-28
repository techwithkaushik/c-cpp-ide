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

    // Keep Java and Kotlin output compatible across CI and local builds.
    compileOptions {
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

    buildFeatures { compose = true }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:aidl"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:explorer"))
    implementation(project(":services:daemon"))
    implementation(project(":integrations:toolchain"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    implementation(libs.commons.compress)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
}
