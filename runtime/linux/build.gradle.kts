plugins {
    alias(libs.plugins.android.library)
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
    api(project(":core:contracts"))
    implementation(project(":core:jni"))
    implementation(project(":core:logging"))
    implementation(libs.commons.compress)
    implementation(libs.xz)
    testImplementation(libs.junit)
}
