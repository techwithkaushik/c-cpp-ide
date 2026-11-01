package com.techwithkaushik.cppide.integration.linuxruntime

import android.os.Process
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
        val arch = if (Process.is64Bit()) "arm64" else "armhf"
        val fileName = "ubuntu-base-24.04.5-base-$arch.tar.gz"
        val baseUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/"
        val sumsFile = File(paths.base, "SHA256SUMS")
        download(URL(baseUrl + "SHA256SUMS"), sumsFile, MAX_SUMS_BYTES, null)
        val expected = sumsFile.useLines { lines ->
            lines.mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[1].trimStart('*') == fileName) parts[0] else null
            }.firstOrNull()
        } ?: throw IllegalStateException("Ubuntu SHA256SUMS does not list $fileName.")
        if (!expected.matches(Regex("[a-fA-F0-9]{64}"))) throw IllegalStateException("Ubuntu SHA256SUMS contains an invalid digest.")
        val archive = File(paths.base, fileName)
        download(URL(baseUrl + fileName), archive, MAX_ROOTFS_ARCHIVE_BYTES, onProgress)
        val actual = sha256(archive)
        if (!actual.equals(expected, ignoreCase = true)) {
            archive.delete()
            throw SecurityException("Ubuntu rootfs checksum verification failed.")
        }
        val installed = RootfsInstaller(paths).install(archive, expected)
        return Result(installed, actual, archive.length())
    }

    private fun download(url: URL, destination: File, maxBytes: Long, progress: ((Long, Long) -> Unit)?) {
        require(url.protocol == "https" && url.host == "cdimage.ubuntu.com") { "Refusing an untrusted Ubuntu download URL." }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Ubuntu download failed with HTTP $code.")
            if (connection.url.protocol != "https" || connection.url.host !in setOf("cdimage.ubuntu.com", "releases.cdimage.ubuntu.com")) {
                throw SecurityException("Ubuntu download redirected to an untrusted host.")
            }
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
