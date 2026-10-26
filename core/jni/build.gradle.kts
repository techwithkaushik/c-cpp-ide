plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.techwithkaushik.cppide.core.jni"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake { cppFlags += "-std=c++20" }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
}
