package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.logging.DeveloperLog
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Extracts a tar archive without distro-specific edits. */
class RootfsInstaller(private val paths: LinuxRuntimePaths) {
    data class Result(val root: File, val entries: Int, val expandedBytes: Long)

    fun install(archive: File, expectedSha256: String? = null): Result {
        require(archive.isFile && archive.length() > 0) { "Rootfs archive is missing or empty." }
        if (expectedSha256 != null) {
            require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256 value." }
            require(sha256(archive).equals(expectedSha256, ignoreCase = true)) { "Rootfs SHA-256 mismatch." }
        }
        return install({ FileInputStream(archive) }, archive.name)
    }

    /** Extract directly from a reopenable source stream; archive bytes are never loaded into memory. */
    fun install(open: () -> InputStream, archiveName: String): Result {
        val startedAt = System.currentTimeMillis()
        DeveloperLog.info("UbuntuInstaller", "Rootfs extraction requested; archive=$archiveName; source=stream")
        paths.ensureDirectories()
        val stage = File(paths.staging, "distro-${System.currentTimeMillis()}")
        if (!stage.mkdirs()) throw IOException("Cannot create extraction directory.")
        var count = 0
        var expanded = 0L
        val directoryModes = mutableListOf<Pair<File, Int>>()
        var previousRoot: File? = null
        var activated = false
        try {
            val raw = BufferedInputStream(open())
            val decoded = RootfsArchitectureInspector.decoder(raw, archiveName)
            TarArchiveInputStream(decoded).use { tar ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Rootfs extraction cancelled.")
                    val entry = tar.nextEntry as? TarArchiveEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) throw IOException("Archive contains too many entries.")
                    val target = safeTarget(stage, entry.name)
                    when {
                        entry.isSymbolicLink -> createSafeSymlink(stage, target, entry.linkName)
                        entry.isDirectory -> {
                            removeEntrySymlink(target)
                            if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create directory: ${entry.name}")
                            if (!target.isDirectory) throw IOException("Directory conflicts with non-directory: ${entry.name}")
                            directoryModes += target to entry.mode
                        }
                        entry.isLink -> {
                            val linked = safeTarget(stage, entry.linkName)
                            if (!linked.isFile || Files.isSymbolicLink(linked.toPath())) throw IOException("Invalid hard link: ${entry.name}")
                            target.parentFile?.mkdirs()
                            removeEntrySymlink(target)
                            Files.copy(linked.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            applyMode(target, entry.mode)
                        }
                        entry.isFile -> {
                            if (entry.size < 0 || entry.size > MAX_EXPANDED_BYTES - expanded) throw IOException("Archive exceeds the 4 GiB expanded-size limit.")
                            removeEntrySymlink(target)
                            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create parent directory.") }
                            BufferedOutputStream(target.outputStream(), 256 * 1024).use { out ->
                                var copied = 0L
                                while (true) {
                                    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Rootfs extraction cancelled.")
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    copied += n
                                    expanded += n
                                    if (expanded > MAX_EXPANDED_BYTES) throw IOException("Archive exceeds the 4 GiB expanded-size limit.")
                                    out.write(buffer, 0, n)
                                }
                                if (copied != entry.size) throw IOException("Truncated archive entry: ${entry.name}")
                            }
                            applyMode(target, entry.mode)
                        }
                    }
                    if (count % 5000 == 0) DeveloperLog.debug("UbuntuInstaller", "Extraction progress; entries=$count; expandedBytes=$expanded")
                }
            }
            directoryModes.forEach { (directory, mode) -> applyMode(directory, mode) }
            requireValidUbuntuRootfs(stage)
            val old = File(paths.staging, "distro-previous")
            if (old.exists() && !old.deleteRecursively()) throw IOException("Cannot remove previous extraction backup.")
            if (paths.ubuntuRoot.exists()) {
                if (!paths.ubuntuRoot.renameTo(old)) throw IOException("Cannot replace existing distro directory.")
                previousRoot = old
            }
            if (!stage.renameTo(paths.ubuntuRoot)) throw IOException("Cannot activate extracted distro directory.")
            activated = true
            paths.marker.writeText("installerVersion=$INSTALLER_VERSION\nroot=${paths.ubuntuRoot.absolutePath}\nentries=$count\nexpandedBytes=$expanded\n")
            previousRoot?.deleteRecursively()
            previousRoot = null
            DeveloperLog.info("UbuntuInstaller", "Rootfs extraction completed; entries=$count; expandedBytes=$expanded; durationMs=${System.currentTimeMillis() - startedAt}")
            return Result(paths.ubuntuRoot, count, expanded)
        } catch (error: Exception) {
            DeveloperLog.error("UbuntuInstaller", "Rootfs extraction failed; entries=$count; expandedBytes=$expanded", error)
            stage.deleteRecursively()
            if (activated) paths.ubuntuRoot.deleteRecursively()
            previousRoot?.let { backup -> if (backup.exists()) backup.renameTo(paths.ubuntuRoot) }
            throw error
        }
    }

    private fun requireValidUbuntuRootfs(root: File) {
        val osRelease = File(root, "etc/os-release")
        val shell = listOf(File(root, "bin/sh"), File(root, "usr/bin/sh")).firstOrNull { it.isFile }
        if (!osRelease.isFile || shell == null) {
            throw IOException("Selected archive is not a complete Linux rootfs: /etc/os-release or /bin/sh is missing.")
        }
        if (!shell.canExecute()) {
            if (!shell.setExecutable(true, false) || !shell.canExecute()) {
                throw IOException("Ubuntu shell is not executable after extraction.")
            }
        }
    }

    private fun applyMode(target: File, archiveMode: Int) {
        val mode = archiveMode and 0x1ff
        val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
        if (mode and 0x100 != 0) permissions.add(PosixFilePermission.OWNER_READ)
        if (mode and 0x80 != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
        if (mode and 0x40 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
        if (mode and 0x20 != 0) permissions.add(PosixFilePermission.GROUP_READ)
        if (mode and 0x10 != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
        if (mode and 0x8 != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)
        if (mode and 0x4 != 0) permissions.add(PosixFilePermission.OTHERS_READ)
        if (mode and 0x2 != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
        if (mode and 0x1 != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)
        try { Files.setPosixFilePermissions(target.toPath(), permissions) }
        catch (error: Exception) { throw IOException("Cannot preserve Ubuntu permissions for ${target.name}.", error) }
    }

    private fun safeTarget(root: File, name: String): File {
        if (name.startsWith("/") || name.split('/').any { it == ".." }) throw IOException("Unsafe archive path: $name")
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
        val resolved = if (rawTarget.startsWith("/")) rootPath.resolve(rawTarget.removePrefix("/")).normalize()
            else parent.resolve(rawTarget).normalize()
        if (!resolved.startsWith(rootPath)) throw IOException("Symlink escapes extraction directory: $rawTarget")
        if (!parent.toFile().exists() && !parent.toFile().mkdirs()) throw IOException("Cannot create symlink parent.")
        if (Files.exists(linkPath) || Files.isSymbolicLink(linkPath)) Files.delete(linkPath)
        Files.createSymbolicLink(linkPath, java.nio.file.Paths.get(rawTarget))
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
