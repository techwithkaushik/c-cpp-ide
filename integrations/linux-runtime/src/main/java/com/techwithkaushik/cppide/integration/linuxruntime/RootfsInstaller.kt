package com.techwithkaushik.cppide.integration.linuxruntime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** Secure, transactional importer for Ubuntu .tar, .tar.gz and .tar.xz rootfs archives. */
class RootfsInstaller(private val paths: LinuxRuntimePaths) {
    data class Result(val root: File, val entries: Int, val expandedBytes: Long)

    fun install(archive: File, expectedSha256: String? = null): Result {
        require(archive.isFile && archive.length() > 0) { "Rootfs archive is missing or empty." }
        if (expectedSha256 != null) {
            require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256 value." }
            require(sha256(archive).equals(expectedSha256, ignoreCase = true)) { "Rootfs SHA-256 mismatch." }
        }
        paths.ensureDirectories()
        val stage = File(paths.staging, "ubuntu-${System.currentTimeMillis()}")
        if (!stage.mkdirs()) throw IOException("Cannot create rootfs staging directory.")
        var count = 0
        var expanded = 0L
        try {
            val raw = BufferedInputStream(FileInputStream(archive))
            val lower = archive.name.lowercase(Locale.ROOT)
            val decoded = when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> GzipCompressorInputStream(raw)
                lower.endsWith(".tar.xz") -> XZCompressorInputStream(raw)
                lower.endsWith(".tar") -> raw
                else -> throw IOException("Unsupported rootfs format. Choose .tar, .tar.gz, or .tar.xz.")
            }
            TarArchiveInputStream(decoded).use { tar ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) throw IOException("Rootfs contains too many entries.")
                    val target = safeTarget(stage, entry.name)
                    when {
                        entry.isDirectory -> if (!target.exists() && !target.mkdirs()) throw IOException("Cannot create directory: ${entry.name}")
                        entry.isSymbolicLink -> createSafeSymlink(stage, target, entry.linkName)
                        entry.isLink -> {
                            val linked = safeTarget(stage, entry.linkName)
                            if (!linked.isFile) throw IOException("Invalid hard link: ${entry.name}")
                            target.parentFile?.mkdirs()
                            Files.copy(linked.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        entry.isFile -> {
                            if (entry.size < 0 || entry.size > MAX_EXPANDED_BYTES - expanded) throw IOException("Rootfs exceeds the 4 GiB expanded-size limit.")
                            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create parent directory.") }
                            target.outputStream().buffered().use { out ->
                                var copied = 0L
                                while (true) {
                                    val n = tar.read(buffer)
                                    if (n < 0) break
                                    copied += n
                                    expanded += n
                                    if (expanded > MAX_EXPANDED_BYTES) throw IOException("Rootfs exceeds the 4 GiB expanded-size limit.")
                                    out.write(buffer, 0, n)
                                }
                                if (copied != entry.size) throw IOException("Truncated rootfs entry: ${entry.name}")
                            }
                            target.setReadable(true, false)
                            target.setWritable(entry.mode and 0x80 != 0, true)
                            if (entry.mode and 0x49 != 0) target.setExecutable(true, false)
                        }
                    }
                }
            }
            val osRelease = File(stage, "etc/os-release")
            if (!osRelease.isFile) throw IOException("Not a Linux rootfs: etc/os-release is missing.")
            if (!osRelease.readText().lowercase(Locale.ROOT).contains("ubuntu")) {
                throw IOException("This installer currently activates Ubuntu rootfs only.")
            }
            if (!File(stage, "bin/sh").exists() && !File(stage, "usr/bin/sh").exists()) {
                throw IOException("Ubuntu rootfs does not contain /bin/sh or /usr/bin/sh.")
            }
            // Preserve the resolver exactly as provided by the Ubuntu rootfs; do not rewrite it.
            ensureAndroidGroups(File(stage, "etc/group"))
            val aptSourcesDir = File(stage, "etc/apt/sources.list.d")
            if (!aptSourcesDir.exists() && !aptSourcesDir.mkdirs()) throw IOException("Cannot create Ubuntu APT sources directory.")
            File(aptSourcesDir, "ubuntu.sources").writeText(
                "Types: deb\n" +
                    "URIs: https://ports.ubuntu.com/ubuntu-ports\n" +
                    "Suites: noble noble-updates noble-backports noble-security\n" +
                    "Components: main universe restricted multiverse\n" +
                    "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
            )
            val old = File(paths.staging, "ubuntu-previous")
            if (old.exists()) old.deleteRecursively()
            if (paths.ubuntuRoot.exists() && !paths.ubuntuRoot.renameTo(old)) throw IOException("Cannot back up current Ubuntu rootfs.")
            if (!stage.renameTo(paths.ubuntuRoot)) {
                if (old.exists()) old.renameTo(paths.ubuntuRoot)
                throw IOException("Cannot activate the new Ubuntu rootfs.")
            }
            old.deleteRecursively()
            paths.marker.writeText("distro=ubuntu\nroot=${paths.ubuntuRoot.absolutePath}\nentries=$count\nexpandedBytes=$expanded\n")
            return Result(paths.ubuntuRoot, count, expanded)
        } catch (error: Exception) {
            stage.deleteRecursively()
            throw error
        }
    }

    /** Add Android-compatible group names/IDs to Ubuntu's group database.
     * This does not grant host kernel supplementary-group privileges; Android
     * assigns those to the app process. */
    private fun ensureAndroidGroups(groupFile: File) {
        if (!groupFile.isFile) throw IOException("Ubuntu /etc/group is missing.")
        val existing = groupFile.readLines()
            .mapNotNull { it.substringBefore(':').takeIf(String::isNotBlank) }
            .toMutableSet()
        val androidGroups = listOf(
            "system" to 1000, "radio" to 1001, "bluetooth" to 1002,
            "graphics" to 1003, "input" to 1004, "audio" to 1005,
            "camera" to 1006, "log" to 1007, "adb" to 1011,
            "sdcard_rw" to 1015, "media_rw" to 1023, "sdcard_r" to 1028,
            "dns" to 1051, "network_stack" to 1073, "net_bt_admin" to 3001,
            "net_bt" to 3002, "inet" to 3003, "net_raw" to 3004,
            "net_admin" to 3005, "net_bw_stats" to 3006, "net_bw_acct" to 3007,
            "readproc" to 3009, "wakelock" to 3010, "everybody" to 9997
        )
        val missing = androidGroups.filter { (name, _) -> name !in existing }
        if (missing.isNotEmpty()) {
            groupFile.appendText(
                missing.joinToString(separator = "", prefix = if (groupFile.length() > 0L) "\n" else "") {
                    "${it.first}:x:${it.second}:\n"
                }
            )
        }
    }

    private fun safeTarget(root: File, name: String): File {
        // Keep archive paths lexical. canonicalFile follows existing symlinks,
        // which is unsafe while extracting usr-merged rootfs archives: /lib may
        // already point to /usr/lib, and canonicalizing later /lib entries can
        // overwrite the wrong target or collapse distinct archive entries.
        if (name.startsWith("/") || name.split('/').any { it == ".." }) {
            throw IOException("Unsafe rootfs path: $name")
        }
        val rootPath = root.absoluteFile.toPath().normalize()
        val target = rootPath.resolve(name).normalize()
        if (!target.startsWith(rootPath)) throw IOException("Path escapes rootfs: $name")
        return target.toFile()
    }

    private fun createSafeSymlink(root: File, link: File, rawTarget: String) {
        if (rawTarget.isBlank()) throw IOException("Empty symlink target in rootfs.")
        val rootPath = root.canonicalFile.toPath()
        val linkPath = link.absoluteFile.toPath().normalize()
        val resolved = if (rawTarget.startsWith("/")) rootPath.resolve(rawTarget.removePrefix("/")).normalize()
            else linkPath.parent.resolve(rawTarget).normalize()
        if (!resolved.startsWith(rootPath)) throw IOException("Symlink escapes rootfs: ${link.relativeTo(root)} -> $rawTarget")
        val relativeTarget: Path = linkPath.parent.relativize(resolved)
        link.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Cannot create symlink parent.") }
        if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()
        Files.createSymbolicLink(link.toPath(), relativeTarget)
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
        private const val MAX_ENTRIES = 500_000
        private const val MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024
    }
}
