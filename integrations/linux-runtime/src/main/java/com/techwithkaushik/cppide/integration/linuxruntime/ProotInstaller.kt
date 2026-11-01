package com.techwithkaushik.cppide.integration.linuxruntime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Installs a PRoot Android binary from a user-selected .tar.gz release archive. */
class ProotInstaller(private val paths: LinuxRuntimePaths) {
    fun install(archive: File, expectedSha256: String): String {
        require(archive.isFile && archive.length() > 0) { "PRoot archive is missing." }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "A valid SHA-256 is required for the PRoot archive." }
        val actual = sha256(archive)
        require(actual.equals(expectedSha256, ignoreCase = true)) { "PRoot archive SHA-256 mismatch." }
        paths.ensureDirectories()
        val temp = File(paths.staging, "proot-${System.currentTimeMillis()}")
        if (!temp.mkdirs()) throw IOException("Cannot create PRoot staging directory.")
        var found = false
        try {
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
                var entries = 0
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (++entries > 2_000) throw IOException("PRoot archive contains too many entries.")
                    if (!entry.isFile) continue
                    val leaf = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                    if (leaf == "proot" || leaf == "proot-rs") {
                        if (entry.size !in 1..(64L * 1024 * 1024)) throw IOException("Invalid PRoot binary size.")
                        val candidate = File(temp, "proot")
                        candidate.outputStream().buffered().use { tar.copyTo(it) }
                        if (candidate.length() != entry.size) throw IOException("Truncated PRoot binary.")
                        if (!candidate.setExecutable(true, true)) throw IOException("Cannot mark PRoot executable.")
                        val magic = candidate.inputStream().use { stream -> ByteArray(4).also { if (stream.read(it) != 4) throw IOException("Truncated PRoot ELF.") } }
                        if (magic.map { it.toInt() and 0xff } != listOf(0x7f, 0x45, 0x4c, 0x46)) {
                            throw IOException("The selected PRoot file is not an ELF executable.")
                        }
                        found = true
                        break
                    }
                }
            }
            if (!found) throw IOException("Archive did not contain a file named proot or proot-rs.")
            val target = paths.proot
            val backup = File(paths.staging, "proot-previous")
            if (backup.exists()) backup.delete()
            if (target.exists() && !target.renameTo(backup)) throw IOException("Cannot back up existing PRoot.")
            if (!File(temp, "proot").renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw IOException("Cannot activate PRoot binary.")
            }
            target.setExecutable(true, true)
            backup.delete()
            return sha256(target)
        } finally {
            temp.deleteRecursively()
        }
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
