package com.techwithkaushik.cppide.integration.linuxruntime

import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a version-pinned upstream proot-rs Android binary (not a Termux package).
 * The v0.1.0 upstream release does not publish asset SHA-256 values, so this method
 * pins the immutable release URL, enforces a strict size limit, and validates the ELF.
 * A future app release should replace this with a project-published digest manifest.
 */
class ProotDownloader(private val paths: LinuxRuntimePaths) {
    data class Result(val sha256: String, val bytes: Long, val release: String)

    fun downloadAndInstall(): Result {
        paths.ensureDirectories()
        val asset = if (Process.is64Bit()) {
            "proot-rs-v0.1.0-aarch64-linux-android.tar.gz"
        } else {
            "proot-rs-v0.1.0-arm-linux-androideabi.tar.gz"
        }
        val url = URL("https://github.com/proot-me/proot-rs/releases/download/v0.1.0/$asset")
        val archive = File(paths.base, asset)
        try {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) throw IllegalStateException("PRoot download failed with HTTP ${connection.responseCode}.")
                if (connection.url.protocol != "https" || connection.url.host !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                    throw SecurityException("PRoot download redirected to an untrusted host.")
                }
                if (connection.contentLengthLong > MAX_ARCHIVE_BYTES) throw IllegalStateException("PRoot archive is larger than expected.")
                FileOutputStream(archive).use { out ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_ARCHIVE_BYTES) throw IllegalStateException("PRoot archive is larger than expected.")
                            out.write(buffer, 0, n)
                        }
                    }
                }
            } finally { connection.disconnect() }
            val digest = sha256(archive)
            ProotInstaller(paths).installFromPinnedRelease(archive)
            return Result(digest, paths.proot.length(), "proot-rs v0.1.0")
        } finally {
            archive.delete()
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

    companion object { private const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024L }
}
