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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

private data class TermuxLinuxTab(val id: Int, val session: TerminalSession)

private object TermuxLinuxTabs {
    val tabs = mutableStateListOf<TermuxLinuxTab>()
    var selectedId by mutableStateOf(0)
    private val ids = AtomicInteger(1)

    fun add(session: TerminalSession): Int {
        val id = ids.getAndIncrement()
        tabs.add(TermuxLinuxTab(id, session))
        selectedId = id
        return id
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
    val preferences = remember(context) { context.getSharedPreferences("developer_options", 0) }
    val fontSize = remember(context) {
        context.getSharedPreferences("ide_preferences", 0).getInt("terminal_font_size", 14).coerceIn(10, 28)
    }

    DisposableEffect(Unit) {
        onDispose { /* Sessions deliberately outlive this panel; tab close owns process cleanup. */ }
    }

    fun startUbuntu() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            // Runtime checks and filesystem preparation stay off the UI thread.
            val validation = withContext(Dispatchers.IO) {
                runCatching {
                    paths.ensureDirectories()
                    check(paths.hasRootfs()) { "Ubuntu rootfs is not installed or incomplete. Open Runtime & Bootstrap settings." }
                    ProotDownloader(paths).downloadAndInstall()
                    val smoke = LinuxRuntimeSmokeTest(paths).run()
                    check(smoke.success) { smoke.error ?: smoke.output.ifBlank { "Ubuntu shell smoke test failed." } }
                }
            }
            // Termux TerminalSession creates an Android Handler internally. It must be
            // constructed on the main Looper, never on a Dispatchers.IO worker.
            val result = if (validation.isSuccess) {
                withContext(Dispatchers.Main.immediate) {
                    runCatching { UbuntuTermuxSession.create(context, paths) }
                }
            } else {
                Result.failure(validation.exceptionOrNull() ?: IllegalStateException("Ubuntu validation failed."))
            }
            busy = false
            result.onSuccess { session ->
                val id = TermuxLinuxTabs.add(session)
                DeveloperLog.event("TermuxTerminal", "ubuntu_tab_started", mapOf("tabId" to id))
            }.onFailure {
                DeveloperLog.failure("TermuxTerminal", "ubuntu_tab_start_failed", it)
                error = it.message ?: "Ubuntu terminal could not start."
            }
        }
    }

    Column(modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TermuxLinuxTabs.tabs.forEach { tab ->
                OutlinedButton(onClick = { TermuxLinuxTabs.selectedId = tab.id }) {
                    Text(if (tab.id == TermuxLinuxTabs.selectedId) "● Ubuntu ${tab.id}" else "Ubuntu ${tab.id}", fontSize = 11.sp)
                }
                Text("×", color = Color(0xFFFF8A80), modifier = Modifier.clickable { TermuxLinuxTabs.close(tab.id) }.padding(8.dp))
            }
            Button(enabled = !busy, onClick = ::startUbuntu) { Text("+ Ubuntu") }
            OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
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
                Text("Termux Terminal", color = Color(0xFF8CC8FF), fontSize = 18.sp)
                Text("Ubuntu sessions run on Termux's native PTY, emulator, cursor, scrollback and input implementation.", color = Color(0xFF9AA7B5), fontSize = 12.sp)
                Button(enabled = !busy, onClick = ::startUbuntu) { Text("Start Ubuntu") }
                OutlinedButton(onClick = onOpenSettings) { Text("Terminal settings") }
                if (busy) CircularProgressIndicator()
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            }
        }
    }
}
