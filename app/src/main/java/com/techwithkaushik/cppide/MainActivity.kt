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
 * Integration milestone screen: checks Termux and can compile/run a source file accessible to
 * Termux. This is not yet the full editor/workspace UI.
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
                    var language by remember { mutableStateOf(TermuxSourceLanguage.C) }
                    var sourcePath by remember { mutableStateOf("/sdcard/Download/main.c") }
                    var outputPath by remember { mutableStateOf("/data/data/com.termux/files/home/cppide-main") }
                    var workingDirectory by remember { mutableStateOf("/sdcard/Download") }
                    var programArguments by remember { mutableStateOf("") }
                    var running by remember { mutableStateOf(false) }
                    var output by remember { mutableStateOf("Choose a source file already accessible to Termux, then tap Compile & Run.") }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("C/C++ IDE", style = MaterialTheme.typography.headlineMedium)
                        Text("Compile and run through Termux's documented RUN_COMMAND API. Source files are not copied or edited by this screen.")
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Termux: ${if (status.installed) "detected" else "not detected"}")
                                Text("RUN_COMMAND service: ${if (status.serviceResolvable) "available" else "unavailable"}")
                                Text("Permission: ${if (status.permissionGranted) "granted" else "not granted"}")
                                Text("Version: ${status.packageVersion ?: "unknown"}")
                                Text(status.message)
                                Button(
                                    enabled = !running,
                                    onClick = { status = TermuxCommandClient.inspect(context) }
                                ) { Text("Refresh status") }
                            }
                        }

                        Text("Source settings", style = MaterialTheme.typography.titleLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !running && language != TermuxSourceLanguage.C,
                                onClick = {
                                    language = TermuxSourceLanguage.C
                                    if (sourcePath.endsWith(".cpp") || sourcePath.endsWith(".cc") || sourcePath.endsWith(".cxx")) {
                                        sourcePath = sourcePath.substringBeforeLast('.') + ".c"
                                    }
                                }
                            ) { Text("C17") }
                            Button(
                                enabled = !running && language != TermuxSourceLanguage.CPP,
                                onClick = {
                                    language = TermuxSourceLanguage.CPP
                                    if (sourcePath.endsWith(".c")) {
                                        sourcePath = sourcePath.substringBeforeLast('.') + ".cpp"
                                    }
                                }
                            ) { Text("C++20") }
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
                        Text(
                            "Note: Termux must have access to the source/work directory. On shared storage, run termux-setup-storage in Termux if needed. The output executable stays in Termux private storage to avoid noexec mounts."
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running && status.installed && status.serviceResolvable && status.permissionGranted,
                            onClick = {
                                running = true
                                output = "Submitting compile request to Termux…"
                                scope.launch {
                                    output = try {
                                        val args = programArguments
                                            .lineSequence()
                                            .filter { it.isNotEmpty() }
                                            .toList()
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
                                            if (outcome.runResult != null) {
                                                appendLine()
                                                appendLine(if (outcome.executionSucceeded) "RUN: PASS" else "RUN: FAIL")
                                                appendLine("Exit code: ${outcome.runResult.exitCode ?: "unknown"}")
                                                if (outcome.runResult.stdout.isNotBlank()) {
                                                    appendLine("Program output:")
                                                    appendLine(outcome.runResult.stdout)
                                                }
                                                if (outcome.runResult.stderr.isNotBlank()) {
                                                    appendLine("Program stderr:")
                                                    appendLine(outcome.runResult.stderr)
                                                }
                                            } else {
                                                appendLine()
                                                appendLine("Program was not run because compilation did not succeed.")
                                            }
                                        }.trim()
                                    } catch (error: Exception) {
                                        "FAILED — ${error.message ?: error.javaClass.simpleName}\n\nA result timeout stops waiting for the result; it does not guarantee the Termux process was terminated."
                                    } finally {
                                        running = false
                                        status = TermuxCommandClient.inspect(context)
                                    }
                                }
                            }
                        ) {
                            Text(if (running) "Working…" else "Compile & Run")
                        }
                        Text("Result", style = MaterialTheme.typography.titleLarge)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                output,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
