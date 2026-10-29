plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.termux.shared"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig { minSdk = 26 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild { ndkBuild { path = file("src/main/cpp/Android.mk") } }
}
dependencies {
    implementation(project(":integrations:termux-upstream:terminal-view"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.core:core:1.17.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:recycler:4.6.2")
    implementation("androidx.window:window:1.4.0")
    implementation("commons-io:commons-io:2.19.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
