package com.techwithkaushik.cppide.feature.terminal

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.OutlinedTextField
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
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimeSmokeTest
import com.techwithkaushik.cppide.integration.linuxruntime.ProotDownloader
import com.techwithkaushik.cppide.integration.linuxruntime.UbuntuBaseDownloader
import com.techwithkaushik.cppide.integration.linuxruntime.RootfsInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
fun RuntimeSetupPanel(
    message: String,
    onRuntimeReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val paths = remember(context) { LinuxRuntimePaths(context.filesDir) }
    val scope = rememberCoroutineScope()
    var rootfsUri by remember { mutableStateOf<Uri?>(null) }
    var rootfsSha by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember(message) { mutableStateOf(message) }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "selected-file")

    suspend fun copySelected(uri: Uri, label: String): File = withContext(Dispatchers.IO) {
        val name = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.cacheDir, "$label-${System.currentTimeMillis()}-$name")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Android could not open selected file.")
        input.use { source ->
            file.outputStream().buffered().use { target ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMPORT_BYTES) throw IOException("Selected file exceeds the 4 GiB import limit.")
                    target.write(buffer, 0, n)
                }
            }
        }
        file
    }

    val rootfsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { rootfsUri = it }
    LaunchedEffect(context) {
        status = withContext(Dispatchers.IO) {
            when {
                !paths.hasRootfs() -> "Ubuntu rootfs is not installed."
                !paths.hasProot() -> "Ubuntu rootfs is present; PRoot is missing."
                else -> {
                    val smoke = LinuxRuntimeSmokeTest(paths).run()
                    if (smoke.success) "Ubuntu shell smoke test passed."
                    else "Ubuntu runtime validation failed: ${smoke.error ?: smoke.output}"
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ubuntu Linux runtime", color = Color(0xFF8CC8FF), fontSize = 18.sp)
        Text(
            "This branch does not use Termux or its app-private prefix. Rootfs is stored under this app's files/linux-runtime directory. PRoot v5.5.0 and its Android loader are bundled in the APK to satisfy Android 10+ executable-file restrictions.",
            color = Color(0xFFE8EDF5), fontSize = 12.sp
        )
        Button(enabled = !busy, onClick = {
            busy = true
            status = "Downloading official Ubuntu Base and verifying SHA256SUMS…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val installed = UbuntuBaseDownloader(paths).downloadAndInstall()
                        "Ubuntu Base installed and SHA-256 verified (${installed.bytes / (1024 * 1024)} MiB). Install PRoot to launch a local shell."
                    } catch (e: Exception) {
                        "Ubuntu download failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                status = result
                busy = false
                if (paths.ready()) {
                    val smoke = withContext(Dispatchers.IO) { LinuxRuntimeSmokeTest(paths).run() }
                    if (smoke.success) {
                        status = "Ubuntu shell smoke test passed."
                        onRuntimeReady()
                    } else {
                        status = "Runtime files are present, but Ubuntu shell execution failed: ${smoke.error ?: smoke.output}"
                    }
                }
            }
        }) { Text("Download official Ubuntu Base 24.04.5") }
        Button(enabled = !busy, onClick = {
            busy = true
            status = "Verifying APK-bundled Android PRoot v5.5.0…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val installed = ProotDownloader(paths).downloadAndInstall()
                        "${installed.release} installed (SHA-256 ${installed.sha256.take(16)}…). The upstream asset currently publishes no SHA-256 digest; the release URL and version are pinned."
                    } catch (e: Exception) {
                        "Bundled PRoot validation failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                status = result
                busy = false
                if (paths.ready()) {
                    val smoke = withContext(Dispatchers.IO) { LinuxRuntimeSmokeTest(paths).run() }
                    if (smoke.success) {
                        status = "Ubuntu shell smoke test passed."
                        onRuntimeReady()
                    } else {
                        status = "Runtime files are present, but Ubuntu shell execution failed: ${smoke.error ?: smoke.output}"
                    }
                }
            }
        }) { Text("Verify bundled Android PRoot v5.5.0") }
        Text("Ubuntu rootfs checksum is verified against Ubuntu's official SHA256SUMS. PRoot v5.5.0 is built from upstream source for Android by CI and bundled in the APK.", color = Color(0xFF9AA7B5), fontSize = 11.sp)

        Button(enabled = !busy, onClick = { rootfsPicker.launch(arrayOf("application/gzip", "application/x-xz", "application/x-tar", "application/octet-stream", "*/*")) }) {
            Text(if (rootfsUri == null) "1 · Select Ubuntu rootfs archive" else "Change Ubuntu rootfs")
        }
        Text(rootfsUri?.let(::displayName) ?: "No Ubuntu rootfs selected", color = Color(0xFF9AA7B5), fontSize = 11.sp)
        OutlinedTextField(
            value = rootfsSha,
            onValueChange = { rootfsSha = it.trim() },
            label = { Text("Rootfs SHA-256 (optional for local import)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(enabled = !busy && rootfsUri != null, onClick = {
            val uri = rootfsUri ?: return@Button
            busy = true
            status = "Validating and installing Ubuntu rootfs…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    var staged: File? = null
                    try {
                        staged = copySelected(uri, "ubuntu-rootfs")
                        val name = displayName(uri).lowercase()
                        val namedArchive = File(staged.parentFile, "rootfs-" + when {
                            name.endsWith(".tar.xz") -> "import.tar.xz"
                            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> "import.tar.gz"
                            else -> "import.tar"
                        })
                        staged.copyTo(namedArchive, overwrite = true)
                        RootfsInstaller(paths).install(namedArchive, rootfsSha.ifBlank { null })
                        namedArchive.delete()
                        if (paths.hasProot()) {
                            val smoke = LinuxRuntimeSmokeTest(paths).run()
                            if (!smoke.success) throw IOException("Ubuntu shell smoke test failed: ${smoke.error ?: smoke.output}")
                            "Ubuntu rootfs installed and shell smoke test passed."
                        } else {
                            "Ubuntu rootfs installed and validated. Install PRoot next to enable local shell execution."
                        }
                    } catch (e: Exception) {
                        "Ubuntu installation failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally { staged?.delete() }
                }
                status = result
                busy = false
                if (paths.ready()) {
                    val smoke = withContext(Dispatchers.IO) { LinuxRuntimeSmokeTest(paths).run() }
                    if (smoke.success) {
                        status = "Ubuntu shell smoke test passed."
                        onRuntimeReady()
                    } else {
                        status = "Runtime files are present, but Ubuntu shell execution failed: ${smoke.error ?: smoke.output}"
                    }
                }
            }
        }) { Text("Install Ubuntu rootfs") }

        Text(
            "PRoot v5.5.0 is bundled with the APK as an Android-native executable and loader. Android 10+ blocks running downloaded executables from writable app storage, so a user-selected PRoot archive is no longer supported. Tap the button above to verify the bundled binaries.",
            color = Color(0xFF9AA7B5), fontSize = 11.sp
        )

        if (busy) CircularProgressIndicator(color = Color(0xFF8CC8FF))
        Text("Install location: ${paths.base.absolutePath}", color = Color(0xFF9AA7B5), fontSize = 11.sp)
        Text(status, color = if (paths.ready()) Color(0xFF7DDBB0) else Color(0xFF9AA7B5), fontSize = 12.sp)
    }
}

private const val MAX_IMPORT_BYTES = 4L * 1024 * 1024 * 1024
