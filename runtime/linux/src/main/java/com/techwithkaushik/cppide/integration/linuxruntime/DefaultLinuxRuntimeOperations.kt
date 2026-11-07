package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.contracts.runtime.LinuxRuntimeEnvironment
import com.techwithkaushik.cppide.core.contracts.runtime.LinuxRuntimeOperations
import com.techwithkaushik.cppide.core.contracts.runtime.ProotValidationResult
import com.techwithkaushik.cppide.core.contracts.runtime.RootfsArchitectureReport
import com.techwithkaushik.cppide.core.contracts.runtime.RootfsInstallResult
import com.techwithkaushik.cppide.core.contracts.runtime.RuntimeSmokeResult
import com.techwithkaushik.cppide.core.contracts.runtime.UbuntuDownloadResult
import java.io.File
import java.io.InputStream

/** Concrete adapter that keeps all runtime provisioning implementations inside this module. */
class DefaultLinuxRuntimeOperations(
    filesDir: File,
    nativeLibraryDir: String?
) : LinuxRuntimeOperations {
    private val paths = LinuxRuntimePaths(filesDir, nativeLibraryDir)

    override val environment: LinuxRuntimeEnvironment
        get() = paths

    override fun inspectRootfs(open: () -> InputStream, archiveName: String): RootfsArchitectureReport {
        val report = RootfsArchitectureInspector.inspect(open, archiveName)
        return RootfsArchitectureReport(
            architecture = report.architecture,
            arm64Executables = report.arm64Executables,
            armhfExecutables = report.armhfExecutables,
            otherExecutables = report.otherExecutables
        )
    }

    override fun installRootfs(open: () -> InputStream, archiveName: String): RootfsInstallResult {
        val result = RootfsInstaller(paths).install(open, archiveName)
        return RootfsInstallResult(result.root, result.entries, result.expandedBytes)
    }

    override fun downloadAndInstallUbuntu(onProgress: (Long, Long) -> Unit): UbuntuDownloadResult {
        val result = UbuntuBaseDownloader(paths).downloadAndInstall(onProgress)
        return UbuntuDownloadResult(
            sha256 = result.sha256,
            archiveBytes = result.bytes,
            rootfsEntries = result.rootfs.entries,
            expandedBytes = result.rootfs.expandedBytes
        )
    }

    override fun validateProot(): ProotValidationResult {
        val result = ProotDownloader(paths).downloadAndInstall()
        return ProotValidationResult(result.sha256, result.bytes, result.release)
    }

    override fun runSmokeTest(timeoutSeconds: Long): RuntimeSmokeResult {
        val result = LinuxRuntimeSmokeTest(paths).run(timeoutSeconds)
        return RuntimeSmokeResult(result.success, result.exitCode, result.output, result.error)
    }
}
