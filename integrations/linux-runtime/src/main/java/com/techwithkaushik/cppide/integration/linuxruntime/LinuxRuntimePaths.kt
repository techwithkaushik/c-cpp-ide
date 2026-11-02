package com.techwithkaushik.cppide.integration.linuxruntime

import android.os.Process
import java.io.File

/** App-private Linux rootfs paths and read-only APK-bundled Android host binaries. */
class LinuxRuntimePaths(filesDir: File, bundledNativeLibraryDir: String? = null) {
    val base = File(filesDir, "linux-runtime")
    val distributions = File(base, "distributions")
    val ubuntuRoot = File(distributions, "ubuntu")
    val bin = File(base, "bin")
    val binProot = File(bin, "proot")
    val binProotLoader = File(bin, "loader")

    /** APK-extracted nativeLibraryDir is executable on Android 10+; app data is not. */
    // Use Android's authoritative native-library directory. /proc/self/maps is
    // not reliable here: the JNI library may not yet be loaded, and its filename
    // can vary by build configuration.
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
    val proot: File get() = nativeLibraryDir?.let { File(it, "libproot.so") } ?: binProot
    val prootLoader: File get() = nativeLibraryDir?.let { File(it, "libproot-loader.so") } ?: binProotLoader
    val marker = File(base, "runtime.properties")
    val staging = File(base, "staging")
    val systemLinker: File get() = File(if (Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker")

    fun ensureDirectories() {
        listOf(base, distributions, bin, staging).forEach {
            if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Cannot create app-private runtime directory: $it")
            if (!it.isDirectory) throw IllegalStateException("Runtime path is not a directory: $it")
        }
        // Keep the expected bin/proot and bin/loader paths available for UI,
        // scripts and older callers, but point them at APK-extracted native
        // binaries. Never copy executable binaries into writable app data:
        // Android 10+ W^X policy can prevent launching those copies.
        nativeLibraryDir?.let { nativeDir ->
            linkNativeBinary(binProot, File(nativeDir, "libproot.so"))
            linkNativeBinary(binProotLoader, File(nativeDir, "libproot-loader.so"))
        }
    }

    private fun linkNativeBinary(alias: File, target: File) {
        if (!target.isFile) return
        if (alias.exists() || java.nio.file.Files.isSymbolicLink(alias.toPath())) {
            val currentTarget = runCatching { alias.canonicalFile }.getOrNull()
            if (currentTarget == target.canonicalFile) return
            if (!alias.delete()) {
                throw IllegalStateException("Cannot replace stale runtime binary alias: $alias")
            }
        }
        try {
            java.nio.file.Files.createSymbolicLink(alias.toPath(), target.toPath())
        } catch (e: Exception) {
            throw IllegalStateException("Cannot create runtime binary alias $alias -> $target", e)
        }
    }

    fun hasRootfs(): Boolean {
        // Rootfs installed by the previous extractor may contain corrupted files
        // where /lib and /usr/lib alias one another. Require the current installer
        // marker so old rootfs images are reinstalled instead of silently reused.
        val currentInstaller = runCatching {
            marker.readLines().any { it.trim() == "installerVersion=4" }
        }.getOrDefault(false)
        return currentInstaller && File(ubuntuRoot, "etc/os-release").isFile &&
            (File(ubuntuRoot, "bin/sh").exists() || File(ubuntuRoot, "usr/bin/sh").exists())
    }

    fun hasProot(): Boolean = nativeLibraryDir != null && proot.isFile && prootLoader.isFile
    fun ready(): Boolean = hasRootfs() && hasProot()
}