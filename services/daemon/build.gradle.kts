plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.techwithkaushik.cppide.services.daemon"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:aidl"))
    implementation(project(":core:jni"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
