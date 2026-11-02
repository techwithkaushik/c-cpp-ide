package com.techwithkaushik.cppide.feature.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.integration.linuxruntime.HostKeyConfirmationRequired
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.linuxruntime.LocalLinuxSession
import com.techwithkaushik.cppide.integration.linuxruntime.SshHost
import com.techwithkaushik.cppide.integration.linuxruntime.SshShellSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.ByteArrayOutputStream

private data class LinuxTerminalTab(
    val id: Int,
    val title: String,
    val output: MutableState<String>,
    val screen: TerminalScreen,
    val send: (String) -> Unit,
    val interrupt: () -> Unit,
    val session: AutoCloseable,
    val exited: MutableState<Int?>,
)

private object LinuxTerminalSessions {
    val tabs = mutableStateListOf<LinuxTerminalTab>()
    var selectedId by mutableIntStateOf(0)
        private set
    var onLastSessionExited: (() -> Unit)? = null
    private var nextId = 1

    fun select(id: Int) { if (tabs.any { it.id == id }) selectedId = id }

    fun add(title: String, session: AutoCloseable, input: OutputStream, interrupt: () -> Unit, initialOutput: String = ""): LinuxTerminalTab {
        val screen = TerminalScreen()
        screen.append(initialOutput)
        val tab = LinuxTerminalTab(
            id = nextId++,
            title = title,
            output = mutableStateOf(screen.render()),
            screen = screen,
            send = { text -> input.write(text.toByteArray(Charsets.UTF_8)); input.flush() },
            interrupt = interrupt,
            session = session,
            exited = mutableStateOf(null)
        )
        tabs.add(tab)
        selectedId = tab.id
        return tab
    }

    fun append(tab: LinuxTerminalTab, text: String) {
        if (tabs.none { it.id == tab.id }) return
        tab.screen.append(text)
        tab.output.value = tab.screen.render()
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
    val sshHostKeyPrefs = remember(context) { context.getSharedPreferences("trusted_ssh_host_keys", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }
    var command by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSshDialog by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateKeyUri by remember { mutableStateOf<Uri?>(null) }
    var privateKeyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var privateKeyPassphrase by remember { mutableStateOf("") }
    var expectedFingerprint by remember { mutableStateOf("") }
    var fingerprintConfirmation by remember { mutableStateOf<String?>(null) }

    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        privateKeyUri = uri
        privateKeyBytes = null
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > 128 * 1024) throw IllegalStateException("Private key file must be at most 128 KiB.")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: throw IllegalStateException("Could not read the selected private key.")
                        if (bytes.isEmpty()) throw IllegalStateException("The selected private key file is empty.")
                        Result.success(bytes)
                    } catch (e: Exception) { Result.failure<ByteArray>(e) }
                }
                result.onSuccess { privateKeyBytes = it }
                    .onFailure { error = it.message ?: "Could not load private key." }
            }
        }
    }

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
                val tab = LinuxTerminalSessions.add(title, session, session.input, interrupt = { runCatching { session.input.write(byteArrayOf(3)); session.input.flush() } }, initialOutput = "Connected to ${sshHost.host}:\n")
                session.readOutput(
                    onChunk = { chunk -> handler.post { LinuxTerminalSessions.append(tab, chunk) } },
                    onExit = { code -> handler.post { LinuxTerminalSessions.append(tab, "\n[SSH session exited: $code]\n"); LinuxTerminalSessions.finish(tab) } }
                )
                showSshDialog = false
                password = ""
                privateKeyBytes = null
                privateKeyUri = null
                privateKeyPassphrase = ""
            }.onFailure { e ->
                if (e is HostKeyConfirmationRequired) {
                    fingerprintConfirmation = e.fingerprint
                    expectedFingerprint = e.fingerprint
                } else error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    Column(modifier.fillMaxSize().background(Color(0xFF0D1117)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                if (!paths.ready()) {
                    error = if (!paths.hasRootfs()) "Ubuntu rootfs is not installed. Open Runtime Settings."
                    else "The app-owned PRoot binary is missing or invalid. Open Runtime Settings."
                    return@Button
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
                        val tab = LinuxTerminalSessions.add("Ubuntu ${LinuxTerminalSessions.tabs.size + 1}", session, session.input, interrupt = { session.interrupt() }, initialOutput = "Ubuntu shell started.\n")
                        session.readOutput(
                            onChunk = { chunk -> handler.post { LinuxTerminalSessions.append(tab, chunk) } },
                            onExit = { code -> handler.post {
                    tab.exited.value = code
                    LinuxTerminalSessions.append(tab, "\r\n[Shell exited: $code — output preserved. Close this tab to dismiss.]\r\n")
                } }
                        )
                    }.onFailure { error = it.message ?: "Could not start Ubuntu shell." }
                }
            }) { Text("Local Ubuntu") }
            OutlinedButton(enabled = !busy, onClick = { showSshDialog = true }) { Text("Connect SSH") }
            if (busy) Text("Connecting…", color = Color(0xFF9AA7B5), modifier = Modifier.padding(top = 12.dp))
        }

        if (LinuxTerminalSessions.tabs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LinuxTerminalSessions.tabs.forEach { tab ->
                    Row(
                        Modifier.background(if (tab.id == LinuxTerminalSessions.selectedId) Color(0xFF173D5C) else Color(0xFF151B23), RoundedCornerShape(8.dp))
                            .clickable { LinuxTerminalSessions.select(tab.id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(tab.title, color = Color(0xFFE8EDF5))
                        Text("×", color = Color(0xFF9AA7B5), modifier = Modifier.clickable { LinuxTerminalSessions.closeTab(tab.id) })
                    }
                }
            }
        }

        val active = LinuxTerminalSessions.tabs.firstOrNull { it.id == LinuxTerminalSessions.selectedId }
        if (active == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linux terminal", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Choose Local Ubuntu to open a persistent shell in the app-owned rootfs, or Connect SSH to open a remote shell directly. No setup shell is required before SSH.", color = Color(0xFF9AA7B5), fontSize = 12.sp)
                Button(onClick = onOpenSettings) { Text("Ubuntu runtime setup") }
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            }
        } else {
            val outputScrollState = rememberScrollState()
            LaunchedEffect(active.output.value) {
                outputScrollState.scrollTo(outputScrollState.maxValue)
            }
            Column(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF080B10), RoundedCornerShape(8.dp)).padding(8.dp)) {
                Text(
                    active.output.value,
                    color = Color(0xFFE6EDF3),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(outputScrollState)
                )
                active.exited.value?.let { code ->
                    Text("Process exited (code $code). Output is preserved; close this tab to dismiss.", color = Color(0xFFFFAB91), fontSize = 11.sp)
                }
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
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF151B23), RoundedCornerShape(6.dp)).padding(12.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || active.exited.value != null) return@onPreviewKeyEvent false
                            when (event.key) {
                                androidx.compose.ui.input.key.Key.Enter -> {
                                    try { active.send("\r") } catch (e: Exception) { error = e.message ?: "Terminal input failed." }
                                    command = ""
                                    true
                                }
                                androidx.compose.ui.input.key.Key.DirectionUp -> { active.send("\u001b[A"); true }
                                androidx.compose.ui.input.key.Key.DirectionDown -> { active.send("\u001b[B"); true }
                                androidx.compose.ui.input.key.Key.DirectionRight -> { active.send("\u001b[C"); true }
                                androidx.compose.ui.input.key.Key.DirectionLeft -> { active.send("\u001b[D"); true }
                                androidx.compose.ui.input.key.Key.Tab -> { active.send("\t"); true }
                                else -> false
                            }
                        },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFE6EDF3), fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF6EA8E0)),
                    decorationBox = { inner ->
                        if (command.isEmpty()) Text(if (active.exited.value == null) "Type directly into terminal…" else "Shell has exited", color = Color(0xFF9AA7B5), fontSize = 12.sp)
                        inner()
                    }
                )

            }
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
        }
    }

    if (showSshDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showSshDialog = false },
            title = { Text("Connect directly over SSH") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SSH opens a remote shell directly; local Ubuntu setup is not required.", fontSize = 12.sp)
                    OutlinedTextField(host, { host = it; expectedFingerprint = "" }, label = { Text("Host / IP") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5); expectedFingerprint = "" }, label = { Text("Port") }, singleLine = true)
                    OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(password, { password = it }, label = { Text("Password (optional if using key)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedButton(onClick = { privateKeyPicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*")) }) {
                        Text(if (privateKeyUri == null) "Select private key (optional)" else "Change private key")
                    }
                    privateKeyUri?.let { Text(if (privateKeyBytes == null) "Reading private key…" else "Private key loaded into memory", color = Color(0xFF9AA7B5), fontSize = 10.sp) }
                    if (privateKeyBytes != null) {
                        OutlinedTextField(privateKeyPassphrase, { privateKeyPassphrase = it }, label = { Text("Key passphrase (if encrypted)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    }
                    expectedFingerprint.takeIf { it.isNotBlank() }?.let {
                        Text("Trusted host fingerprint: $it", color = Color(0xFF9AA7B5), fontSize = 10.sp)
                    }
                    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 11.sp) }
                }
            },
            confirmButton = { Button(enabled = !busy, onClick = { connectSsh(expectedFingerprint.ifBlank { null }) }) { Text(if (busy) "Connecting…" else "Connect") } },
            dismissButton = { TextButton(onClick = { showSshDialog = false }) { Text("Cancel") } }
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
                TextButton(onClick = { fingerprintConfirmation = null; expectedFingerprint = ""; showSshDialog = true }) { Text("Cancel") }
            }
        )
    }
}

