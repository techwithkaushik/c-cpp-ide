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
    private val installer: BootstrapInstaller = BootstrapInstaller(),
) {
    val layout: RuntimeLayout = RuntimeLayout.fromAppFilesDirectory(appFilesDirectory)

    fun installBootstrap(archive: File, expectedSha256: String): BootstrapInstaller.Result {
        layout.ensurePrivateDirectories()
        val prefix = layout.prefix.canonicalFile
        val privateRoot = layout.rootDirectory.canonicalFile
        if (!prefix.toPath().startsWith(privateRoot.toPath())) {
            throw SecurityException("Bootstrap prefix escapes app-private runtime directory")
        }
        return installer.install(archive, expectedSha256, prefix)
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
