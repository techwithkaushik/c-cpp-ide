package com.techwithkaushik.cppide.integration.termuxruntime

import java.io.File
import java.io.IOException

/**
 * Canonical locations and environment for the embedded runtime.
 *
 * The prefix is intentionally inside this application's private files directory:
 * it must not be redirected to shared/external storage or another app's directory.
 * The compiled bootstrap prefix must match [prefix].
 */
data class RuntimeLayout private constructor(
    val appFilesDirectory: File,
    val rootDirectory: File,
    val prefix: File,
    val home: File,
    val temporaryDirectory: File,
) {
    val bin: File get() = File(prefix, "bin")
    val shell: File get() = File(bin, "sh")

    fun ensurePrivateDirectories() {
        ensureDirectory(appFilesDirectory)
        ensureDirectory(rootDirectory)
        ensureDirectory(home)
        ensureDirectory(temporaryDirectory)
        requirePrivateChild(prefix, rootDirectory)
        requirePrivateChild(home, rootDirectory)
        requirePrivateChild(temporaryDirectory, rootDirectory)
    }

    /**
     * Environment suitable for a future native execution adapter. This only
     * constructs environment values; it does not claim that binaries can execute.
     */
    fun environment(
        inherited: Map<String, String> = emptyMap(),
        extraPathEntries: List<File> = emptyList(),
    ): Map<String, String> {
        val pathEntries = (listOf(bin) + extraPathEntries).map { it.absolutePath }
        return inherited.toMutableMap().apply {
            put("PREFIX", prefix.absolutePath)
            put("TERMUX_PREFIX", prefix.absolutePath)
            put("HOME", home.absolutePath)
            put("TMPDIR", temporaryDirectory.absolutePath)
            put("SHELL", inherited["SHELL"] ?: shell.absolutePath)

            // App-specific bootstrap binaries use their own RUNPATH. Stock
            // Termux ZIPs contain ELF RUNPATHs pointing at com.termux, so add
            // this app's lib directory only for that imported bootstrap type.
            // This keeps the default app bootstrap on Termux's normal path.
            val sourceMarker = File(prefix, ".ide-bootstrap-source")
            if (sourceMarker.isFile && sourceMarker.readText(Charsets.UTF_8).trim() == "stock-termux") {
                put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
            } else {
                remove("LD_LIBRARY_PATH")
            }

            // Android 10+ blocks direct execve() of writable app-data files for
            // targetSdk >= 29. The initial shell must use the system linker;
            // termux-exec then handles child execs and /bin, /usr/bin paths.
            val termuxExec = File(prefix, "lib/libtermux-exec.so")
            if (termuxExec.isFile) {
                put("LD_PRELOAD", termuxExec.absolutePath)
            } else {
                remove("LD_PRELOAD")
            }

            put("PATH", (pathEntries + inherited["PATH"].orEmpty().split(File.pathSeparator)
                .filter { it.isNotBlank() }).distinct().joinToString(File.pathSeparator))
            put("LANG", inherited["LANG"] ?: "C.UTF-8")
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create runtime directory: $directory")
        }
        if (!directory.isDirectory) throw IOException("Runtime path is not a directory: $directory")
    }

    private fun requirePrivateChild(child: File, parent: File) {
        val parentPath = parent.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        if (!childPath.startsWith(parentPath) || childPath == parentPath) {
            throw SecurityException("Runtime path must remain inside app-private storage")
        }
    }

    companion object {
        fun fromAppFilesDirectory(filesDirectory: File): RuntimeLayout {
            val appFiles = filesDirectory.canonicalFile
            val root = appFiles
            val layout = RuntimeLayout(
                appFilesDirectory = appFiles,
                rootDirectory = root,
                prefix = File(root, "usr"),
                home = File(root, "home"),
                temporaryDirectory = File(File(root, "usr"), "tmp"),
            )
            layout.requirePrivateChild(layout.prefix, root)
            layout.requirePrivateChild(layout.home, root)
            layout.requirePrivateChild(layout.temporaryDirectory, layout.prefix)
            return layout
        }
    }
}

/**
 * Small facade for application code. The caller supplies a bootstrap archive
 * obtained from a trusted, pinned source and its independently verified digest.
 * Downloading, UI state, and binary execution are deliberately separate concerns.
 */
class EmbeddedTermuxRuntime(
    appFilesDirectory: File,
    private val installer: BootstrapInstaller = BootstrapInstaller(requireSymlinkManifest = true),
) {
    val layout: RuntimeLayout = RuntimeLayout.fromAppFilesDirectory(appFilesDirectory)

    fun installBootstrap(
        archive: File,
        expectedSha256: String? = null,
        processSymlinks: Boolean = true,
        validateInstalledPrefix: ((File) -> Boolean)? = null,
    ): BootstrapInstaller.Result {
        layout.ensurePrivateDirectories()
        val prefix = layout.prefix.canonicalFile
        val privateRoot = layout.rootDirectory.canonicalFile
        if (!prefix.toPath().startsWith(privateRoot.toPath())) {
            throw SecurityException("Bootstrap prefix escapes app-private runtime directory")
        }
        val result = installer.install(
            archive = archive,
            expectedSha256 = expectedSha256,
            prefixDirectory = prefix,
            processSymlinks = processSymlinks,
            validateInstalledPrefix = validateInstalledPrefix,
        )
        // Installation extracts directly into files/usr, replacing the previous
        // prefix only transactionally; recreate private directories such as PREFIX/tmp.
        layout.ensurePrivateDirectories()
        return result
    }

    /**
     * Repair absolute paths in an existing imported Termux bootstrap in place.
     * This does not download, rebuild, or reinstall any bootstrap files.
     */
    fun repairInstalledRuntimePaths() {
        installer.repairInstalledRuntimePaths(layout.prefix)
        layout.ensurePrivateDirectories()
        ensureSupplementaryGroupNames()
    }

    /**
     * Android injects supplementary host GIDs into the app process. Inside PRoot,
     * Ubuntu's groups(1) resolves those IDs against /etc/group and warns when a
     * host GID has no entry. Add stable names for common Android GIDs without
     * changing their numeric IDs or touching Ubuntu's core packages.
     */
    private fun ensureSupplementaryGroupNames() {
        val groupFile = File(layout.prefix, "etc/group")
        if (!groupFile.isFile || !groupFile.canWrite()) return

        val androidGroups = mapOf(
            3003 to "android_inet",
            9997 to "android_everybody",
            20490 to "android_shared",
            50490 to "android_user"
        )
        val existingLines = groupFile.readLines(Charsets.UTF_8)
        val existingGids = existingLines.mapNotNull { line ->
            line.split(':').getOrNull(2)?.toIntOrNull()
        }.toSet()
        val missing = androidGroups.filterKeys { it !in existingGids }
        if (missing.isEmpty()) return

        groupFile.appendText(
            missing.entries.joinToString(separator = "", prefix = if (existingLines.isEmpty()) "" else "\n") {
                "${it.value}:x:${it.key}:\n"
            },
            Charsets.UTF_8
        )
    }

    /** Structural check only; a real executable smoke test still has to run. */
    fun hasBootstrapLayout(): Boolean {
        val prefix = layout.prefix
        return File(prefix, "bin").isDirectory &&
            File(prefix, "etc").isDirectory &&
            File(prefix, "lib").exists() &&
            File(prefix, "share").exists() &&
            (File(prefix, "bin/sh").exists() || File(prefix, "bin/bash").exists())
    }
}
