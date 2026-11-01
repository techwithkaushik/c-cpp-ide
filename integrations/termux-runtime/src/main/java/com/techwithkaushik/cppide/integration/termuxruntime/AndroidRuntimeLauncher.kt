package com.techwithkaushik.cppide.integration.termuxruntime

import android.os.Process
import java.io.File

/**
 * Android 10+ denies direct execve() of executables extracted into app-private files
 * when the app targets API 29+. Launch the system dynamic linker instead, which can
 * load the Termux ELF executable from the private prefix.
 */
object AndroidRuntimeLauncher {
    val systemLinker: File
        get() = File(if (Process.is64Bit()) "/system/bin/linker64" else "/system/bin/linker")

    fun command(shell: File, vararg arguments: String): Array<String> =
        arrayOf(systemLinker.absolutePath, shell.absolutePath, *arguments)
}
