plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.techwithkaushik.cppide.runtime.process"
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
    api(project(":core:contracts"))
    api(project(":runtime:toolchains"))
    api(project(":workspace:api"))
}
