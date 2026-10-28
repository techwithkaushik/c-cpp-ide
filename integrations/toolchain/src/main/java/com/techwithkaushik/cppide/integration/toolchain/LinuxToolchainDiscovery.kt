package com.techwithkaushik.cppide.integration.toolchain

import java.io.File

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
 * conventional locations for manually extracted toolchains. Paths returned
 * by this class are absolute paths in the host-visible rootfs tree; callers
 * must translate them to rootfs paths when invoking the Linux runtime.
 */
class LinuxToolchainDiscovery {
    private val recommended = listOf("sh", "clang", "clang++", "cmake", "make", "gdb")

    fun discover(rootfs: File, environmentPath: String? = null): LinuxToolchainInventory {
        require(rootfs.isDirectory) { "Root filesystem directory does not exist" }
        val canonicalRoot = rootfs.canonicalFile
        val pathEntries = (environmentPath ?: "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            .split(':')
            .filter { it.isNotBlank() }
            .distinct()

        val discovered = linkedMapOf<String, LinuxTool>()
        val aliases = mapOf("cc" to "cc", "c++" to "c++")

        for (name in recommended + aliases.keys) {
            val candidates = mutableListOf<Pair<File, LinuxTool.Source>>()
            pathEntries.forEachIndexed { index, entry ->
                val relative = entry.removePrefix("/")
                val dir = File(canonicalRoot, relative)
                candidates += File(dir, name) to if (index == 0 && entry.startsWith("/usr/local")) {
                    LinuxTool.Source.MANUAL_PATH
                } else {
                    LinuxTool.Source.DISTRO_PATH
                }
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
                val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (!canonical.toPath().startsWith(canonicalRoot.toPath())) return@firstNotNullOfOrNull null
                if (canonical.isFile && (canonical.canExecute() || name == "sh")) {
                    LinuxTool(name, canonical.absolutePath, source)
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
}
