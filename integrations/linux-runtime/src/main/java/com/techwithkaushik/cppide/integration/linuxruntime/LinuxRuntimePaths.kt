package com.techwithkaushik.cppide.integration.linuxruntime

import android.os.Process
import java.io.File

/** App-private Linux runtime paths. Never accesses a separately installed Termux environment. */
class LinuxRuntimePaths(filesDir: File) {
    val base = File(filesDir, "linux-runtime")
    val distributions = File(base, "distributions")
    val ubuntuRoot = File(distributions, "ubuntu")
    val bin = File(base, "bin")
    val proot = File(bin, "proot")
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

    fun hasProot(): Boolean = proot.isFile && proot.canRead()
    fun ready(): Boolean = hasRootfs() && hasProot() && systemLinker.isFile
}
