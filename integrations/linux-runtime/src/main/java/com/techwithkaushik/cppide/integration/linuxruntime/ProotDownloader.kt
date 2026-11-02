package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.security.MessageDigest

/**
 * Validates Android-native PRoot binaries bundled in the APK. Downloading a
 * generic Linux release into app data is not compatible with Android W^X or bionic.
 */
class ProotDownloader(private val paths: LinuxRuntimePaths) {
    data class Result(val sha256: String, val bytes: Long, val release: String)

    fun downloadAndInstall(): Result {
        paths.ensureDirectories()
        val binary = paths.proot
        val loader = paths.prootLoader
        if (paths.nativeLibraryDir == null) {
            throw IllegalStateException(
                "This APK cannot locate its native library directory. Rebuild with PRoot v5.5.0 bundled as native libraries."
            )
        }
        if (!binary.isFile || !loader.isFile) {
            throw IllegalStateException(
                "PRoot v5.5.0 Android binaries are missing from ${paths.nativeLibraryDir}. Build the APK with the native PRoot build workflow."
            )
        }
        validateElf(binary)
        validateElf(loader)
        return Result(sha256(binary), binary.length() + loader.length(), "PRoot v5.5.0 (Android NDK)")
    }

    private fun validateElf(file: File) {
        val header = file.inputStream().use { it.readNBytes(20) }
        require(header.size == 20 && header.take(4).map { it.toInt() and 0xff } ==
            listOf(0x7f, 0x45, 0x4c, 0x46)) { "Bundled ${file.name} is not a valid ELF executable." }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val expected = when {
            abi.contains("arm64") -> 183
            abi.contains("armeabi") -> 40
            abi.contains("x86_64") -> 62
            else -> 3
        }
        require(machine == expected) { "PRoot ABI mismatch: ELF machine=$machine, expected=$expected." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
