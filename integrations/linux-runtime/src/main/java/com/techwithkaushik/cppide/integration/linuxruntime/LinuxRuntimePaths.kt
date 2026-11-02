package com.techwithkaushik.cppide.integration.linuxruntime

import android.os.Process
import java.io.File

/** App-private Linux rootfs paths and read-only APK-bundled Android host binaries. */
class LinuxRuntimePaths(filesDir: File) {
    val base = File(filesDir, "linux-runtime")
    val distributions = File(base, "distributions")
    val ubuntuRoot = File(distributions, "ubuntu")
    val bin = File(base, "bin")

    /** APK-extracted nativeLibraryDir is executable on Android 10+; app data is not. */
    val nativeLibraryDir: File? by lazy {
        runCatching {
            File("/proc/self/maps").useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    val path = line.substringAfterLast(' ', "")
                    if (path.endsWith("/libcppide_native.so")) File(path).parentFile else null
                }
            }
        }.getOrNull()
    }
    val proot: File get() = nativeLibraryDir?.let { File(it, "libproot.so") } ?: File(bin, "proot")
    val prootLoader: File get() = nativeLibraryDir?.let { File(it, "libproot-loader.so") } ?: File(bin, "loader")
    val marker = File(base, "runtime.properties")
    val staging = File(base, "staging")
    val systemLinker: File get() = File(if (Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker")

    fun ensureDirectories() {
        listOf(base, distributions, bin, staging).forEach {
            if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Cannot create app-private runtime directory: $it")
            if (!it.isDirectory) throw IllegalStateException("Runtime path is not a directory: $it")
        }
    }

    fun hasRootfs(): Boolean = File(ubuntuRoot, "etc/os-release").isFile &&
        (File(ubuntuRoot, "bin/sh").exists() || File(ubuntuRoot, "usr/bin/sh").exists())

    fun hasProot(): Boolean = nativeLibraryDir != null && proot.isFile && prootLoader.isFile
    fun ready(): Boolean = hasRootfs() && hasProot() && systemLinker.isFile
}