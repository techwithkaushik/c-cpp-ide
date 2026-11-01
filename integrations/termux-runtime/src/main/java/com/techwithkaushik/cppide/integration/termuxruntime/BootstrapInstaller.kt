package com.techwithkaushik.cppide.integration.termuxruntime

import android.system.Os
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
    private val expectedBootstrapPrefix: String = DEFAULT_BOOTSTRAP_PREFIX,
    private val requireSymlinkManifest: Boolean = false,
) {
    data class Result(
        val prefix: File,
        val sha256: String,
        val filesExtracted: Int,
        val symlinksCreated: Int,
    )

    fun install(
        archive: File,
        expectedSha256: String? = null,
        prefixDirectory: File,
        processSymlinks: Boolean = true,
        validateInstalledPrefix: ((File) -> Boolean)? = null,
    ): Result {
        require(archive.isFile) { "Bootstrap archive does not exist" }
        require(archive.length() in 1..maxArchiveBytes) { "Bootstrap archive size is invalid" }
        // Match Termux: published archives are validated in the build/release pipeline.
        // A first-run install does not require a separate checksum manifest.
        val digest = sha256(archive)
        if (expectedSha256 != null) {
            require(expectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                "Optional SHA-256 digest must contain 64 hexadecimal characters"
            }
            require(digest.equals(expectedSha256, ignoreCase = true)) {
                "Bootstrap checksum mismatch"
            }
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

        val installRoot = prefixDirectory.absoluteFile
        val backup = File(parent, ".${prefixDirectory.name}.backup-${UUID.randomUUID()}")
        var installedSuccessfully = false
        var previousPrefixMoved = false

        try {
            // Extract directly into the final app-private runtime prefix.
            // Preserve the previous runtime in a sibling backup for rollback.
            if (installRoot.exists()) {
                if (!installRoot.renameTo(backup)) throw IOException("Unable to move existing runtime prefix to backup")
                previousPrefixMoved = true
            }
            if (!installRoot.mkdirs()) throw IOException("Unable to create runtime prefix: $installRoot")
            val symlinkManifest = File(installRoot, "SYMLINKS.txt")

            var entryCount = 0
            var expandedBytes = 0L
            val seen = HashSet<String>()

            ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > maxEntries) throw IOException("Bootstrap contains too many entries")

                    // Only accept a bootstrap ZIP rooted at bin/, etc/, lib/,
                    // share/, and SYMLINKS.txt—not an app-files snapshot or usr/ wrapper.
                    val relative = validateArchivePath(entry.name)
                    if (!seen.add(relative)) throw IOException("Duplicate archive entry: $relative")

                    val target = File(installRoot, relative)
                    val canonicalTarget = target.canonicalFile
                    val canonicalRoot = installRoot.canonicalFile
                    if (canonicalTarget != canonicalRoot &&
                        !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
                    ) {
                        throw IOException("Archive entry escapes runtime prefix")
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

            if (processSymlinks && requireSymlinkManifest && !symlinkManifest.isFile) {
                throw IOException("Termux bootstrap is missing SYMLINKS.txt; refusing to install an incomplete runtime")
            }
            // The no-symlinks path intentionally does not parse or replay
            // SYMLINKS.txt. This makes it possible to test the archive's actual
            // extracted files independently from the app's symlink handling.
            val symlinkCount = if (processSymlinks && symlinkManifest.isFile) {
                createSymlinks(symlinkManifest, installRoot, installRoot)
            } else {
                0
            }
            symlinkManifest.delete()

            setExecutableBits(installRoot, installRoot)
            validateBootstrapLayout(installRoot, requireShell = processSymlinks)
            if (processSymlinks && requireSymlinkManifest) {
                validateTermuxSymlinkLayout(installRoot, installRoot, symlinkCount)
            }

            // Test the exact prefix where extraction occurred.
            if (validateInstalledPrefix != null && !validateInstalledPrefix(installRoot)) {
                throw IOException("Installed bootstrap failed runtime validation")
            }
            installedSuccessfully = true
            backup.deleteRecursively()
            return Result(installRoot, digest, entryCount, symlinkCount)
        } finally {
            if (!installedSuccessfully) {
                if (installRoot.exists()) installRoot.deleteRecursively()
                if (previousPrefixMoved && backup.exists() && !backup.renameTo(installRoot)) {
                    throw IOException("Runtime validation failed and previous runtime could not be restored")
                }
                if (backup.exists()) backup.deleteRecursively()
            }
        }
    }

    private fun createSymlinks(manifest: File, installRoot: File, finalPrefix: File): Int {
        var created = 0
        manifest.useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val line = raw.trimEnd()
                if (line.isBlank()) return@forEachIndexed
                val separator = line.indexOf('←')
                if (separator <= 0 || separator == line.lastIndex) {
                    throw IOException("Malformed SYMLINKS.txt line ${index + 1}")
                }

                // Termux manifests use spaces around the Unicode arrow delimiter.
                // Trim both sides before validating or resolving the paths.
                val rawTarget = line.substring(0, separator).trim()
                val rawLink = line.substring(separator + 1).trim()
                val linkRelative = validateArchivePath(rawLink)
                val linkInStage = File(installRoot, linkRelative)
                val targetFile = if (rawTarget.startsWith("/")) {
                    // Official Termux bootstrap archives encode absolute links for
                    // Termux's own package prefix. Remap only these known prefixes
                    // into this IDE's private prefix; never permit arbitrary absolute
                    // links to escape the installed runtime.
                    // The bootstrap must be built for this app package. Remapping
                    // another app's symlinks does not rewrite hard-coded ELF paths.
                    val sourcePrefixes = listOf(
                        expectedBootstrapPrefix,
                        expectedBootstrapPrefix.replace("/data/data/", "/data/user/0/"),
                        "/data/data/com.termux/files/usr",
                        "/data/user/0/com.termux/files/usr",
                    ).distinct()
                    val sourcePrefix = sourcePrefixes.firstOrNull { source ->
                        rawTarget == source || rawTarget.startsWith(source + "/")
                    }
                    val candidate = if (sourcePrefix != null) {
                        val suffix = rawTarget.removePrefix(sourcePrefix).trimStart('/')
                        if (suffix.isEmpty()) finalPrefix.toPath().toAbsolutePath().normalize().toFile()
                        else File(finalPrefix, suffix).toPath().toAbsolutePath().normalize().toFile()
                    } else {
                        File(rawTarget).toPath().toAbsolutePath().normalize().toFile()
                    }
                    val finalNormalized = finalPrefix.toPath().toAbsolutePath().normalize().toFile()
                    if (candidate != finalNormalized &&
                        !candidate.path.startsWith(finalNormalized.path + File.separator)
                    ) {
                        throw IOException("Absolute symlink target escapes runtime prefix")
                    }
                    candidate
                } else {
                    if (rawTarget.contains('\\')) throw IOException("Invalid symlink target")
                    val resolved = File(File(finalPrefix, linkRelative).parentFile, rawTarget).toPath().toAbsolutePath().normalize().toFile()
                    val finalNormalized = finalPrefix.toPath().toAbsolutePath().normalize().toFile()
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
                    Os.symlink(targetFile.absolutePath, linkInStage.absolutePath)
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

    private fun setExecutableBits(root: File, finalPrefix: File) {
        // ZIP extraction does not preserve Unix mode bits. Restore traversal
        // permissions for directories before making command files executable.
        root.walkTopDown().forEach { entry ->
            if (java.nio.file.Files.isSymbolicLink(entry.toPath())) return@forEach
            if (entry.isDirectory) {
                try { Os.chmod(entry.absolutePath, 0x1ED) } catch (error: Exception) { throw IOException("Unable to chmod bootstrap directory ${entry.relativeTo(root)}: ${error.message}", error) }
                if (!entry.canExecute()) throw IOException("Bootstrap directory is not executable after chmod: ${entry.relativeTo(root)}")
            }
        }
        val executableRoots = listOf(File(root, "bin"), File(root, "libexec"), File(root, "lib/apt/methods"))
        executableRoots.filter { it.exists() }.forEach { executableRoot ->
            executableRoot.walkTopDown().forEach { file ->
                if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.isFile) return@forEach
                try { Os.chmod(file.absolutePath, 0x1C0) } catch (error: Exception) { throw IOException("Unable to chmod bootstrap executable ${file.relativeTo(root)}: ${error.message}", error) }
                if (!file.canExecute()) throw IOException("Bootstrap executable bit is missing after chmod: ${file.relativeTo(root)}")
            }
        }

        // Some bootstrap archives expose sh/bash through symlinks whose target
        // is outside the normal bin traversal. Resolve the final runtime target
        // explicitly and restore its execute bit before promoting the prefix.
        val finalPrefixPath = finalPrefix.toPath().toAbsolutePath().normalize()
        listOf("bin/sh", "bin/bash").map { File(root, it) }
            .filter { java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .forEach { link ->
                // Manifest links point at the final prefix; resolve their
                // target back into the extracted runtime tree for permission checks.
                val rawTarget = java.nio.file.Files.readSymbolicLink(link.toPath())
                val resolvedTarget = if (rawTarget.isAbsolute) {
                    rawTarget.toAbsolutePath().normalize()
                } else {
                    link.toPath().parent.resolve(rawTarget).toAbsolutePath().normalize()
                }
                val resolvedExecutable = if (resolvedTarget.startsWith(finalPrefixPath)) {
                    val relative = finalPrefixPath.relativize(resolvedTarget)
                    root.toPath().resolve(relative).normalize().toFile()
                } else {
                    resolvedTarget.toFile()
                }
                if (!resolvedExecutable.isFile) {
                    throw IOException("Bootstrap shell target is missing: ${link.relativeTo(root)} -> $resolvedExecutable")
                }
                try { Os.chmod(resolvedExecutable.absolutePath, 0x1C0) } catch (error: Exception) { throw IOException("Unable to chmod shell target ${link.relativeTo(root)} -> $resolvedExecutable: ${error.message}", error) }
                if (!resolvedExecutable.canExecute()) throw IOException("Shell target is not executable after chmod: ${link.relativeTo(root)} -> $resolvedExecutable")
            }
        listOf("bin/sh", "bin/bash").forEach { shell ->
            val candidate = File(root, shell)
            if (candidate.exists()) {
                val target = try { candidate.canonicalFile } catch (_: Exception) { candidate }
                if (!candidate.canExecute() || !target.canExecute()) throw IOException("Bootstrap shell lacks execute permission: $shell (link=${candidate.canExecute()}, target=${target.canExecute()}, targetPath=$target)")
            }
        }
    }
    /**
     * Validate canonical Termux command aliases after replaying SYMLINKS.txt.
     * Termux bootstrap normally exposes bin/sh as a symlink to dash.
     */
    private fun validateTermuxSymlinkLayout(root: File, finalPrefixDirectory: File, symlinkCount: Int) {
        if (symlinkCount == 0) {
            throw IOException("SYMLINKS.txt contained no symlinks; bootstrap extraction is incomplete")
        }
        val shell = File(root, "bin/sh")
        if (!java.nio.file.Files.isSymbolicLink(shell.toPath())) {
            throw IOException("Termux bootstrap expected bin/sh symlink from SYMLINKS.txt, but it was not created")
        }
        val rawTarget = java.nio.file.Files.readSymbolicLink(shell.toPath())
        val finalPrefix = finalPrefixDirectory.toPath().toAbsolutePath().normalize()
        val targetPath = if (rawTarget.isAbsolute) rawTarget.toAbsolutePath().normalize()
            else shell.toPath().parent.resolve(rawTarget).toAbsolutePath().normalize()
        val resolvedExecutable = if (targetPath.startsWith(finalPrefix)) {
            root.toPath().resolve(finalPrefix.relativize(targetPath)).normalize().toFile()
        } else targetPath.toFile()
        if (!resolvedExecutable.isFile || !resolvedExecutable.canExecute()) {
            throw IOException("Termux shell symlink target is missing or not executable: bin/sh -> $resolvedExecutable")
        }
        listOf("bin/bash", "bin/ls").forEach { alias ->
            val candidate = File(root, alias)
            if (!candidate.exists() && !java.nio.file.Files.isSymbolicLink(candidate.toPath())) {
                throw IOException("Termux SYMLINKS.txt alias was not installed: $alias")
            }
        }
    }

    private fun validateBootstrapLayout(root: File, requireShell: Boolean) {
        val required = listOf("bin", "etc", "lib", "share")
        required.forEach { name ->
            if (!File(root, name).exists()) throw IOException("Bootstrap is missing required path: $name")
        }
        if (requireShell && !File(root, "bin/sh").exists() && !File(root, "bin/bash").exists()) {
            throw IOException("Bootstrap does not contain a shell entry point after symlink processing")
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
        const val DEFAULT_BOOTSTRAP_PREFIX = "/data/data/com.techwithkaushik.cppide/files/usr"
    }
}
