package com.techwithkaushik.cppide.feature.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.techwithkaushik.cppide.core.designsystem.IdeDesignTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.integration.linuxruntime.HostKeyConfirmationRequired
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.linuxruntime.LocalLinuxSession
import com.techwithkaushik.cppide.integration.linuxruntime.SshHost
import com.techwithkaushik.cppide.integration.linuxruntime.SshShellSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.ByteArrayOutputStream

private data class LinuxTerminalTab(
    val id: Int,
    val title: MutableState<String>,
    val output: MutableState<AnnotatedString>,
    val screen: TerminalScreen,
    val send: (String) -> Unit,
    val interrupt: () -> Unit,
    val session: AutoCloseable,
    val resize: (Int, Int) -> Unit,
    val exited: MutableState<Int?>,
    var lastPtyColumns: Int = 0,
    var lastPtyRows: Int = 0,
)

private object LinuxTerminalSessions {
    val tabs = mutableStateListOf<LinuxTerminalTab>()
    var selectedId by mutableIntStateOf(0)
        private set
    var onLastSessionExited: (() -> Unit)? = null
    private var nextId = 1

    fun select(id: Int) { if (tabs.any { it.id == id }) selectedId = id }

    fun add(title: String, session: AutoCloseable, input: OutputStream, interrupt: () -> Unit, resize: (Int, Int) -> Unit = { _, _ -> }, initialOutput: String = "", maxScrollbackLines: Int = 5_000): LinuxTerminalTab {
        val screen = TerminalScreen(maxScrollbackLines = maxScrollbackLines)
        screen.append(initialOutput)
        val tab = LinuxTerminalTab(
            id = nextId++,
            title = mutableStateOf(title),
            output = mutableStateOf(screen.renderAnnotated()),
            screen = screen,
            send = { text -> input.write(text.toByteArray(Charsets.UTF_8)); input.flush() },
            interrupt = interrupt,
            session = session,
            resize = resize,
            exited = mutableStateOf(null)
        )
        tabs.add(tab)
        selectedId = tab.id
        return tab
    }

    fun append(tab: LinuxTerminalTab, text: String) {
        if (tabs.none { it.id == tab.id }) return
        tab.screen.append(text)
        tab.output.value = tab.screen.renderAnnotated()
    }

    fun finish(tab: LinuxTerminalTab) {
        val index = tabs.indexOfFirst { it.id == tab.id }
        if (index < 0) return
        tabs.removeAt(index)
        runCatching { tab.session.close() }
        if (tabs.isEmpty()) {
            selectedId = 0
            onLastSessionExited?.invoke()
        } else if (selectedId == tab.id) {
            selectedId = tabs.getOrNull(index - 1)?.id ?: tabs.first().id
        }
    }

    fun closeTab(id: Int) {
        tabs.firstOrNull { it.id == id }?.let(::finish)
    }

}

@Composable
fun LinuxTerminalPanel(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onCloseTerminal: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val paths = remember(context) {
        // Use the same authoritative APK native-library path as Runtime Settings.
        LinuxRuntimePaths(context.filesDir, context.applicationInfo.nativeLibraryDir)
    }
    val terminalPreferences = remember(context) { context.getSharedPreferences(IdeDesignTokens.Preference.FILE, Context.MODE_PRIVATE) }
    var terminalFontSize by remember { mutableIntStateOf(terminalPreferences.getInt(IdeDesignTokens.Preference.TERMINAL_FONT_SIZE, IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE).coerceIn(10, 28)) }
    val scrollbackLines = terminalPreferences.getInt("terminal_scrollback_lines", 5_000).coerceIn(1_000, 10_000)
    val autoShowKeyboard = terminalPreferences.getBoolean(IdeDesignTokens.Preference.TERMINAL_AUTO_KEYBOARD, true)
    val connectionMode = terminalPreferences.getString("terminal_connection_mode", "ubuntu") ?: "ubuntu"
    val terminalInputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val sshHostKeyPrefs = remember(context) { context.getSharedPreferences("trusted_ssh_host_keys", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }
    var command by remember { mutableStateOf("") }
    var sessionMenuId by remember { mutableIntStateOf(0) }
    var renameTabId by remember { mutableIntStateOf(0) }
    var renameValue by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var host by remember { mutableStateOf(terminalPreferences.getString("terminal_ssh_host", "").orEmpty()) }
    var port by remember { mutableStateOf(terminalPreferences.getInt("terminal_ssh_port", 22).toString()) }
    var username by remember { mutableStateOf(terminalPreferences.getString("terminal_ssh_user", "").orEmpty()) }
    var password by remember { mutableStateOf(TerminalConnectionConfig.sshPassword) }
    var privateKeyBytes by remember { mutableStateOf(TerminalConnectionConfig.sshPrivateKey) }
    var privateKeyPassphrase by remember { mutableStateOf(TerminalConnectionConfig.sshPrivateKeyPassphrase) }
    var expectedFingerprint by remember { mutableStateOf("") }
    var fingerprintConfirmation by remember { mutableStateOf<String?>(null) }

    DisposableEffect(onCloseTerminal) {
        LinuxTerminalSessions.onLastSessionExited = onCloseTerminal
        onDispose {
            if (LinuxTerminalSessions.onLastSessionExited === onCloseTerminal) LinuxTerminalSessions.onLastSessionExited = null
        }
    }

    fun connectSsh(expected: String?) {
        val sshHost = try {
            SshHost(host.trim(), port.toIntOrNull() ?: 22, username.trim(), password = password.ifBlank { null }, privateKey = privateKeyBytes, privateKeyPassphrase = privateKeyPassphrase.ifBlank { null }, expectedSha256Fingerprint = expected ?: sshHostKeyPrefs.getString("${host.trim()}:${port.toIntOrNull() ?: 22}", null))
        } catch (e: Exception) {
            error = e.message ?: "Invalid SSH settings."
            return
        }
        // Persist only non-secret connection coordinates; passwords and private keys stay in memory.
        terminalPreferences.edit()
            .putString("terminal_ssh_host", sshHost.host)
            .putInt("terminal_ssh_port", sshHost.port)
            .putString("terminal_ssh_user", sshHost.username)
            .apply()
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(SshShellSession.connect(sshHost)) }
                catch (e: Exception) { Result.failure<SshShellSession>(e) }
            }
            busy = false
            result.onSuccess { session ->
                val title = "SSH ${sshHost.username}@${sshHost.host}"
                val tab = LinuxTerminalSessions.add(title, session, session.input, interrupt = { runCatching { session.input.write(byteArrayOf(3)); session.input.flush() } }, resize = session::resize, initialOutput = "Connected to ${sshHost.host}:\n", maxScrollbackLines = scrollbackLines)
                session.readOutput(
                    onChunk = { chunk -> handler.post { LinuxTerminalSessions.append(tab, chunk) } },
                    onExit = { code -> handler.post {
                        tab.exited.value = code
                        LinuxTerminalSessions.append(tab, "\n[SSH session exited: $code — output is preserved. Close this tab to dismiss.]\n")
                    } }
                )
                password = ""
                TerminalConnectionConfig.sshPassword = ""
                privateKeyBytes = null
                TerminalConnectionConfig.sshPrivateKey = null
                TerminalConnectionConfig.sshPrivateKeyPassphrase = ""
                privateKeyPassphrase = ""
            }.onFailure { e ->
                if (e is HostKeyConfirmationRequired) {
                    fingerprintConfirmation = e.fingerprint
                    expectedFingerprint = e.fingerprint
                } else error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun startLocalSession() {
        if (busy) return
        if (!paths.ready()) {
            val validation = context.getSharedPreferences("linux_runtime_validation", Context.MODE_PRIVATE)
                .getString("message", null)
            error = validation ?: if (!paths.hasRootfs()) "Ubuntu rootfs is not installed. Open Runtime & Bootstrap in Settings."
            else "The app-owned PRoot binary is missing or invalid. Open Runtime & Bootstrap in Settings."
            return
        }
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(LocalLinuxSession.start(paths)) }
                catch (e: Exception) { Result.failure<LocalLinuxSession>(e) }
            }
            busy = false
            result.onSuccess { session ->
                val tab = LinuxTerminalSessions.add("Ubuntu " + (LinuxTerminalSessions.tabs.size + 1), session, session.input, interrupt = { session.interrupt() }, resize = session::resize, initialOutput = "", maxScrollbackLines = scrollbackLines)
                session.readOutput(
                    onChunk = { chunk -> handler.post { LinuxTerminalSessions.append(tab, chunk) } },
                    onExit = { code -> handler.post {
                        tab.exited.value = code
                        LinuxTerminalSessions.append(tab, "\r\n[Shell exited: " + code + " — output preserved. Close this tab to dismiss.]\r\n")
                    } }
                )
            }.onFailure { error = it.message ?: "Could not start Ubuntu shell." }
        }
    }

    LaunchedEffect(connectionMode) {
        if (connectionMode == "ssh") {
            when {
                host.isBlank() || username.isBlank() -> error = "Configure SSH host and username in Settings → Terminal."
                password.isBlank() && privateKeyBytes == null -> error = "Add an SSH password or private key in Settings → Terminal."
                else -> connectSsh(null)
            }
        } else {
            startLocalSession()
        }
    }

    Column(modifier.fillMaxSize().background(Color(0xFF080B10))) {
        if (busy) Text("Starting session…", color = Color(0xFF9AA7B5), modifier = Modifier.padding(8.dp))

        if (LinuxTerminalSessions.tabs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LinuxTerminalSessions.tabs.forEach { tab ->
                    Row {
                        Row(
                            Modifier.background(if (tab.id == LinuxTerminalSessions.selectedId) Color(0xFF173D5C) else Color(0xFF151B23), RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { LinuxTerminalSessions.select(tab.id) },
                                    onLongClick = { sessionMenuId = tab.id }
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(tab.title.value, color = Color(0xFFE8EDF5), maxLines = 1, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = sessionMenuId == tab.id, onDismissRequest = { sessionMenuId = 0 }) {
                            DropdownMenuItem(text = { Text("Rename session") }, onClick = {
                                renameTabId = tab.id
                                renameValue = tab.title.value
                                sessionMenuId = 0
                            })
                            DropdownMenuItem(text = { Text("Close session") }, onClick = {
                                sessionMenuId = 0
                                LinuxTerminalSessions.closeTab(tab.id)
                            })
                        }
                    }
                }
                }
                Text("A−", modifier = Modifier.clickable { terminalFontSize = (terminalFontSize - 1).coerceAtLeast(10); terminalPreferences.edit().putInt(IdeDesignTokens.Preference.TERMINAL_FONT_SIZE, terminalFontSize).apply() }.padding(horizontal = 7.dp, vertical = 4.dp), color = Color(0xFF9AA7B5), fontSize = 18.sp)
                Text("A+", modifier = Modifier.clickable { terminalFontSize = (terminalFontSize + 1).coerceAtMost(28); terminalPreferences.edit().putInt(IdeDesignTokens.Preference.TERMINAL_FONT_SIZE, terminalFontSize).apply() }.padding(horizontal = 7.dp, vertical = 4.dp), color = Color(0xFF9AA7B5), fontSize = 18.sp)
                Text("+", modifier = Modifier.background(Color(0xFF173D5C), RoundedCornerShape(7.dp)).clickable {
                    if (connectionMode == "ssh") onOpenSettings() else startLocalSession()
                }.padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, fontSize = 16.sp)
            }
        }

        val active = LinuxTerminalSessions.tabs.firstOrNull { it.id == LinuxTerminalSessions.selectedId }
        LaunchedEffect(active?.id, autoShowKeyboard) {
            if (active != null && autoShowKeyboard && active.exited.value == null) {
                kotlinx.coroutines.delay(180)
                terminalInputFocusRequester.requestFocus()
                keyboardController?.show()
            }
        }
        if (active == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linux terminal", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("No active terminal session. Choose the connection type and start a session from Terminal Settings.", color = Color(0xFF9AA7B5), fontSize = 12.sp)
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            }
        } else {
            // Blink the block cursor at a terminal-like cadence. DEC ?25l still
            // suppresses it because visibility is controlled by the VT screen.
            LaunchedEffect(active.id) {
                var visible = true
                while (true) {
                    delay(530)
                    visible = !visible
                    active.screen.setCursorBlinkPhase(visible)
                    active.output.value = active.screen.renderAnnotated()
                }
            }
            val outputScrollState = rememberScrollState()
            val density = LocalDensity.current
            LaunchedEffect(active.output.value) {
                // Follow live output only when the user was already at the bottom.
                // This keeps scrollback readable while a command is producing output.
                val wasFollowingOutput = outputScrollState.maxValue - outputScrollState.value <= 48
                if (wasFollowingOutput) outputScrollState.scrollTo(outputScrollState.maxValue)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF080B10))
                    .onSizeChanged { size ->
                        val cellWidth = with(density) { (terminalFontSize.sp.toPx() * 0.62f).coerceAtLeast(1f) }
                        val cellHeight = with(density) { (terminalFontSize.sp.toPx() * 1.35f).coerceAtLeast(1f) }
                        val columns = (size.width / cellWidth).toInt().coerceIn(20, 300)
                        val rows = (size.height / cellHeight).toInt().coerceIn(5, 120)
                        active.screen.resize(columns, rows)
                        // Avoid redundant PTY size updates; interactive shells redraw
                        // their prompt on every SIGWINCH.
                        if (columns != active.lastPtyColumns || rows != active.lastPtyRows) {
                            active.lastPtyColumns = columns
                            active.lastPtyRows = rows
                            active.resize(columns, rows)
                            active.output.value = active.screen.renderAnnotated()
                        }
                    }
            ) {
                Text(
                    active.output.value,
                    color = Color(0xFFE6EDF3),
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    fontSize = terminalFontSize.sp,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .pointerInput(terminalInputFocusRequester) {
                            detectTapGestures {
                                terminalInputFocusRequester.requestFocus()
                                keyboardController?.show()
                            }
                        }
                        .horizontalScroll(rememberScrollState())
                    .verticalScroll(outputScrollState)
                )
                androidx.compose.foundation.text.BasicTextField(
                    value = command,
                    onValueChange = { updated ->
                        val previous = command
                        if (active.exited.value == null) {
                            val commonPrefix = previous.commonPrefixWith(updated)
                            val removed = previous.length - commonPrefix.length
                            val added = updated.substring(commonPrefix.length)
                            try {
                                repeat(removed) { active.send("\u007f") }
                                if (added.isNotEmpty()) active.send(added)
                            } catch (e: Exception) { error = e.message ?: "Terminal input failed." }
                        }
                        command = updated
                    },
                    modifier = Modifier.size(1.dp).focusRequester(terminalInputFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || active.exited.value != null) return@onPreviewKeyEvent false
                            if (event.isCtrlPressed) {
                                val control = when (event.key) {
                                    androidx.compose.ui.input.key.Key.A -> "\u0001" // beginning of line
                                    androidx.compose.ui.input.key.Key.C -> "\u0003" // interrupt
                                    androidx.compose.ui.input.key.Key.D -> "\u0004" // EOF
                                    androidx.compose.ui.input.key.Key.E -> "\u0005" // end of line
                                    androidx.compose.ui.input.key.Key.K -> "\u000b" // kill to end of line
                                    androidx.compose.ui.input.key.Key.L -> "\u000c" // clear screen
                                    androidx.compose.ui.input.key.Key.R -> "\u0012" // reverse history search
                                    androidx.compose.ui.input.key.Key.U -> "\u0015" // kill to beginning of line
                                    androidx.compose.ui.input.key.Key.W -> "\u0017" // erase previous word
                                    androidx.compose.ui.input.key.Key.Z -> "\u001a" // suspend foreground job
                                    else -> null
                                }
                                if (control != null) {
                                    try { active.send(control) } catch (e: Exception) { error = e.message ?: "Terminal input failed." }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            when (event.key) {
                                androidx.compose.ui.input.key.Key.DirectionUp -> { active.send("\u001b[A"); true }
                                androidx.compose.ui.input.key.Key.DirectionDown -> { active.send("\u001b[B"); true }
                                androidx.compose.ui.input.key.Key.DirectionRight -> { active.send("\u001b[C"); true }
                                androidx.compose.ui.input.key.Key.DirectionLeft -> { active.send("\u001b[D"); true }
                                androidx.compose.ui.input.key.Key.MoveHome -> { active.send("\u001b[H"); true }
                                androidx.compose.ui.input.key.Key.MoveEnd -> { active.send("\u001b[F"); true }
                                androidx.compose.ui.input.key.Key.PageUp -> { active.send("\u001b[5~"); true }
                                androidx.compose.ui.input.key.Key.PageDown -> { active.send("\u001b[6~"); true }
                                androidx.compose.ui.input.key.Key.Delete -> { active.send("\u001b[3~"); true }
                                androidx.compose.ui.input.key.Key.Tab -> { active.send("\t"); true }
                                androidx.compose.ui.input.key.Key.Escape -> { active.send("\u001b"); true }
                                androidx.compose.ui.input.key.Key.Backspace -> { active.send("\u007f"); true }
                                else -> false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        try { active.send("\r") } catch (e: Exception) { error = e.message ?: "Terminal input failed." }
                        command = ""
                    }),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent, fontFamily = FontFamily.Monospace, fontSize = terminalFontSize.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                    decorationBox = { inner -> inner() }
                )

            }
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
        }
    }

    if (renameTabId != 0) {
        AlertDialog(
            onDismissRequest = { renameTabId = 0 },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(48) },
                    singleLine = true,
                    label = { Text("Session name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LinuxTerminalSessions.tabs.firstOrNull { it.id == renameTabId }?.title?.value =
                        renameValue.trim().ifBlank { "Terminal" }
                    renameTabId = 0
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTabId = 0 }) { Text("Cancel") } }
        )
    }

    fingerprintConfirmation?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = { fingerprintConfirmation = null; expectedFingerprint = "" },
            title = { Text("Verify SSH server") },
            text = {
                Text("First connection to $host presented this SHA-256 host key:\n\n$fingerprint\n\nOnly trust it if it matches the fingerprint provided by your server administrator. No shell commands have been sent.", color = Color(0xFFE8EDF5))
            },
            confirmButton = {
                Button(onClick = {
                    fingerprintConfirmation = null
                    sshHostKeyPrefs.edit().putString("${host.trim()}:${port.toIntOrNull() ?: 22}", fingerprint).apply()
                    expectedFingerprint = fingerprint
                    connectSsh(fingerprint)
                }) { Text("Trust fingerprint and connect") }
            },
            dismissButton = {
                TextButton(onClick = { fingerprintConfirmation = null; expectedFingerprint = "" }) { Text("Cancel") }
            }
        )
    }
}

