package com.techwithkaushik.cppide

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Downloads/imports Ubuntu Base and safely extracts its rootfs. Extraction alone is not runtime validation. */
internal class LinuxDistroInstaller(private val filesDir: File, private val contentResolver: ContentResolver) {
    data class Result(val archive: File, val rootfs: File, val message: String)
    private val downloadDir get() = File(filesDir, "linux/downloads").apply { mkdirs() }
    private val rootfsDir get() = File(filesDir, "linux/rootfs/ubuntu").apply { mkdirs() }

    fun recommendedArchiveName(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val arch = when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a", "armeabi" -> "armhf"
            "x86_64" -> "amd64"
            else -> error("Unsupported Android ABI: $abi")
        }
        return "ubuntu-base-24.04.5-base-$arch.tar.gz"
    }

    suspend fun downloadUbuntu(onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val name = recommendedArchiveName()
        val base = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/"
        val sums = URL(base + "SHA256SUMS").openStream().bufferedReader().use { it.readText() }
        val expected = sums.lineSequence().firstNotNullOfOrNull { line ->
            val match = Regex("^([0-9a-fA-F]{64})\\s+\\*?$name\\z").find(line.trim())
                ?: Regex("^([0-9a-fA-F]{64})\\s+$name\\z").find(line.trim())
            match?.groupValues?.get(1)
        } ?: error("Ubuntu SHA256SUMS does not contain $name")
        val destination = File(downloadDir, name)
        val connection = (URL(base + name).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 45_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            check(connection.responseCode in 200..299) { "Download failed: HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            if (destination.exists()) destination.delete()
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(received, total)
                    }
                }
            }
        } finally { connection.disconnect() }
        val actual = sha256(destination)
        check(actual.equals(expected, ignoreCase = true)) {
            destination.delete()
            "Ubuntu archive checksum mismatch; the downloaded file was discarded."
        }
        destination
    }

    suspend fun importArchive(uri: Uri): File = withContext(Dispatchers.IO) {
        val destination = File(downloadDir, "selected-rootfs.tar.gz")
        val input = contentResolver.openInputStream(uri) ?: error("Could not open selected archive")
        input.use { source -> FileOutputStream(destination).use { output -> source.copyTo(output) } }
        validateGzipTar(destination)
        destination
    }

    suspend fun installArchive(archive: File): Result = withContext(Dispatchers.IO) {
        validateGzipTar(archive)
        val staging = File(filesDir, "linux/rootfs/ubuntu-staging")
        if (staging.exists()) staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create Linux rootfs staging directory" }
        val root = staging.canonicalFile
        try {
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
                var entries = 0
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    entries++
                    check(entries <= MAX_ENTRIES) { "Archive contains too many entries" }
                    val normalized = entry.name.replace('\\', '/').removePrefix("./")
                    check(normalized.isNotBlank() && !normalized.startsWith("/") &&
                        normalized.split('/').none { it == ".." }) { "Unsafe path in archive: ${entry.name}" }
                    val output = File(root, normalized).canonicalFile
                    check(output.toPath().startsWith(root.toPath())) { "Archive path escapes rootfs" }
                    if (entry.isDirectory) {
                        check(output.isDirectory || output.mkdirs()) { "Could not create ${entry.name}" }
                    } else if (entry.isSymbolicLink) {
                        val rawTarget = entry.linkName
                        val targetFile = if (rawTarget.startsWith("/")) File(root, rawTarget.removePrefix("/"))
                            else File(output.parentFile, rawTarget)
                        val target = targetFile.toPath().normalize()
                        check(target.startsWith(root.toPath())) { "Unsafe symlink target in archive" }
                        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                        if (output.exists() || output.isSymbolicLink()) output.delete()
                        val relativeTarget = output.parentFile.toPath().relativize(target).toString()
                        java.nio.file.Files.createSymbolicLink(output.toPath(), java.nio.file.Paths.get(relativeTarget))
                    } else if (entry.isFile) {
                        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                        check(!output.isSymbolicLink()) { "Archive attempts to overwrite a symlink: ${entry.name}" }
                        FileOutputStream(output).use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var remaining = entry.size
                            while (remaining > 0) {
                                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                check(count > 0) { "Unexpected end of archive entry: ${entry.name}" }
                                out.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                        if (entry.mode and 0b001001001 != 0) output.setExecutable(true, false)
                    } else if (entry.isLink) {
                        val targetName = entry.linkName.removePrefix("./")
                        check(targetName.isNotBlank() && !targetName.startsWith("/") &&
                            targetName.split('/').none { it == ".." }) { "Unsafe hard-link target" }
                        val target = File(root, targetName).canonicalFile
                        check(target.toPath().startsWith(root.toPath()) && target.isFile) {
                            "Hard-link target is missing or unsafe: ${entry.linkName}"
                        }
                        output.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
                        java.nio.file.Files.createLink(output.toPath(), target.toPath())
                    }
                }
            }
            check(File(staging, "etc/os-release").isFile || File(staging, "usr/lib/os-release").isFile) {
                "Archive does not look like a Linux root filesystem (os-release is missing)"
            }
            val finalRoot = rootfsDir
            val backup = File(filesDir, "linux/rootfs/ubuntu-previous")
            if (backup.exists()) backup.deleteRecursively()
            if (finalRoot.exists() && !finalRoot.renameTo(backup)) error("Could not preserve previous rootfs")
            if (!staging.renameTo(finalRoot)) {
                if (backup.exists()) backup.renameTo(finalRoot)
                error("Could not activate extracted rootfs")
            }
            backup.deleteRecursively()
            Result(archive, finalRoot, "Ubuntu Base extracted. Runtime execution still needs validation.")
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    /** Prepare persistent HOME/workspace and deterministic environment metadata after extraction. */
    suspend fun prepareEnvironment(rootfs: File): File = withContext(Dispatchers.IO) {
        val root = rootfs.canonicalFile
        check(root.isDirectory && (File(root, "etc/os-release").isFile || File(root, "usr/lib/os-release").isFile)) {
            "Linux root filesystem is not installed."
        }
        val home = File(root, "home/ide")
        val workspace = File(filesDir, "linux/workspace")
        val tmp = File(filesDir, "linux/tmp")
        check(home.mkdirs() || home.isDirectory) { "Could not create Linux HOME." }
        check(workspace.mkdirs() || workspace.isDirectory) { "Could not create Linux workspace." }
        check(tmp.mkdirs() || tmp.isDirectory) { "Could not create Linux temporary directory." }
        val config = File(filesDir, "linux/environment.properties")
        config.writeText("DISTRO=ubuntu\\nROOTFS=" + root.absolutePath + "\\nHOME=" + home.absolutePath + "\\nWORKSPACE=" + workspace.absolutePath + "\\nTMPDIR=" + tmp.absolutePath + "\\nPATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\\n")
        config
    }

    /** No compatible runtime is bundled yet; do not claim shell access until it is validated. */
    fun hasExecutionRuntime(rootfs: File): Boolean = false
    private fun validateGzipTar(file: File) {
        check(file.isFile && file.length() >= 2) { "Selected file is empty or missing" }
        FileInputStream(file).use {
            check(it.read() == 0x1f && it.read() == 0x8b) { "Select a gzip-compressed Linux rootfs (.tar.gz)" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun File.isSymbolicLink(): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(toPath()) }.getOrDefault(false)

    companion object { private const val MAX_ENTRIES = 250_000 }
}
