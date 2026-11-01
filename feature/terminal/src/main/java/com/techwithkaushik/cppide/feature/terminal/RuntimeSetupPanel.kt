package com.techwithkaushik.cppide.feature.terminal

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import com.techwithkaushik.cppide.integration.termuxruntime.BootstrapSmokeTest
import com.techwithkaushik.cppide.integration.termuxruntime.EmbeddedTermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

@Composable
fun RuntimeSetupPanel(
    message: String,
    onRuntimeReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var selectedArchive by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checkingInstalledRuntime by remember { mutableStateOf(false) }
    var status by remember(message) { mutableStateOf(message) }

    val supportedAbis = remember { Build.SUPPORTED_ABIS.toSet() }
    val verifyInstalledRuntime: () -> Unit = {
        if (!busy && !checkingInstalledRuntime) {
            checkingInstalledRuntime = true
            status = "Checking the installed runtime and executing a fixed shell smoke test…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val runtime = EmbeddedTermuxRuntime(context.filesDir)
                        if (!runtime.hasBootstrapLayout()) {
                            "No complete bootstrap is installed. Select the matching bootstrap.zip and bootstrap.json files below."
                        } else {
                            val smoke = BootstrapSmokeTest(runtime).run()
                            if (smoke.success) {
                                "Runtime verified. Shell execution passed."
                            } else {
                                val detail = listOfNotNull(
                                    smoke.failureReason,
                                    smoke.stderr.trim().takeIf { it.isNotBlank() }?.let { "stderr: ${it.take(400)}" }
                                ).joinToString(" — ")
                                "Runtime verification failed: $detail. Select the matching bootstrap files below to repair it."
                            }
                        }
                    } catch (error: Exception) {
                        "Runtime check failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
                status = result
                checkingInstalledRuntime = false
            }
        }
    }

    LaunchedEffect(context) { verifyInstalledRuntime() }

    val archivePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            selectedArchive = uri
            if (uri != null) status = "Bootstrap ZIP selected. Ready to install with Termux-style symlink setup."
        }
    )


    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Main terminal setup", color = Color(0xFF8CC8FF), fontSize = 18.sp)
        Text(
            "Install the app-private Termux-compatible environment. Select the bootstrap ZIP built for this app and device ABI. Installation extracts files, replays SYMLINKS.txt, restores executable permissions, and tests the shell. Your existing Termux installation is not touched.",
            color = Color(0xFFE8EDF5),
            fontSize = 12.sp
        )
        Button(enabled = !busy && !checkingInstalledRuntime, onClick = verifyInstalledRuntime) {
            Text(if (checkingInstalledRuntime) "Checking runtime…" else "Check terminal runtime")
        }
        Button(
            enabled = !busy && !checkingInstalledRuntime,
            onClick = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        ) {
            Text(if (selectedArchive == null) "1 · Select bootstrap.zip" else "Change bootstrap ZIP")
        }
        Text(
            selectedArchive?.lastPathSegment?.substringAfterLast('/') ?: "No bootstrap ZIP selected",
            color = Color(0xFF9AA7B5),
            fontSize = 11.sp
        )
        Button(
            enabled = !busy && !checkingInstalledRuntime && selectedArchive != null,
            onClick = {
                val uri = selectedArchive ?: return@Button
                busy = true
                status = "Installing bootstrap, applying SYMLINKS.txt, and testing the shell…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val stagedArchive = File(context.cacheDir, "selected-code-studio-bootstrap.zip")
                        try {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: throw IOException("Android could not open the selected archive")
                            input.use { source ->
                                stagedArchive.outputStream().buffered().use { target ->
                                    val buffer = ByteArray(8192)
                                    var total = 0L
                                    while (true) {
                                        val count = source.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        if (total > MAX_ARCHIVE_BYTES) throw IOException("Bootstrap ZIP exceeds the 512 MiB import limit")
                                        target.write(buffer, 0, count)
                                    }
                                }
                            }
                            val runtime = EmbeddedTermuxRuntime(context.filesDir)
                            var smoke: BootstrapSmokeTest.Result? = null
                            try {
                                val installed = runtime.installBootstrap(stagedArchive, null) {
                                    runtime.layout.ensurePrivateDirectories()
                                    smoke = BootstrapSmokeTest(runtime).run()
                                    smoke?.success == true
                                }
                                "Bootstrap installed and verified. Created ${installed.symlinksCreated} symlinks; shell execution passed."
                            } catch (error: Exception) {
                                val failedSmoke = smoke
                                val reason = failedSmoke?.failureReason
                                if (reason != null) {
                                    val stderr = failedSmoke.stderr.trim().takeIf { it.isNotBlank() }
                                    "Setup failed: $reason" +
                                        (stderr?.let { " — stderr: ${it.take(400)}" } ?: "") +
                                        ". The previous runtime, if any, was restored."
                                } else {
                                    throw error
                                }
                            }
                        } catch (error: Exception) {
                            "Setup failed: ${error.message ?: error.javaClass.simpleName}"
                        } finally {
                            stagedArchive.delete()
                        }
                    }
                    status = result
                    busy = false
                    if (result.startsWith("Bootstrap installed and verified")) onRuntimeReady()
                }
            }
        ) {
            Text(if (busy) "Setting up terminal…" else "3 · Install and test main terminal")
        }
        if (busy || checkingInstalledRuntime) CircularProgressIndicator(color = Color(0xFF8CC8FF))
        Text(
            status,
            color = if (
                status.startsWith("Bootstrap installed and verified") ||
                status.startsWith("Runtime verified")
            ) Color(0xFF7DDBB0) else Color(0xFF9AA7B5),
            fontSize = 12.sp
        )
    }
}

private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
