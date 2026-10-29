plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.termux.view"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":integrations:termux-upstream:terminal-emulator"))
    implementation("androidx.annotation:annotation:1.9.0")
}
