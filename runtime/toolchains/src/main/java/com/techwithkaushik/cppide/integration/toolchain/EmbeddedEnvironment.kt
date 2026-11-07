package com.techwithkaushik.cppide.integration.toolchain

import java.io.File

/**
 * Describes the app-owned shell/package/compiler environment.
 *
 * This contract deliberately has no dependency on the Termux app package, its RUN_COMMAND service,
 * or /data/data/com.termux paths. An installer must not construct a READY snapshot until the shell
 * and C17/C++20 compile-and-run health checks have succeeded.
 *
 * This is the environment boundary for the embedded distribution; it does not itself install
 * binaries. The bootstrap artifact and installer are tracked in the embedded environment migration
 * plan and must be implemented before this can be used for production builds.
 */
data class EmbeddedEnvironment(
    val version: String,
    val abi: String,
    val prefix: File,
    val home: File,
    val temp: File,
    val bin: File,
    val lib: File,
    val shell: File,
    val clang: File,
    val clangxx: File
) {
    fun validateLayout(): String? {
        if (version.isBlank()) return "Environment version is missing."
        if (abi.isBlank()) return "Environment ABI is missing."
        if (!prefix.isDirectory) return "Embedded environment prefix is missing."
        if (!home.isDirectory) return "Embedded HOME directory is missing."
        if (!temp.isDirectory) return "Embedded TMPDIR directory is missing."
        if (!bin.isDirectory) return "Embedded bin directory is missing."
        if (!lib.isDirectory) return "Embedded library directory is missing."
        if (!isExecutableInsidePrefix(shell)) return "Embedded shell is missing or not executable."
        if (!isExecutableInsidePrefix(clang)) return "Embedded clang is missing or not executable."
        if (!isExecutableInsidePrefix(clangxx)) return "Embedded clang++ is missing or not executable."
        return null
    }

    /**
     * Build an environment for a child process. The process launcher must still use an argument
     * array, a bounded output collector and a timeout; this map is not a shell command.
     */
    fun processEnvironment(): Map<String, String> = mapOf(
        "HOME" to home.absolutePath,
        "PREFIX" to prefix.absolutePath,
        "TMPDIR" to temp.absolutePath,
        "PATH" to bin.absolutePath,
        "LD_LIBRARY_PATH" to lib.absolutePath
    )

    private fun isExecutableInsidePrefix(file: File): Boolean {
        val prefixPath = prefix.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        return filePath.startsWith(prefixPath) && file.isFile && file.canExecute()
    }
}
