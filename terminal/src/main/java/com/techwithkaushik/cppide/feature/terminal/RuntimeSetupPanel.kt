package com.techwithkaushik.cppide.feature.terminal

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import com.techwithkaushik.cppide.core.contracts.runtime.LinuxRuntimeOperations
import com.techwithkaushik.cppide.core.contracts.runtime.RootfsArchitectureReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
fun RuntimeSetupPanel(runtime: LinuxRuntimeOperations, onRuntimeReady: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val paths = runtime.environment
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose an Ubuntu installation method.") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<RootfsArchitectureReport?>(null) }
    var showOverride by remember { mutableStateOf(false) }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "ubuntu-rootfs.tar")

    suspend fun validateRuntime() {
        val startedAt = System.nanoTime()
        DeveloperLog.event("UbuntuSetup", "runtime_validation_started", mapOf("rootfs" to paths.ubuntuRoot.absolutePath, "rootfsExists" to paths.hasRootfs(), "prootExists" to paths.hasProot()))
        val validation = withContext(Dispatchers.IO) {
            runCatching {
                val proot = runtime.validateProot()
                DeveloperLog.event("UbuntuSetup", "proot_validation_passed", mapOf("bytes" to proot.bytes, "release" to proot.release))
                if (!paths.hasRootfs()) throw IOException("Ubuntu rootfs is missing or incomplete.")
                DeveloperLog.event("UbuntuSetup", "rootfs_validation_passed", mapOf("rootfs" to paths.ubuntuRoot.absolutePath))
                val smoke = runtime.runSmokeTest()
                DeveloperLog.event("UbuntuSetup", "shell_smoke_test_completed", mapOf("success" to smoke.success, "exitCode" to smoke.exitCode, "stdoutCharacters" to smoke.output.length, "error" to smoke.error?.take(800)))
                if (!smoke.success) throw IOException(smoke.error ?: smoke.output.ifBlank { "Ubuntu shell smoke test failed." })
            }
        }
        validation.fold(
            onSuccess = {
                status = "Ubuntu runtime is ready."
                DeveloperLog.event("UbuntuSetup", "runtime_validation_passed", mapOf("durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
                onRuntimeReady()
            },
            onFailure = { error ->
                DeveloperLog.failure("UbuntuSetup", "runtime_validation_failed", error, mapOf("durationMs" to (System.nanoTime() - startedAt) / 1_000_000, "rootfsExists" to paths.hasRootfs(), "prootExists" to paths.hasProot()))
                status = runtimeFailureMessage(error.message, "Ubuntu could not start. Please try again.")
            }
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        DeveloperLog.event("UbuntuSetup", "rootfs_picker_result", mapOf("selected" to (uri != null), "scheme" to uri?.scheme))
        if (uri != null) {
            selectedUri = uri
            selectedName = displayName(uri)
            report = null
            showOverride = false
            busy = true
            status = "Inspecting selected rootfs architecture…"
            scope.launch {
                try {
                    DeveloperLog.event("UbuntuSetup", "rootfs_inspection_started", mapOf("fileName" to selectedName))
                    val inspected = withContext(Dispatchers.IO) {
                        runtime.inspectRootfs({
                            context.contentResolver.openInputStream(uri)
                                ?: throw IOException("Android could not reopen the selected archive.")
                        }, selectedName)
                    }
                    report = inspected
                    DeveloperLog.event("UbuntuSetup", "rootfs_inspection_completed", mapOf("fileName" to selectedName, "architecture" to inspected.architecture, "arm64Executables" to inspected.arm64Executables, "armhfExecutables" to inspected.armhfExecutables, "otherExecutables" to inspected.otherExecutables))
                    val expected = paths.ubuntuArchitecture.lowercase()
                    val compatible = (expected in listOf("arm64", "aarch64") && inspected.architecture == "arm64") ||
                        (expected in listOf("arm", "armhf", "armv7", "armv7l") && inspected.architecture == "armhf")
                    if (compatible) status = "Architecture verified (${inspected.architecture}). Ready to install."
                    else { status = "Architecture mismatch: app=$expected, archive=${inspected.architecture}."; showOverride = true }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DeveloperLog.failure("UbuntuSetup", "rootfs_architecture_inspection_failed", error, mapOf("fileName" to selectedName))
                    selectedUri = null
                    selectedName = ""
                    status = runtimeFailureMessage(error.message, "Could not inspect this archive.")
                } finally { busy = false }
            }
        }
    }

    fun installLocal(allowMismatch: Boolean) {
        val uri = selectedUri ?: return
        val inspected = report ?: return
        val expected = paths.ubuntuArchitecture.lowercase()
        val compatible = (expected in listOf("arm64", "aarch64") && inspected.architecture == "arm64") ||
            (expected in listOf("arm", "armhf", "armv7", "armv7l") && inspected.architecture == "armhf")
        if (!compatible && !allowMismatch) { showOverride = true; return }
        showOverride = false
        busy = true
        status = "Installing local Ubuntu rootfs…"
        scope.launch {
            val startedAt = System.nanoTime()
            DeveloperLog.event("UbuntuSetup", "local_rootfs_install_started", mapOf("fileName" to selectedName, "architecture" to inspected.architecture, "allowMismatch" to allowMismatch))
            try {
                withContext(Dispatchers.IO) {
                    runtime.installRootfs({
                        context.contentResolver.openInputStream(uri)
                            ?: throw IOException("Android could not reopen the selected archive.")
                    }, selectedName)
                }
                DeveloperLog.event("UbuntuSetup", "local_rootfs_install_completed", mapOf("fileName" to selectedName, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
                validateRuntime()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DeveloperLog.failure("UbuntuSetup", "local_rootfs_install_failed", error, mapOf("fileName" to selectedName, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
                status = runtimeFailureMessage(error.message, "Ubuntu rootfs installation failed.")
            } finally { busy = false }
        }
    }

    LaunchedEffect(context) {
        val present = withContext(Dispatchers.IO) { paths.hasRootfs() }
        DeveloperLog.event("UbuntuSetup", "setup_panel_initialized", mapOf("rootfsPresent" to present, "architecture" to paths.ubuntuArchitecture, "rootfsDiagnostic" to paths.rootfsDiagnostic()))
        status = if (present) "Ubuntu rootfs found. Validating shell…" else "Ubuntu rootfs is not installed."
        if (present) validateRuntime()
    }

    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ubuntu Linux runtime", color = Color(0xFF8CC8FF), fontSize = 18.sp)
        Button(enabled = !busy, onClick = {
            val startedAt = System.nanoTime()
            DeveloperLog.event("UbuntuSetup", "online_rootfs_download_requested", mapOf("architecture" to paths.ubuntuArchitecture))
            busy = true
            status = "Downloading Ubuntu Base 24.04.5…"
            scope.launch {
                try {
                    val downloaded = withContext(Dispatchers.IO) { runtime.downloadAndInstallUbuntu() }
                    DeveloperLog.event("UbuntuSetup", "online_rootfs_download_completed", mapOf("archiveBytes" to downloaded.bytes, "entries" to downloaded.rootfs.entries, "expandedBytes" to downloaded.rootfs.expandedBytes, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
                    validateRuntime()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DeveloperLog.failure("UbuntuSetup", "online_rootfs_download_failed", error, mapOf("architecture" to paths.ubuntuArchitecture, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
                    status = runtimeFailureMessage(error.message, "Ubuntu download failed. Check your connection and try again.")
                } finally { busy = false }
            }
        }) { Text("Download Online") }

        OutlinedButton(enabled = !busy, onClick = {
            picker.launch(arrayOf("application/x-tar", "application/gzip", "application/x-xz", "application/octet-stream", "*/*"))
        }) { Text("Select Local File") }

        if (selectedName.isNotBlank()) Text("Selected: $selectedName", color = Color(0xFF9AA7B5), fontSize = 12.sp)
        report?.let { inspected ->
            Text("Detected: ${inspected.architecture} · ARM64 ${inspected.arm64Executables} · ARMHF ${inspected.armhfExecutables} · Other ${inspected.otherExecutables}",
                color = Color(0xFF9AA7B5), fontSize = 12.sp)
            Button(enabled = !busy && !showOverride, onClick = { installLocal(false) }) { Text("Install Local Rootfs") }
        }
        if (busy) CircularProgressIndicator(color = Color(0xFF8CC8FF))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, color = Color(0xFF9AA7B5), fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }

    if (showOverride && report != null) {
        val inspected = report!!
        AlertDialog(
            onDismissRequest = { showOverride = false },
            title = { Text("Architecture mismatch") },
            text = { Text("The app expects ${paths.ubuntuArchitecture}, but this archive contains ${inspected.architecture} executables. Installing it may prevent Ubuntu from starting. Continue anyway?") },
            confirmButton = { TextButton(onClick = { installLocal(true) }) { Text("Install anyway") } },
            dismissButton = { TextButton(onClick = { showOverride = false }) { Text("Cancel") } }
        )
    }
}

private fun runtimeFailureMessage(details: String?, fallback: String): String {
    val message = details.orEmpty().lowercase()
    return when {
        "no space" in message || "enospc" in message -> "Not enough storage. Free space and try again."
        "unknownhost" in message || "connection" in message || "timed out" in message -> "Download connection failed. Check your internet connection."
        "checksum" in message || "sha-256" in message -> "Archive verification failed. Download or select the archive again."
        "unsupported archive" in message || "invalid archive" in message -> "Unsupported archive. Select a .tar, .tar.gz, or .tar.xz file."
        "permission denied" in message || "eacces" in message -> "Ubuntu could not access a required file. Reinstall the runtime."
        else -> fallback
    }
}
