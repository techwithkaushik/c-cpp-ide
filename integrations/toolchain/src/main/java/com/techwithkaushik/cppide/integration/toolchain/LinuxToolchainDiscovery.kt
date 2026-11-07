package com.techwithkaushik.cppide.integration.toolchain

import com.techwithkaushik.cppide.core.contracts.runtime.LinuxToolchainAccess
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Discovers tools inside an installed Linux rootfs without assuming that they
 * were installed by apt. Tools may come from a distro package or a manually
 * extracted SDK/toolchain added to PATH.
 *
 * This class only resolves candidates; it does not execute binaries. Execution
 * must go through the validated Android Linux runtime (for example, proot).
 */
data class LinuxTool(
    val name: String,
    val executable: String,
    val source: Source
) {
    enum class Source { DISTRO_PATH, MANUAL_PATH, WELL_KNOWN_LOCATION }
}

data class LinuxToolchainInventory(
    val rootfsPath: String,
    val pathEntries: List<String>,
    val tools: Map<String, LinuxTool>,
    val missingRecommendedTools: List<String>
) {
    fun find(name: String): LinuxTool? = tools[name]
}

/**
 * Resolves known development commands using the rootfs PATH first, then
 * conventional locations for manually extracted toolchains.
 *
 * Paths are resolved using Linux rootfs symlink semantics. In particular, an
 * absolute symlink such as /usr/bin/clangd -> /usr/lib/llvm-XX/bin/clangd
 * must resolve below the rootfs, not against the Android host filesystem.
 */
class LinuxToolchainDiscovery : LinuxToolchainAccess {
    private val recommended = listOf(
        "sh", "clang", "clang++", "clangd", "clang-format", "clang-tidy",
        "lldb", "lldb-dap", "cmake", "make", "gdb"
    )

    fun discover(rootfs: File, environmentPath: String? = null): LinuxToolchainInventory {
        require(rootfs.isDirectory) { "Root filesystem directory does not exist" }
        val canonicalRoot = rootfs.canonicalFile
        val rootPath = canonicalRoot.toPath()
        val pathEntries = (environmentPath ?: "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            .split(':')
            .filter { it.isNotBlank() }
            .distinct()

        val discovered = linkedMapOf<String, LinuxTool>()
        val aliases = mapOf("cc" to "cc", "c++" to "c++")

        for (name in recommended + aliases.keys) {
            val candidates = mutableListOf<Pair<File, LinuxTool.Source>>()
            pathEntries.forEach { entry ->
                val relative = entry.removePrefix("/")
                val dir = File(canonicalRoot, relative)
                val source = if (entry.startsWith("/opt/") || entry.startsWith("/usr/local/") ||
                    entry.startsWith("/home/") || entry.startsWith("/root/")) {
                    LinuxTool.Source.MANUAL_PATH
                } else {
                    LinuxTool.Source.DISTRO_PATH
                }
                candidates += File(dir, name) to source
            }

            // Common manual SDK layouts: /opt/<sdk>/bin and /usr/local/<sdk>/bin.
            listOf("opt", "usr/local", "usr/lib").forEach { base ->
                val parent = File(canonicalRoot, base)
                if (parent.isDirectory) {
                    parent.listFiles()?.take(128)?.filter { it.isDirectory }?.forEach { child ->
                        candidates += File(File(child, "bin"), name) to LinuxTool.Source.MANUAL_PATH
                    }
                }
            }

            val found = candidates.firstNotNullOfOrNull { (candidate, source) ->
                val resolved = resolveInsideRootfs(rootPath, candidate.toPath()) ?: return@firstNotNullOfOrNull null
                val executable = resolved.toFile()
                if (executable.isFile && executable.canExecute()) {
                    LinuxTool(name, executable.absolutePath, source)
                } else null
            }
            if (found != null) discovered[name] = found
        }

        return LinuxToolchainInventory(
            rootfsPath = canonicalRoot.absolutePath,
            pathEntries = pathEntries,
            tools = discovered,
            missingRecommendedTools = recommended.filterNot(discovered::containsKey)
        )
    }

    /**
     * Resolve an absolute Linux guest executable path without following absolute
     * symlinks on the Android host. Returns the physical file below rootfs only.
     */
    override fun findExecutable(rootfs: File, name: String, environmentPath: String? = null): File? =
        discover(rootfs, environmentPath).find(name)?.executable?.let(::File)

    override fun resolveExecutable(rootfs: File, guestPath: String): File? {
        if (!guestPath.startsWith("/") || '\u0000' in guestPath) return null
        val root = rootfs.canonicalFile.toPath()
        val candidate = resolveInsideRootfs(root, root.resolve(guestPath.removePrefix("/")).normalize())
            ?: return null
        val executable = candidate.toFile()
        return executable.takeIf { it.isFile && it.canExecute() }
    }

    /**
     * Convert a physical rootfs file into its absolute Linux guest path.
     */
    override fun toGuestPath(rootfs: File, executable: File): String? {
        val root = rootfs.canonicalFile.toPath()
        val file = executable.toPath().toAbsolutePath().normalize()
        if (!file.startsWith(root)) return null
        return "/" + root.relativize(file).joinToString("/") { it.toString() }
    }

    /**
     * Resolve symlinks as they would be interpreted inside a chroot. Java's
     * canonicalFile follows absolute symlinks on the Android host, which makes
     * valid distro links appear to escape (or disappear outside) the rootfs.
     */
    private fun resolveInsideRootfs(root: Path, candidate: Path): Path? {
        var pending = candidate.toAbsolutePath().normalize()
        if (!pending.startsWith(root)) return null

        var linkCount = 0
        while (true) {
            val relative = root.relativize(pending)
            var current = root
            var restarted = false

            for (index in 0 until relative.nameCount) {
                current = current.resolve(relative.getName(index))
                if (!Files.isSymbolicLink(current)) continue

                if (++linkCount > MAX_SYMLINKS) return null
                val target = runCatching { Files.readSymbolicLink(current) }.getOrNull() ?: return null
                val targetInRoot = if (target.isAbsolute) {
                    root.resolve(target.toString().removePrefix("/"))
                } else {
                    current.parent.resolve(target)
                }
                val remaining = if (index + 1 < relative.nameCount) {
                    relative.subpath(index + 1, relative.nameCount)
                } else {
                    null
                }
                pending = (remaining?.let(targetInRoot::resolve) ?: targetInRoot).normalize()
                if (!pending.startsWith(root)) return null
                restarted = true
                break
            }

            if (!restarted) return pending
        }
    }

    private companion object {
        const val MAX_SYMLINKS = 40
    }
}
