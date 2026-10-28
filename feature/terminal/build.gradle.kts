plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.techwithkaushik.cppide.feature.terminal"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:aidl"))

    // Reuse the upstream Termux emulator and View instead of maintaining a forked parser.
    // Pin to the inspected termux/termux-app master commit; update via reviewed revision bumps.
    implementation("com.github.termux.termux-app:terminal-emulator:8629e632fcb95da272221be327db653fb24befe9")
    implementation("com.github.termux.termux-app:terminal-view:8629e632fcb95da272221be327db653fb24befe9")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
}
