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
import com.techwithkaushik.cppide.integration.linuxruntime.ProotInstaller
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
    var prootUri by remember { mutableStateOf<Uri?>(null) }
    var rootfsSha by remember { mutableStateOf("") }
    var prootSha by remember { mutableStateOf("") }
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
    val prootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { prootUri = it }

    LaunchedEffect(context) {
        status = if (paths.ready()) {
            "Ubuntu rootfs and PRoot are present. Runtime verification is performed when a local shell starts."
        } else {
            "Ubuntu setup is incomplete. Import an Ubuntu rootfs archive and a SHA-256-verified Android PRoot release archive."
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ubuntu Linux runtime", color = Color(0xFF8CC8FF), fontSize = 18.sp)
        Text(
            "This branch does not use Termux or its app-private prefix. Rootfs and PRoot are stored under this app's files/linux-runtime directory. Import an Ubuntu .tar, .tar.gz or .tar.xz rootfs and a PRoot Android .tar.gz archive. The PRoot archive must be verified with its SHA-256 before installation.",
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
                if (paths.ready()) onRuntimeReady()
            }
        }) { Text("Download official Ubuntu Base 24.04.5") }
        Button(enabled = !busy, onClick = {
            busy = true
            status = "Downloading pinned upstream Android PRoot…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val installed = ProotDownloader(paths).downloadAndInstall()
                        "${installed.release} installed (SHA-256 ${installed.sha256.take(16)}…). The upstream asset currently publishes no SHA-256 digest; the release URL and version are pinned."
                    } catch (e: Exception) {
                        "PRoot download failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                status = result
                busy = false
                if (paths.ready()) onRuntimeReady()
            }
        }) { Text("Download pinned Android PRoot") }
        Text("Ubuntu rootfs checksum is verified against Ubuntu's official SHA256SUMS. PRoot uses the fixed upstream proot-rs v0.1.0 Android release because that upstream release does not publish an asset digest.", color = Color(0xFF9AA7B5), fontSize = 11.sp)

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
                        "Ubuntu rootfs installed and validated. Install PRoot next to enable local shell execution."
                    } catch (e: Exception) {
                        "Ubuntu installation failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally { staged?.delete() }
                }
                status = result
                busy = false
            }
        }) { Text("Install Ubuntu rootfs") }

        Button(enabled = !busy, onClick = { prootPicker.launch(arrayOf("application/gzip", "application/octet-stream", "*/*")) }) {
            Text(if (prootUri == null) "2 · Select Android PRoot .tar.gz" else "Change PRoot archive")
        }
        Text(prootUri?.let(::displayName) ?: "No PRoot archive selected", color = Color(0xFF9AA7B5), fontSize = 11.sp)
        OutlinedTextField(
            value = prootSha,
            onValueChange = { prootSha = it.trim() },
            label = { Text("Required PRoot archive SHA-256") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(enabled = !busy && prootUri != null && prootSha.matches(Regex("[a-fA-F0-9]{64}")), onClick = {
            val uri = prootUri ?: return@Button
            busy = true
            status = "Verifying and installing PRoot…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    var staged: File? = null
                    try {
                        staged = copySelected(uri, "proot")
                        val namedArchive = File(staged.parentFile, "proot-import.tar.gz")
                        staged.copyTo(namedArchive, overwrite = true)
                        ProotInstaller(paths).install(namedArchive, prootSha)
                        namedArchive.delete()
                        if (!paths.ready()) throw IOException("PRoot installed, but Ubuntu rootfs is still missing or invalid.")
                        "Ubuntu runtime files are installed. Local shell launch will be tested when Terminal opens."
                    } catch (e: Exception) {
                        "PRoot installation failed: ${e.message ?: e.javaClass.simpleName}"
                    } finally { staged?.delete() }
                }
                status = result
                busy = false
                if (paths.ready()) onRuntimeReady()
            }
        }) { Text("Verify and install PRoot") }

        if (busy) CircularProgressIndicator(color = Color(0xFF8CC8FF))
        Text("Install location: ${paths.base.absolutePath}", color = Color(0xFF9AA7B5), fontSize = 11.sp)
        Text(status, color = if (paths.ready()) Color(0xFF7DDBB0) else Color(0xFF9AA7B5), fontSize = 12.sp)
    }
}

private const val MAX_IMPORT_BYTES = 4L * 1024 * 1024 * 1024
