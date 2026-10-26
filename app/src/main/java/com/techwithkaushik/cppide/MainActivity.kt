package com.techwithkaushik.cppide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.techwithkaushik.cppide.integration.termux.TermuxBuildRequest
import com.techwithkaushik.cppide.integration.termux.TermuxBuildRunner
import com.techwithkaushik.cppide.integration.termux.TermuxCommandClient
import com.techwithkaushik.cppide.integration.termux.TermuxSourceLanguage
import kotlinx.coroutines.launch

/**
 * Termux integration screen. It probes the documented command API automatically and can compile
 * a source file accessible to Termux. The probe does not launch Termux's terminal UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    var status by remember { mutableStateOf(TermuxCommandClient.inspect(context)) }
                    var commandApiReady by remember { mutableStateOf(false) }
                    var connecting by remember { mutableStateOf(false) }
                    var connectionMessage by remember {
                        mutableStateOf("Checking Termux command access…")
                    }
                    var language by remember { mutableStateOf(TermuxSourceLanguage.C) }
                    var sourcePath by remember { mutableStateOf("/sdcard/Download/main.c") }
                    var outputPath by remember { mutableStateOf("/data/data/com.termux/files/home/cppide-main") }
                    var workingDirectory by remember { mutableStateOf("/sdcard/Download") }
                    var programArguments by remember { mutableStateOf("") }
                    var running by remember { mutableStateOf(false) }
                    var output by remember { mutableStateOf("Choose a source file accessible to Termux, then tap Compile & Run.") }

                    fun connectToTermux() {
                        status = TermuxCommandClient.inspect(context)
                        if (!status.installed || !status.serviceResolvable || !status.permissionGranted) {
                            commandApiReady = false
                            connectionMessage = status.message
                            return
                        }
                        connecting = true
                        commandApiReady = false
                        connectionMessage = "Connecting to Termux without opening its terminal UI…"
                        scope.launch {
                            try {
                                TermuxCommandClient.probeCommandApi(context)
                                commandApiReady = true
                                connectionMessage = "Connected. Termux command execution is ready."
                            } catch (error: Exception) {
                                commandApiReady = false
                                connectionMessage = "Connection failed: ${error.message ?: error.javaClass.simpleName}. Check Termux's allow-external-apps setting and RUN_COMMAND permission."
                            } finally {
                                connecting = false
                                status = TermuxCommandClient.inspect(context)
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        connectToTermux()
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("C/C++ IDE", style = MaterialTheme.typography.headlineMedium)
                        Text("Build and run from this IDE through Termux's documented command API. You do not need to open the Termux terminal for each build. Termux must be installed and configured once.")
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Termux: ${if (status.installed) "detected" else "not detected"}")
                                Text("RUN_COMMAND service: ${if (status.serviceResolvable) "available" else "unavailable"}")
                                Text("Permission: ${if (status.permissionGranted) "granted" else "not granted"}")
                                Text("Version: ${status.packageVersion ?: "unknown"}")
                                Text("Command API: ${if (commandApiReady) "ready" else if (connecting) "connecting…" else "not ready"}")
                                Text(connectionMessage)
                                Button(
                                    enabled = !running && !connecting,
                                    onClick = { connectToTermux() }
                                ) { Text(if (connecting) "Connecting…" else "Connect / retry") }
                            }
                        }

                        Text("Source settings", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !running,
                                onClick = {
                                    language = TermuxSourceLanguage.C
                                    if (sourcePath.endsWith(".cpp") || sourcePath.endsWith(".cc") || sourcePath.endsWith(".cxx")) {
                                        sourcePath = sourcePath.substringBeforeLast('.') + ".c"
                                    }
                                }
                            ) { Text(if (language == TermuxSourceLanguage.C) "✓ C17" else "C17") }
                            Button(
                                enabled = !running,
                                onClick = {
                                    language = TermuxSourceLanguage.CPP
                                    if (sourcePath.endsWith(".c")) {
                                        sourcePath = sourcePath.substringBeforeLast('.') + ".cpp"
                                    }
                                }
                            ) { Text(if (language == TermuxSourceLanguage.CPP) "✓ C++20" else "C++20") }
                        }
                        OutlinedTextField(
                            value = sourcePath,
                            onValueChange = { sourcePath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Source file path") },
                            singleLine = true,
                            enabled = !running
                        )
                        OutlinedTextField(
                            value = outputPath,
                            onValueChange = { outputPath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Output executable path (Termux home/usr/tmp)") },
                            singleLine = true,
                            enabled = !running
                        )
                        OutlinedTextField(
                            value = workingDirectory,
                            onValueChange = { workingDirectory = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Working directory") },
                            singleLine = true,
                            enabled = !running
                        )
                        OutlinedTextField(
                            value = programArguments,
                            onValueChange = { programArguments = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Program arguments (one argument per line)") },
                            minLines = 2,
                            enabled = !running
                        )
                        Text("Termux must be able to read the source and working directory. For shared storage, one-time storage permission setup may be required. Compiled executables stay in Termux private storage to avoid noexec mounts.")
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running && !connecting && commandApiReady,
                            onClick = {
                                running = true
                                output = "Submitting compile request…"
                                scope.launch {
                                    output = try {
                                        val args = programArguments.lineSequence().filter { it.isNotEmpty() }.toList()
                                        val outcome = TermuxBuildRunner.compileAndRun(
                                            context,
                                            TermuxBuildRequest(
                                                sourcePath = sourcePath.trim(),
                                                outputPath = outputPath.trim(),
                                                workingDirectory = workingDirectory.trim(),
                                                language = language,
                                                programArguments = args
                                            )
                                        )
                                        buildString {
                                            appendLine(if (outcome.compilationSucceeded) "COMPILE: PASS" else "COMPILE: FAIL")
                                            appendLine("Exit code: ${outcome.compileResult.exitCode ?: "unknown"}")
                                            if (outcome.compileResult.stdout.isNotBlank()) {
                                                appendLine("Compiler output:")
                                                appendLine(outcome.compileResult.stdout)
                                            }
                                            if (outcome.compileResult.stderr.isNotBlank()) {
                                                appendLine("Compiler diagnostics:")
                                                appendLine(outcome.compileResult.stderr)
                                            }
                                            val runResult = outcome.runResult
                                            if (runResult != null) {
                                                appendLine()
                                                appendLine(if (outcome.executionSucceeded) "RUN: PASS" else "RUN: FAIL")
                                                appendLine("Exit code: ${runResult.exitCode ?: "unknown"}")
                                                if (runResult.stdout.isNotBlank()) {
                                                    appendLine("Program output:")
                                                    appendLine(runResult.stdout)
                                                }
                                                if (runResult.stderr.isNotBlank()) {
                                                    appendLine("Program stderr:")
                                                    appendLine(runResult.stderr)
                                                }
                                            } else {
                                                appendLine()
                                                appendLine("Program was not run because compilation did not succeed.")
                                            }
                                        }.trim()
                                    } catch (error: Exception) {
                                        "FAILED — ${error.message ?: error.javaClass.simpleName}\n\nA result timeout stops waiting; it does not guarantee the Termux process was terminated."
                                    } finally {
                                        running = false
                                        status = TermuxCommandClient.inspect(context)
                                    }
                                }
                            }
                        ) { Text(if (running) "Working…" else "Compile & Run") }
                        Text("Result", style = MaterialTheme.typography.titleLarge)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(output, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
