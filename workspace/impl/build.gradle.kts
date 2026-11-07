plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.techwithkaushik.cppide.workspace.impl"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":workspace:api"))
    implementation(project(":core:logging"))
    testImplementation(libs.junit)
}
