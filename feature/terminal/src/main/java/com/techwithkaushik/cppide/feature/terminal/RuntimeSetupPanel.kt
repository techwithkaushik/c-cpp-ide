package com.techwithkaushik.cppide.feature.terminal

import android.net.Uri
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
import java.io.File
import java.io.IOException

@Composable
fun RuntimeSetupPanel(
    onRuntimeReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var selectedArchive by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checkingInstalledRuntime by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Checking runtime…") }

    val verifyInstalledRuntime: () -> Unit = {
        if (!busy && !checkingInstalledRuntime) {
            checkingInstalledRuntime = true
            status = "Checking runtime…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val runtime = EmbeddedTermuxRuntime(context.filesDir)
                        if (!runtime.hasBootstrapLayout()) {
                            "No bootstrap installed."
                        } else {
                            val smoke = BootstrapSmokeTest(runtime).run()
                            if (smoke.success) {
                                "Runtime verified."
                            } else {
                                val detail = listOfNotNull(
                                    smoke.failureReason,
                                    smoke.stderr.trim().takeIf { it.isNotBlank() }?.let { "stderr: ${it.take(400)}" }
                                ).joinToString(" — ")
                                "Runtime verification failed: $detail"
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
            if (uri != null) status = "Bootstrap ZIP selected."
        }
    )


    val installBootstrap: () -> Unit = {
        val uri = selectedArchive
        if (uri != null && !busy && !checkingInstalledRuntime) {
            busy = true
            status = "Installing bootstrap…"
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
                            val installed = runtime.installBootstrap(
                                archive = stagedArchive,
                                expectedSha256 = null,
                                processSymlinks = true,
                            ) {
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
                if (result.startsWith("Bootstrap installed")) onRuntimeReady()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Runtime & Bootstrap", color = Color(0xFF8CC8FF), fontSize = 18.sp)
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
            onClick = { installBootstrap() }
        ) {
            Text(if (busy) "Setting up terminal…" else "Install bootstrap")
        }
        if (busy || checkingInstalledRuntime) CircularProgressIndicator(color = Color(0xFF8CC8FF))
        Text(
            status,
            color = if (
                status.startsWith("Bootstrap installed") ||
                status.startsWith("Runtime verified")
            ) Color(0xFF7DDBB0) else Color(0xFF9AA7B5),
            fontSize = 12.sp
        )
    }
}

private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
