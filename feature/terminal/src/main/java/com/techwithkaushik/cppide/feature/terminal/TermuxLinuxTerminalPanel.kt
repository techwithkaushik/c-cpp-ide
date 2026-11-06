package com.techwithkaushik.cppide.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalSession
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimeSmokeTest
import com.techwithkaushik.cppide.integration.linuxruntime.ProotDownloader
import com.techwithkaushik.cppide.integration.linuxruntime.RootfsInstaller
import com.techwithkaushik.cppide.integration.linuxruntime.RootfsArchitectureInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

internal data class TermuxLinuxTab(val id: Int, val session: TerminalSession)

internal object TermuxLinuxTabs {
    val tabs = mutableStateListOf<TermuxLinuxTab>()
    var selectedId by mutableStateOf(0)
    private val ids = AtomicInteger(1)

    fun add(session: TerminalSession): Int {
        val id = ids.getAndIncrement()
        tabs.add(TermuxLinuxTab(id, session))
        selectedId = id
        return id
    }

    fun onSessionFinished(session: TerminalSession) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val tab = tabs.firstOrNull { it.session === session } ?: return@post
            tabs.remove(tab)
            if (selectedId == tab.id) selectedId = tabs.lastOrNull()?.id ?: 0
        }
    }

    fun close(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tabs.remove(tab)
        runCatching { tab.session.finishIfRunning() }
        if (selectedId == id) selectedId = tabs.lastOrNull()?.id ?: 0
    }
}

/** The app's terminal UI is exclusively backed by Termux TerminalView/TerminalSession. */
@Composable
fun TermuxLinuxTerminalPanel(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onCloseTerminal: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val paths = remember(context) { LinuxRuntimePaths(context.filesDir, context.applicationInfo.nativeLibraryDir) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingArchitectureWarning by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var localInstallStatus by remember { mutableStateOf<String?>(null) }
    val preferences = remember(context) { context.getSharedPreferences("developer_options", 0) }
    val fontSize = remember(context) {
        context.getSharedPreferences("ide_preferences", 0).getInt("terminal_font_size", 14).coerceIn(10, 28)
    }

    DisposableEffect(Unit) {
        onDispose { /* Sessions deliberately outlive this panel; tab close owns process cleanup. */ }
    }

    fun displayName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        return cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.takeIf { it.isNotBlank() } ?: "ubuntu.tar"
    }

    fun installLocalRootfs(uri: Uri) {
        if (busy) return
        busy = true
        error = null
        localInstallStatus = "Inspecting and installing local Ubuntu rootfs…"
        val archiveName = displayName(uri)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val installed = RootfsInstaller(paths).install(
                        open = { context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open the selected archive.") },
                        archiveName = archiveName
                    )
                    installed
                }
            }
            busy = false
            localInstallStatus = null
            result.onSuccess {
                pendingArchitectureWarning = null
                DeveloperLog.event("UbuntuInstaller", "local_rootfs_installed", mapOf("archive" to archiveName, "entries" to it.entries, "expandedBytes" to it.expandedBytes))
            }.onFailure {
                error = it.message ?: "Local Ubuntu installation failed."
                DeveloperLog.failure("UbuntuInstaller", "local_rootfs_install_failed", it, mapOf("archive" to archiveName))
            }
        }
    }

    fun inspectLocalRootfs(uri: Uri) {
        if (busy) return
        busy = true
        error = null
        val archiveName = displayName(uri)
        scope.launch {
            val inspection = withContext(Dispatchers.IO) {
                runCatching {
                    RootfsArchitectureInspector.inspect(
                        open = { context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read the selected archive.") },
                        archiveName = archiveName
                    )
                }
            }
            busy = false
            inspection.onSuccess { report ->
                val mismatch = report.architecture != paths.ubuntuArchitecture
                if (mismatch) {
                    pendingArchitectureWarning = uri to "Detected ${report.architecture} rootfs, but this app process requires ${paths.ubuntuArchitecture}. ELF files: arm64=${report.arm64Executables}, armhf=${report.armhfExecutables}, other=${report.otherExecutables}. Installing an incompatible rootfs may make Ubuntu unable to start."
                } else {
                    installLocalRootfs(uri)
                }
            }.onFailure { error = it.message ?: "Could not inspect this rootfs archive." }
        }
    }

    val localRootfsPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) inspectLocalRootfs(uri)
    }

    fun downloadOnline() {
        if (busy) return
        busy = true
        error = null
        localInstallStatus = "Downloading and installing Ubuntu Base…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { com.techwithkaushik.cppide.integration.linuxruntime.UbuntuBaseDownloader(paths).downloadAndInstall() }
            }
            busy = false
            localInstallStatus = null
            result.onFailure { error = it.message ?: "Ubuntu download or installation failed." }
        }
    }

    fun startUbuntu() {
        if (busy) return
        busy = true
        error = null
        val operationId = UUID.randomUUID().toString().take(8)
        DeveloperLog.event("TermuxTerminal", "ubuntu_start_requested", mapOf("operationId" to operationId))
        scope.launch {
            var failureStage = "runtime_validation"
            val validationStartedAt = System.nanoTime()
            val validation = withContext(Dispatchers.IO) {
                runCatching {
                    DeveloperLog.event("TermuxTerminal", "runtime_validation_started", mapOf("operationId" to operationId))
                    paths.ensureDirectories()
                    check(paths.hasRootfs()) { "Ubuntu rootfs is not installed or incomplete. Open Runtime & Bootstrap settings." }
                    if (!paths.ready()) {
                        ProotDownloader(paths).downloadAndInstall()
                        val smoke = LinuxRuntimeSmokeTest(paths).run()
                        check(smoke.success) {
                            smoke.error ?: smoke.output.ifBlank { "Ubuntu shell smoke test failed." }
                        }
                        check(paths.ready()) { "Ubuntu runtime did not become ready after validation." }
                    }
                }
            }
            val validationDurationMs = (System.nanoTime() - validationStartedAt) / 1_000_000
            DeveloperLog.event("TermuxTerminal", "runtime_validation_completed", mapOf(
                "operationId" to operationId,
                "result" to if (validation.isSuccess) "success" else "failed",
                "durationMs" to validationDurationMs
            ))
            val result = if (validation.isSuccess) {
                failureStage = "terminal_session_create"
                val sessionStartedAt = System.nanoTime()
                DeveloperLog.event("TermuxTerminal", "terminal_session_create_started", mapOf("operationId" to operationId))
                withContext(Dispatchers.Main.immediate) {
                    runCatching { UbuntuTermuxSession.create(context, paths, onFinished = TermuxLinuxTabs::onSessionFinished) }
                }.also { sessionResult ->
                    DeveloperLog.event("TermuxTerminal", "terminal_session_create_completed", mapOf(
                        "operationId" to operationId,
                        "result" to if (sessionResult.isSuccess) "success" else "failed",
                        "durationMs" to (System.nanoTime() - sessionStartedAt) / 1_000_000
                    ))
                }
            } else {
                Result.failure(validation.exceptionOrNull() ?: IllegalStateException("Ubuntu validation failed."))
            }
            busy = false
            result.onSuccess { session ->
                val id = TermuxLinuxTabs.add(session)
                DeveloperLog.event("TermuxTerminal", "ubuntu_tab_started", mapOf(
                    "operationId" to operationId,
                    "tabId" to id,
                    "validationDurationMs" to validationDurationMs
                ))
            }.onFailure {
                DeveloperLog.failure("TermuxTerminal", "ubuntu_tab_start_failed", it, mapOf(
                    "operationId" to operationId,
                    "failureStage" to failureStage,
                    "validationDurationMs" to validationDurationMs
                ))
                error = it.message ?: "Ubuntu terminal could not start."
            }
        }
    }

    LaunchedEffect(Unit) {
        if (TermuxLinuxTabs.tabs.isEmpty()) startUbuntu()
    }

    pendingArchitectureWarning?.let { (uri, warning) ->
        AlertDialog(
            onDismissRequest = { pendingArchitectureWarning = null },
            title = { Text("Rootfs architecture mismatch") },
            text = { Text("$warning\n\nContinue only if you understand the risk.") },
            confirmButton = {
                TextButton(onClick = { pendingArchitectureWarning = null; installLocalRootfs(uri) }) {
                    Text("Install anyway")
                }
            },
            dismissButton = { TextButton(onClick = { pendingArchitectureWarning = null }) { Text("Cancel") } }
        )
    }

    Column(modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TermuxLinuxTabs.tabs.forEach { tab ->
                OutlinedButton(
                    onClick = { TermuxLinuxTabs.selectedId = tab.id },
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (tab.id == TermuxLinuxTabs.selectedId) "● ${tab.id}" else "${tab.id}",
                        fontSize = 10.sp,
                        lineHeight = 12.sp
                    )
                }
                Text("×", color = Color(0xFFFF8A80), modifier = Modifier.clickable { TermuxLinuxTabs.close(tab.id) }.padding(horizontal = 3.dp, vertical = 4.dp), fontSize = 12.sp)
            }
            Button(enabled = !busy, onClick = ::startUbuntu) { Text("+") }
        }

        val active = TermuxLinuxTabs.tabs.firstOrNull { it.id == TermuxLinuxTabs.selectedId }
        if (active != null) {
            TermuxTerminalView(
                session = active.session,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                fontSizeSp = fontSize
            )
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            }
        }
    }
}
