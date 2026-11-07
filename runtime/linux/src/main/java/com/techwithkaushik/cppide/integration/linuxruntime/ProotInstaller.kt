package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.io.IOException

/**
 * Executable PRoot binaries must be built for Android and bundled into the APK.
 * Runtime extraction into writable app data is blocked by Android 10+ W^X policy.
 */
class ProotInstaller(private val paths: LinuxRuntimePaths) {
    fun installFromPinnedRelease(archive: File): String {
        throw IOException("Runtime PRoot downloads are disabled. Bundle PRoot v5.5.0 through the Android NDK build workflow.")
    }

    fun install(archive: File, expectedSha256: String): String {
        throw IOException("Installing PRoot into writable app data is unsupported on Android 10+. Rebuild the APK with bundled Android-native PRoot binaries.")
    }
}
