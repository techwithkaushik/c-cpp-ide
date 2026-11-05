package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.nio.file.Files

/** Simple app-private paths for the extracted distro and bundled Android PRoot. */
class LinuxRuntimePaths(filesDir: File, bundledNativeLibraryDir: String? = null) {
    val base = filesDir
    val distro = File(filesDir, "rootfs")
    val ubuntuRoot: File get() = distro
    val usr = File(filesDir, "usr")
    val usrTmp = File(usr, "tmp")
    val usrBin = File(usr, "bin")
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
        listOf(base, distro, usrTmp, usrBin, staging).forEach {
            if (!it.exists() && !it.mkdirs()) throw IllegalStateException("Cannot create runtime directory: $it")
            if (!it.isDirectory) throw IllegalStateException("Runtime path is not a directory: $it")
        }
        normalizeGuestTemporaryDirectories()
        val nativeDir = nativeLibraryDir ?: return
        linkNativeBinary(proot, File(nativeDir, "libproot.so"))
        linkNativeBinary(prootLoader, File(nativeDir, "libproot-loader.so"))
    }

    /**
     * Older app releases extracted directories with the host's default 0755 mode.
     * Ubuntu's apt sandbox runs as _apt and needs /tmp and /var/tmp to be writable
     * by non-root users. Repair these two guest paths in-place for existing installs.
     */
    private fun normalizeGuestTemporaryDirectories() {
        val root = runCatching { ubuntuRoot.canonicalFile }.getOrNull() ?: return
        listOf(File(ubuntuRoot, "tmp"), File(ubuntuRoot, "var/tmp")).forEach { directory ->
            if (!directory.isDirectory) return@forEach
            val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return@forEach
            if (!canonical.path.startsWith(root.path + File.separator) || !canonical.isDirectory) return@forEach
            val readable = canonical.setReadable(true, false)
            val writable = canonical.setWritable(true, false)
            val executable = canonical.setExecutable(true, false)
            if (!readable || !writable || !executable) {
                throw IllegalStateException("Cannot prepare Ubuntu temporary directory permissions.")
            }
            // Android supports chmod's sticky bit; reflection keeps local JVM tests
            // independent of android.jar while retaining standard /tmp semantics on-device.
            runCatching {
                val os = Class.forName("android.system.Os")
                os.getMethod("chmod", String::class.java, Int::class.javaPrimitiveType!!)
                    .invoke(null, canonical.absolutePath, 0x1ff or 0x200)
            }
        }
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

    /**
     * Returns a useful, non-secret explanation of the installed rootfs state.
     * Installer versions are layout metadata, not distro compatibility versions:
     * older successfully installed rootfs trees remain usable when their required
     * Ubuntu files are present.
     */
    fun rootfsDiagnostic(): String {
        val markerLines = runCatching { marker.takeIf(File::isFile)?.readLines()?.take(8).orEmpty() }
            .getOrDefault(emptyList())
        val installerVersion = markerLines.firstOrNull { it.startsWith("installerVersion=") }
            ?.substringAfter("=")?.toIntOrNull()
        val osRelease = File(ubuntuRoot, "etc/os-release")
        val binSh = File(ubuntuRoot, "bin/sh")
        val usrBinSh = File(ubuntuRoot, "usr/bin/sh")
        val structural = osRelease.isFile && (binSh.exists() || usrBinSh.exists())
        val versionSupported = installerVersion != null && installerVersion in 1..CURRENT_INSTALLER_VERSION
        return "rootfsPath=${ubuntuRoot.absolutePath}; rootfsExists=${ubuntuRoot.exists()}; " +
            "markerExists=${marker.isFile}; installerVersion=${installerVersion ?: "unknown"}; " +
            "supportedInstallerVersion=$versionSupported; osRelease=${osRelease.isFile}; " +
            "binSh=${binSh.exists()}; usrBinSh=${usrBinSh.exists()}; " +
            "rootfsValid=${versionSupported && structural}; marker=${markerLines.joinToString("|")}"
    }

    fun hasRootfs(): Boolean {
        val markerLines = runCatching { marker.takeIf(File::isFile)?.readLines()?.take(8).orEmpty() }
            .getOrDefault(emptyList())
        val installerVersion = markerLines.firstOrNull { it.startsWith("installerVersion=") }
            ?.substringAfter("=")?.toIntOrNull()
        val structural = File(ubuntuRoot, "etc/os-release").isFile &&
            (File(ubuntuRoot, "bin/sh").exists() || File(ubuntuRoot, "usr/bin/sh").exists())
        return installerVersion != null && installerVersion in 1..CURRENT_INSTALLER_VERSION && structural
    }

    fun hasProot(): Boolean = proot.isFile && prootLoader.isFile
    fun ready(): Boolean = hasRootfs() && hasProot()

    companion object {
        private const val CURRENT_INSTALLER_VERSION = 5
    }
}
