package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.logging.DeveloperLog
import java.io.File
import java.security.MessageDigest

/** Validates Android-native PRoot binaries bundled in the APK. */
class ProotDownloader(private val paths: LinuxRuntimePaths) {
    data class Result(val sha256: String, val bytes: Long, val release: String)

    fun downloadAndInstall(): Result {
        DeveloperLog.info("PRoot", "Validating APK-bundled Android PRoot binaries")
        try {
            paths.ensureDirectories()
            val binary = paths.proot
            val loader = paths.prootLoader
            if (paths.nativeLibraryDir == null) throw IllegalStateException(
                "This APK cannot locate its native library directory. Rebuild with PRoot v5.5.0 bundled as native libraries."
            )
            if (!binary.isFile || !loader.isFile) throw IllegalStateException(
                "PRoot v5.5.0 Android binaries are missing from ${paths.nativeLibraryDir}. Build the APK with the native PRoot build workflow."
            )
            validateElf(binary)
            validateElf(loader)
            val result = Result(sha256(binary), binary.length() + loader.length(), "PRoot v5.5.0 (Android NDK)")
            DeveloperLog.info("PRoot", "Bundled PRoot validation passed; bytes=${result.bytes}; release=${result.release}")
            return result
        } catch (error: Exception) {
            DeveloperLog.error("PRoot", "Bundled PRoot validation failed", error)
            throw error
        }
    }

    private fun validateElf(file: File) {
        val header = ByteArray(20)
        var headerBytes = 0
        file.inputStream().use { input ->
            while (headerBytes < header.size) {
                val count = input.read(header, headerBytes, header.size - headerBytes)
                if (count < 0) break
                if (count == 0) continue
                headerBytes += count
            }
        }
        require(headerBytes == 20 && header.take(4).map { it.toInt() and 0xff } == listOf(0x7f,0x45,0x4c,0x46)) {
            "Bundled ${file.name} is not a valid ELF executable."
        }
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        val x86Device = android.os.Build.SUPPORTED_ABIS.any { it.startsWith("x86") }
        val expected = when {
            android.os.Process.is64Bit() && x86Device -> 62
            android.os.Process.is64Bit() -> 183
            x86Device -> 3
            else -> 40
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
