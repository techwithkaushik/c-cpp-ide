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

            // Match Termux's Android execution environment: do not export
            // PREFIX/lib through LD_LIBRARY_PATH on Android 7+, because it can
            // make the system linker load incompatible libraries. Termux ELF
            // binaries use their packaged RUNPATH for their shared libraries.
            remove("LD_LIBRARY_PATH")

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
