package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads the pinned official Ubuntu Base release and verifies it against Ubuntu's SHA256SUMS. */
class UbuntuBaseDownloader(private val paths: LinuxRuntimePaths) {
    data class Result(val rootfs: RootfsInstaller.Result, val sha256: String, val bytes: Long)

    fun downloadAndInstall(onProgress: (Long, Long) -> Unit = { _, _ -> }): Result {
        paths.ensureDirectories()
        val arch = paths.ubuntuArchitecture
        val fileName = "ubuntu-base-24.04.5-base-$arch.tar.gz"
        val baseUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/"
        val sumsFile = File(paths.base, "SHA256SUMS")
        val archive = File(paths.base, fileName)
        try {
            download(URL(baseUrl + "SHA256SUMS"), sumsFile, MAX_SUMS_BYTES, null)
            val expected = sumsFile.useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    if (parts.size == 2 && parts[1].trimStart('*') == fileName) parts[0] else null
                }.firstOrNull()
            } ?: throw IllegalStateException("Ubuntu SHA256SUMS does not list $fileName.")
            if (!expected.matches(Regex("[a-fA-F0-9]{64}"))) throw IllegalStateException("Ubuntu SHA256SUMS contains an invalid digest.")
            download(URL(baseUrl + fileName), archive, MAX_ROOTFS_ARCHIVE_BYTES, onProgress)
            val actual = sha256(archive)
            if (!actual.equals(expected, ignoreCase = true)) throw SecurityException("Ubuntu rootfs checksum verification failed.")
            val byteCount = archive.length()
            val installed = RootfsInstaller(paths).install(archive, expected)
            return Result(installed, actual, byteCount)
        } finally {
            // The extracted rootfs is the installed runtime; keeping the compressed
            // archive would waste hundreds of MiB in the app-private data directory.
            archive.delete()
            sumsFile.delete()
        }
    }

    private fun download(url: URL, destination: File, maxBytes: Long, progress: ((Long, Long) -> Unit)?) {
        val allowedHosts = setOf("cdimage.ubuntu.com", "releases.cdimage.ubuntu.com")
        var current = url
        var redirects = 0
        var connection: HttpURLConnection
        while (true) {
            if (current.protocol != "https" || current.host !in allowedHosts) {
                throw SecurityException("Ubuntu download attempted an untrusted host.")
            }
            connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || ++redirects > 3) throw IllegalStateException("Ubuntu download redirect chain is invalid.")
                current = URL(current, location)
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IllegalStateException("Ubuntu download failed with HTTP $code.")
            }
            break
        }
        try {
            val length = connection.contentLengthLong
            if (length > maxBytes) throw IllegalStateException("Ubuntu download exceeds the size limit.")
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IllegalStateException("Ubuntu download exceeds the size limit.")
                        out.write(buffer, 0, n)
                        progress?.invoke(total, length)
                    }
                    if (length >= 0 && total != length) throw IllegalStateException("Ubuntu download was truncated.")
                }
            }
        } finally { connection.disconnect() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_SUMS_BYTES = 256 * 1024L
        private const val MAX_ROOTFS_ARCHIVE_BYTES = 512 * 1024 * 1024L
    }
}
