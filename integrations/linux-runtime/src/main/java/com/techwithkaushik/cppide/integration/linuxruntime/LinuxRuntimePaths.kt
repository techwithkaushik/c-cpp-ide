package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.nio.file.Files

/** Simple app-private paths for the extracted distro and bundled Android PRoot. */
class LinuxRuntimePaths(filesDir: File, bundledNativeLibraryDir: String? = null) {
    val base = File(filesDir, "linux-runtime")
    val distro = File(filesDir, "distro")
    /** Backward-compatible name for the currently downloaded Ubuntu distro. */
    val ubuntuRoot: File get() = distro
    val usrBin = File(filesDir, "usr/bin")
    val proot = File(usrBin, "proot")
    val prootLoader = File(usrBin, "libproot-loader.so")
    val marker = File(base, "runtime.properties")
    val staging = File(base, "staging")

    /** Android-native executables must resolve to the APK's executable nativeLibraryDir. */
    val nativeLibraryDir: File? by lazy {
        bundledNativeLibraryDir?.takeIf { it.isNotBlank() }?.let(::File)
            ?: runCatching {
                File("/proc/self/maps").useLines { lines ->
                    lines.firstNotNullOfOrNull { line ->
                        val path = line.substringAfterLast(' ', "")
                        if (path.endsWith(".so") && path.contains("/lib/")) File(path).parentFile else null
                    }
                }
            }.getOrNull()
    }

    fun ensureDirectories() {
        listOf(base, distro, usrBin, staging).forEach {
            if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Cannot create runtime directory: $it")
            if (!it.isDirectory) throw IllegalStateException("Runtime path is not a directory: $it")
        }
        val nativeDir = nativeLibraryDir ?: return
        linkNativeBinary(proot, File(nativeDir, "libproot.so"))
        linkNativeBinary(prootLoader, File(nativeDir, "libproot-loader.so"))
    }

    private fun linkNativeBinary(alias: File, target: File) {
        if (!target.isFile) return
        if (Files.isSymbolicLink(alias.toPath())) {
            if (runCatching { alias.canonicalFile == target.canonicalFile }.getOrDefault(false)) return
            Files.delete(alias.toPath())
        } else if (alias.exists()) {
            if (alias.canonicalFile == target.canonicalFile) return
            if (!alias.delete()) throw IllegalStateException("Cannot replace runtime binary: $alias")
        }
        try {
            Files.createSymbolicLink(alias.toPath(), target.toPath())
        } catch (e: Exception) {
            throw IllegalStateException("Cannot link $alias to APK-bundled binary $target", e)
        }
    }

    fun hasRootfs(): Boolean {
        val currentInstaller = runCatching {
            marker.readLines().any { it.trim() == "installerVersion=5" }
        }.getOrDefault(false)
        return currentInstaller && File(ubuntuRoot, "etc/os-release").isFile &&
            (File(ubuntuRoot, "bin/sh").exists() || File(ubuntuRoot, "usr/bin/sh").exists())
    }

    fun hasProot(): Boolean = proot.isFile && prootLoader.isFile
    fun ready(): Boolean = hasRootfs() && hasProot()
}
