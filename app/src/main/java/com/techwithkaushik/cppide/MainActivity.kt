package com.techwithkaushik.cppide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.techwithkaushik.cppide.integration.termux.TermuxCommandClient
import kotlinx.coroutines.launch

/**
 * Temporary architecture-spike screen. This is deliberately not the final IDE shell.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val scope = rememberCoroutineScope()
                    var status by remember { mutableStateOf(TermuxCommandClient.inspect(context)) }
                    var probeMessage by remember { mutableStateOf("Command probe has not been run.") }
                    var running by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("C/C++ IDE — Termux integration spike", style = MaterialTheme.typography.headlineSmall)
                        Text("This diagnostic checks the authorized Termux command API only. It does not validate clangd, a terminal, or a debugger.")
                        Card {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Termux: ${if (status.installed) "detected" else "not detected"}")
                                Text("RUN_COMMAND service: ${if (status.serviceResolvable) "resolvable" else "unavailable"}")
                                Text("Permission: ${if (status.permissionGranted) "granted" else "not granted"}")
                                Text("Version: ${status.packageVersion ?: "unknown"}")
                                Text(status.message)
                            }
                        }
                        Button(
                            enabled = !running && status.installed && status.serviceResolvable && status.permissionGranted,
                            onClick = {
                                running = true
                                probeMessage = "Running fixed, harmless command probe…"
                                scope.launch {
                                    probeMessage = try {
                                        val result = TermuxCommandClient.probeCommandApi(context)
                                        "PASS — output received and exit code ${result.exitCode}. Output: ${result.stdout.take(120)}"
                                    } catch (error: Exception) {
                                        "FAIL — ${error.message ?: error.javaClass.simpleName}"
                                    } finally {
                                        running = false
                                        status = TermuxCommandClient.inspect(context)
                                    }
                                }
                            }
                        ) {
                            Text(if (running) "Probing…" else "Test Termux command access")
                        }
                        Text(probeMessage, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
