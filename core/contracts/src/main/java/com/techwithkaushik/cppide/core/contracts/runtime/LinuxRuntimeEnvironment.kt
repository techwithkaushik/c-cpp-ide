package com.techwithkaushik.cppide.core.contracts.runtime

import java.io.File

/**
 * Readiness and filesystem boundary consumed by language services.
 *
 * This interface describes an already configured Linux execution environment;
 * it deliberately does not expose installers, downloaders, Compose, or Android UI.
 */
interface LinuxRuntimeEnvironment {
    val ubuntuRoot: File
    val proot: File
    val prootLoader: File
    val usrTmp: File
    val nativeLibraryDir: File?

    fun ensureDirectories()
    fun hasRootfs(): Boolean
    fun ready(): Boolean
}

/** Resolves Linux guest tools without exposing the concrete discovery implementation. */
interface LinuxToolchainAccess {
    fun findExecutable(rootfs: File, name: String, environmentPath: String? = null): File?
    fun resolveExecutable(rootfs: File, guestPath: String): File?
    fun toGuestPath(rootfs: File, executable: File): String?
}
