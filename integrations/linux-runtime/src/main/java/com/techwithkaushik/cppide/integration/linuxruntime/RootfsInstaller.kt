package com.techwithkaushik.cppide.integration.linuxruntime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Extracts a tar archive without distro-specific edits. The resulting files are
 * used in-place at paths.ubuntuRoot, just as with: tar -xf archive -C distro.
 */
class RootfsInstaller(private val paths: LinuxRuntimePaths) {
    data class Result(val root: File, val entries: Int, val expandedBytes: Long)

    fun install(archive: File, expectedSha256: String? = null): Result {
        require(archive.isFile && archive.length() > 0) { "Rootfs archive is missing or empty." }
        if (expectedSha256 != null) {
            require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256 value." }
            require(sha256(archive).equals(expectedSha256, ignoreCase = true)) { "Rootfs SHA-256 mismatch." }
        }

        paths.ensureDirectories()
        val stage = File(paths.staging, "distro-${System.currentTimeMillis()}")
        if (!stage.mkdirs()) throw IOException("Cannot create extraction directory.")
        var count = 0
        var expanded = 0L
        try {
            val raw = BufferedInputStream(FileInputStream(archive))
            val lower = archive.name.lowercase(Locale.ROOT)
            val decoded = when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> GzipCompressorInputStream(raw)
                lower.endsWith(".tar.xz") -> XZCompressorInputStream(raw)
                lower.endsWith(".tar") -> raw
                else -> throw IOException("Unsupported archive. Use .tar, .tar.gz, or .tar.xz.")
            }
            TarArchiveInputStream(decoded).use { tar ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) throw IOException("Archive contains too many entries.")
                    val target = safeTarget(stage, entry.name)
                    when {
                        entry.isSymbolicLink -> createSafeSymlink(stage, target, entry.linkName)
                        entry.isDirectory -> {
                            removeEntrySymlink(target)
                            if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create directory: ${entry.name}")
                            if (!target.isDirectory) throw IOException("Directory conflicts with non-directory: ${entry.name}")
                        }
                        entry.isLink -> {
                            val linked = safeTarget(stage, entry.linkName)
                            if (!linked.isFile || Files.isSymbolicLink(linked.toPath())) throw IOException("Invalid hard link: ${entry.name}")
                            target.parentFile?.mkdirs()
                            removeEntrySymlink(target)
                            Files.copy(linked.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        entry.isFile -> {
                            if (entry.size < 0 || entry.size > MAX_EXPANDED_BYTES - expanded) {
                                throw IOException("Archive exceeds the 4 GiB expanded-size limit.")
                            }
                            removeEntrySymlink(target)
                            target.parentFile?.let {
                                if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create parent directory.")
                            }
                            target.outputStream().buffered().use { out ->
                                var copied = 0L
                                while (true) {
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    copied += n
                                    expanded += n
                                    if (expanded > MAX_EXPANDED_BYTES) throw IOException("Archive exceeds the 4 GiB expanded-size limit.")
                                    out.write(buffer, 0, n)
                                }
                                if (copied != entry.size) throw IOException("Truncated archive entry: ${entry.name}")
                            }
                            target.setReadable(true, false)
                            target.setWritable(entry.mode and 0x80 != 0, true)
                            if (entry.mode and 0x49 != 0) target.setExecutable(true, false)
                        }
                    }
                }
            }

            // Activate the unmodified extracted tree at the one canonical distro path.
            val old = File(paths.staging, "distro-previous")
            if (old.exists() && !old.deleteRecursively()) throw IOException("Cannot remove previous extraction backup.")
            if (paths.ubuntuRoot.exists() && !paths.ubuntuRoot.renameTo(old)) {
                throw IOException("Cannot replace existing distro directory.")
            }
            if (!stage.renameTo(paths.ubuntuRoot)) {
                if (old.exists()) old.renameTo(paths.ubuntuRoot)
                throw IOException("Cannot activate extracted distro directory.")
            }
            old.deleteRecursively()
            paths.marker.writeText(
                "installerVersion=$INSTALLER_VERSION\n" +
                    "root=${paths.ubuntuRoot.absolutePath}\n" +
                    "entries=$count\nexpandedBytes=$expanded\n"
            )
            return Result(paths.ubuntuRoot, count, expanded)
        } catch (error: Exception) {
            stage.deleteRecursively()
            throw error
        }
    }

    private fun safeTarget(root: File, name: String): File {
        if (name.startsWith("/") || name.split('/').any { it == ".." }) {
            throw IOException("Unsafe archive path: $name")
        }
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val target = rootPath.resolve(name).normalize()
        if (!target.startsWith(rootPath) || target == rootPath) throw IOException("Path escapes extraction directory: $name")
        return target.toFile()
    }

    private fun removeEntrySymlink(target: File) {
        if (Files.isSymbolicLink(target.toPath())) Files.deleteIfExists(target.toPath())
    }

    private fun createSafeSymlink(root: File, link: File, rawTarget: String) {
        if (rawTarget.isBlank()) throw IOException("Empty symlink target.")
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val linkPath = link.toPath().toAbsolutePath().normalize()
        val parent = linkPath.parent ?: throw IOException("Invalid symlink path.")
        val resolved = if (rawTarget.startsWith("/")) {
            rootPath.resolve(rawTarget.removePrefix("/")).normalize()
        } else {
            parent.resolve(rawTarget).normalize()
        }
        if (!resolved.startsWith(rootPath)) throw IOException("Symlink escapes extraction directory: $rawTarget")
        val target = parent.relativize(resolved)
        if (!parent.toFile().exists() && !parent.toFile().mkdirs()) throw IOException("Cannot create symlink parent.")
        if (Files.exists(linkPath) || Files.isSymbolicLink(linkPath)) Files.delete(linkPath)
        Files.createSymbolicLink(linkPath, target)
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
        private const val INSTALLER_VERSION = 5
        private const val MAX_ENTRIES = 500_000
        private const val MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024
    }
}
