plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.techwithkaushik.cppide.integration.linuxruntime"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:jni"))
    implementation(project(":core:logging"))
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.jsch)
    testImplementation(libs.junit)
}
