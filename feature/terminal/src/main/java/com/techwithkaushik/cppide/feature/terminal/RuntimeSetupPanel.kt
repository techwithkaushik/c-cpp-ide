package com.techwithkaushik.cppide.feature.terminal

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
internal fun RuntimeSetupPanel(
    message: String,
    onRuntimeReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var selectedArchive by remember { mutableStateOf<Uri?>(null) }
    var sha256 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember(message) { mutableStateOf(message) }

    val archivePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            selectedArchive = uri
            if (uri != null) status = "Archive selected. Enter the SHA-256 from the workflow's SHA256SUMS file."
        }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Embedded terminal setup", color = Color(0xFF8CC8FF), fontSize = 16.sp)
        Text(
            "A private Linux runtime is required. Import the app-specific bootstrap ZIP from the GitHub Actions artifact and enter its published SHA-256. Your existing Termux installation is not modified.",
            color = Color(0xFFE8EDF5),
            fontSize = 12.sp
        )
        Button(
            enabled = !busy,
            onClick = { archivePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        ) {
            Text(if (selectedArchive == null) "Select bootstrap ZIP" else "Choose another ZIP")
        }
        OutlinedTextField(
            value = sha256,
            onValueChange = { sha256 = it.filterNot(Char::isWhitespace).lowercase() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("SHA-256 checksum") },
            supportingText = { Text("64 hexadecimal characters from SHA256SUMS") },
            singleLine = true
        )
        Button(
            enabled = !busy && selectedArchive != null && sha256.matches(Regex("^[a-f0-9]{64}$")),
            onClick = {
                val uri = selectedArchive ?: return@Button
                busy = true
                status = "Verifying and installing bootstrap…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val stagedArchive = File(context.cacheDir, "selected-termux-bootstrap.zip")
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
                                        if (total > MAX_ARCHIVE_BYTES) {
                                            throw IOException("Bootstrap ZIP exceeds the 512 MiB import limit")
                                        }
                                        target.write(buffer, 0, count)
                                    }
                                }
                            }
                            val runtime = EmbeddedTermuxRuntime(context.filesDir)
                            var smoke: BootstrapSmokeTest.Result? = null
                            try {
                                val installed = runtime.installBootstrap(stagedArchive, sha256) {
                                    runtime.layout.ensurePrivateDirectories()
                                    smoke = BootstrapSmokeTest(runtime).run()
                                    smoke?.success == true
                                }
                                "Bootstrap verified (${installed.sha256.take(12)}…). Shell execution test passed."
                            } catch (error: Exception) {
                                val reason = smoke?.failureReason
                                if (reason != null) {
                                    "Setup failed: $reason. The previous runtime, if any, was restored."
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
                    if (result.startsWith("Bootstrap verified")) onRuntimeReady()
                }
            }
        ) {
            Text("Verify and install")
        }
        if (busy) CircularProgressIndicator(color = Color(0xFF8CC8FF))
        Text(status, color = if (status.startsWith("Bootstrap verified")) Color(0xFF7DDBB0) else Color(0xFF9AA7B5), fontSize = 12.sp)
    }
}

private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
