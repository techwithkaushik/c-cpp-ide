package com.techwithkaushik.cppide.integration.termuxruntime

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Installs a verified Termux-compatible bootstrap into an app-private prefix.
 *
 * This class deliberately does not launch bootstrap scripts or claim that the
 * resulting binaries are executable on the current Android release. A separate
 * native execution adapter and device smoke test must establish that contract.
 *
 * Run from a background dispatcher/thread. The caller must provide a destination
 * prefix matching the prefix compiled into the custom bootstrap.
 */
class BootstrapInstaller(
    private val maxArchiveBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
    private val maxExpandedBytes: Long = DEFAULT_MAX_EXPANDED_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    data class Result(
        val prefix: File,
        val sha256: String,
        val filesExtracted: Int,
        val symlinksCreated: Int,
    )

    fun install(
        archive: File,
        expectedSha256: String,
        prefixDirectory: File,
    ): Result {
        require(archive.isFile) { "Bootstrap archive does not exist" }
        require(expectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            "A pinned SHA-256 digest is required"
        }
        require(archive.length() in 1..maxArchiveBytes) { "Bootstrap archive size is invalid" }

        val digest = sha256(archive)
        require(digest.equals(expectedSha256, ignoreCase = true)) {
            "Bootstrap checksum mismatch"
        }

        val parent = prefixDirectory.absoluteFile.parentFile
            ?: throw IOException("Prefix must have a parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create runtime parent directory")
        }
        require(parent.isDirectory) { "Runtime parent is not a directory" }
        require(!prefixDirectory.exists() || prefixDirectory.isDirectory) {
            "Runtime prefix path exists and is not a directory"
        }

        val staging = File(parent, ".${prefixDirectory.name}.staging-${UUID.randomUUID()}")
        val backup = File(parent, ".${prefixDirectory.name}.backup-${UUID.randomUUID()}")
        var promoted = false

        try {
            if (!staging.mkdir()) throw IOException("Unable to create staging directory")
            val symlinkManifest = File(staging, "SYMLINKS.txt")
            var entryCount = 0
            var expandedBytes = 0L
            val seen = HashSet<String>()

            ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > maxEntries) throw IOException("Bootstrap contains too many entries")

                    val rawName = entry.name
                    val relative = validateArchivePath(rawName)
                    if (!seen.add(relative)) throw IOException("Duplicate archive entry: $relative")

                    val target = File(staging, relative)
                    val canonicalTarget = target.canonicalFile
                    val canonicalStaging = staging.canonicalFile
                    if (canonicalTarget != canonicalStaging &&
                        !canonicalTarget.path.startsWith(canonicalStaging.path + File.separator)
                    ) {
                        throw IOException("Archive entry escapes staging directory")
                    }

                    if (entry.isDirectory) {
                        if (!target.isDirectory && !target.mkdirs()) {
                            throw IOException("Unable to create directory: $relative")
                        }
                    } else {
                        val targetParent = target.parentFile
                        if (targetParent != null && !targetParent.isDirectory && !targetParent.mkdirs()) {
                            throw IOException("Unable to create parent directory for: $relative")
                        }
                        FileOutputStream(target).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                expandedBytes += count
                                if (expandedBytes > maxExpandedBytes) {
                                    throw IOException("Bootstrap expands beyond the allowed size")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }

            val symlinkCount = if (symlinkManifest.isFile) {
                createSymlinks(symlinkManifest, staging, prefixDirectory.absoluteFile)
            } else {
                0
            }
            symlinkManifest.delete()

            setExecutableBits(staging)
            validateBootstrapLayout(staging)

            // Keep the previous prefix until the staged tree is ready. Renames
            // are within the same parent filesystem, so promotion is atomic per rename.
            if (prefixDirectory.exists()) {
                if (!prefixDirectory.renameTo(backup)) {
                    throw IOException("Unable to move existing runtime prefix aside")
                }
            }
            if (!staging.renameTo(prefixDirectory)) {
                if (backup.exists()) backup.renameTo(prefixDirectory)
                throw IOException("Unable to promote staged runtime prefix")
            }
            promoted = true
            backup.deleteRecursively()

            return Result(prefixDirectory, digest, entryCount, symlinkCount)
        } finally {
            if (!promoted) {
                staging.deleteRecursively()
                if (backup.exists() && !prefixDirectory.exists()) backup.renameTo(prefixDirectory)
            }
        }
    }

    private fun createSymlinks(manifest: File, staging: File, finalPrefix: File): Int {
        var created = 0
        manifest.useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val line = raw.trimEnd()
                if (line.isBlank()) return@forEachIndexed
                val separator = line.indexOf('←')
                if (separator <= 0 || separator == line.lastIndex) {
                    throw IOException("Malformed SYMLINKS.txt line ${index + 1}")
                }

                val rawTarget = line.substring(0, separator)
                val rawLink = line.substring(separator + 1)
                val linkRelative = validateArchivePath(rawLink)
                val linkInStage = File(staging, linkRelative)
                val linkInFinal = File(finalPrefix, linkRelative)
                val targetFile = if (rawTarget.startsWith("/")) {
                    val absoluteTarget = File(rawTarget).toPath().toAbsolutePath().normalize().toFile()
                    val finalNormalized = finalPrefix.toPath().toAbsolutePath().normalize().toFile()
                    if (absoluteTarget != finalNormalized &&
                        !absoluteTarget.path.startsWith(finalNormalized.path + File.separator)
                    ) {
                        throw IOException("Absolute symlink target escapes runtime prefix")
                    }
                    absoluteTarget
                } else {
                    if (rawTarget.contains('\\')) throw IOException("Invalid symlink target")
                    val resolved = File(linkInFinal.parentFile, rawTarget).toPath().toAbsolutePath().normalize().toFile()
                    val finalNormalized = finalPrefix.absoluteFile.normalize()
                    if (resolved != finalNormalized &&
                        !resolved.path.startsWith(finalNormalized.path + File.separator)
                    ) {
                        throw IOException("Relative symlink target escapes runtime prefix")
                    }
                    resolved
                }

                if (linkInStage.exists() || java.nio.file.Files.isSymbolicLink(linkInStage.toPath())) {
                    throw IOException("Symlink destination already exists: $linkRelative")
                }
                val parent = linkInStage.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("Unable to create symlink parent")
                }
                try {
                    java.nio.file.Files.createSymbolicLink(linkInStage.toPath(), targetFile.toPath())
                } catch (e: Exception) {
                    throw IOException("Unable to create bootstrap symlink: $linkRelative", e)
                }
                created++
            }
        }
        return created
    }

    private fun validateArchivePath(raw: String): String {
        if (raw.isBlank() || raw.startsWith("/") || raw.startsWith("\\") || raw.contains('\\')) {
            throw IOException("Unsafe archive path")
        }
        val normalized = java.nio.file.Paths.get(raw).normalize()
        if (normalized.isAbsolute || normalized.toString() == "." ||
            normalized.startsWith("..") || normalized.any { it.toString() == ".." }
        ) {
            throw IOException("Archive path escapes runtime prefix")
        }
        return normalized.toString()
    }

    private fun setExecutableBits(root: File) {
        val executableRoots = listOf(File(root, "bin"), File(root, "libexec"), File(root, "lib/apt/methods"))
        executableRoots.filter { it.exists() }.forEach { executableRoot ->
            executableRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                if (!file.setExecutable(true, false) && !file.canExecute()) {
                    throw IOException("Unable to mark bootstrap executable: ${file.relativeTo(root)}")
                }
            }
        }
    }

    private fun validateBootstrapLayout(root: File) {
        val required = listOf("bin", "etc", "lib", "share")
        required.forEach { name ->
            if (!File(root, name).exists()) throw IOException("Bootstrap is missing required path: $name")
        }
        if (!File(root, "bin/sh").exists() && !File(root, "bin/bash").exists()) {
            throw IOException("Bootstrap does not contain a shell entry point")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
        const val DEFAULT_MAX_ENTRIES = 100_000
    }
}
