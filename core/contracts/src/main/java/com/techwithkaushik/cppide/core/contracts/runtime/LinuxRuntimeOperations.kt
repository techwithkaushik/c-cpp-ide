package com.techwithkaushik.cppide.core.contracts.runtime

import java.io.File
import java.io.InputStream

data class RootfsArchitectureReport(
    val architecture: String,
    val arm64Executables: Int,
    val armhfExecutables: Int,
    val otherExecutables: Int
) {
    val isMixedOrUnknown: Boolean get() = architecture == "unknown" || architecture == "mixed"
}

data class RootfsInstallResult(
    val root: File,
    val entries: Int,
    val expandedBytes: Long
)

data class UbuntuDownloadResult(
    val sha256: String,
    val archiveBytes: Long,
    val rootfsEntries: Int,
    val expandedBytes: Long
)

data class ProotValidationResult(
    val sha256: String,
    val bytes: Long,
    val release: String
)

data class RuntimeSmokeResult(
    val success: Boolean,
    val exitCode: Int?,
    val output: String,
    val error: String?
)

/**
 * Application-facing operations for provisioning and validating the Linux runtime.
 *
 * The UI supplies reopenable archive streams; all download, extraction, checksum,
 * PRoot and shell validation details remain inside the runtime integration.
 */
interface LinuxRuntimeOperations {
    val environment: LinuxRuntimeEnvironment

    fun inspectRootfs(open: () -> InputStream, archiveName: String): RootfsArchitectureReport
    fun installRootfs(open: () -> InputStream, archiveName: String): RootfsInstallResult
    fun downloadAndInstallUbuntu(onProgress: (Long, Long) -> Unit = { _, _ -> }): UbuntuDownloadResult
    fun validateProot(): ProotValidationResult
    fun runSmokeTest(timeoutSeconds: Long = 20): RuntimeSmokeResult
}
