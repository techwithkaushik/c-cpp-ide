plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.techwithkaushik.cppide.core.lsp"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:aidl"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
}
