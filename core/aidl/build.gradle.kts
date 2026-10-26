plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.techwithkaushik.cppide.core.aidl"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }
}
