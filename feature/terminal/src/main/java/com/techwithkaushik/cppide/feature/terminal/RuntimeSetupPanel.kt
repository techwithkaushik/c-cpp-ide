package com.techwithkaushik.cppide.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
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
    onRuntimeReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val paths = remember(context) { LinuxRuntimePaths(context.filesDir, context.applicationInfo.nativeLibraryDir) }
    val scope = rememberCoroutineScope()
    var rootfsUri by remember { mutableStateOf<Uri?>(null) }
    var rootfsSha by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Checking Ubuntu runtime…") }

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
            val prootCheck = runCatching {
                // Materializes APK native-library links and validates the ELF ABI.
                ProotDownloader(paths).downloadAndInstall()
            }
            when {
                !paths.hasRootfs() -> "Ubuntu rootfs is not installed or incomplete."
                prootCheck.isFailure -> runtimeFailureMessage(prootCheck.exceptionOrNull()?.message, "Ubuntu is installed, but its terminal component could not be prepared.")
                else -> {
                    val smoke = LinuxRuntimeSmokeTest(paths).run()
                    if (smoke.success) "Ubuntu shell smoke test passed."
                    else runtimeFailureMessage(smoke.error ?: smoke.output, "Ubuntu could not start. Please try again.")
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ubuntu Linux runtime", color = Color(0xFF8CC8FF), fontSize = 18.sp)
        Button(enabled = !busy, onClick = {
            busy = true
            status = "Downloading Ubuntu system files…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        DeveloperLog.info(context, "UbuntuSetup", "Starting Ubuntu Base download")
                        val installed = UbuntuBaseDownloader(paths).downloadAndInstall()
                        DeveloperLog.info(context, "UbuntuSetup", "Ubuntu Base installed successfully")
                        "Ubuntu system files have been installed. The terminal component is the next step."
                    } catch (e: Exception) {
                        DeveloperLog.error(context, "UbuntuSetup", "Ubuntu Base download failed", e)
                        runtimeFailureMessage(e.message, "Ubuntu could not be downloaded. Please check your internet connection and try again.")
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
                        status = runtimeFailureMessage(smoke.error ?: smoke.output, "Ubuntu could not start. Please try again.")
                    }
                }
            }
        }) { Text("Download official Ubuntu Base 24.04.5") }
        Button(enabled = !busy, onClick = {
            busy = true
            status = "Checking terminal component…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        DeveloperLog.info(context, "UbuntuSetup", "Validating bundled terminal runtime")
                        val installed = ProotDownloader(paths).downloadAndInstall()
                        "The terminal component is ready."
                    } catch (e: Exception) {
                        DeveloperLog.error(context, "UbuntuSetup", "Bundled terminal runtime validation failed", e)
                        runtimeFailureMessage(e.message, "The terminal component could not be prepared. Please update or reinstall the app.")
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
                        status = runtimeFailureMessage(smoke.error ?: smoke.output, "Ubuntu could not start. Please try again.")
                    }
                }
            }
        }) { Text("Check terminal component") }
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
                        DeveloperLog.info(context, "UbuntuSetup", "Installing selected Ubuntu root filesystem archive")
                        RootfsInstaller(paths).install(namedArchive, rootfsSha.ifBlank { null })
                        namedArchive.delete()
                        if (paths.hasProot()) {
                            val smoke = LinuxRuntimeSmokeTest(paths).run()
                            if (!smoke.success) throw IOException(runtimeFailureMessage(smoke.error ?: smoke.output, "Ubuntu could not start. Please try again."))
                            "Ubuntu rootfs installed and shell smoke test passed."
                        } else {
                            "Ubuntu system files are installed. Set up the terminal component to start Ubuntu."
                        }
                    } catch (e: Exception) {
                        DeveloperLog.error(context, "UbuntuSetup", "Ubuntu root filesystem installation failed", e)
                        runtimeFailureMessage(e.message, "Ubuntu could not be installed. Please check the archive and available storage.")
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
                        status = runtimeFailureMessage(smoke.error ?: smoke.output, "Ubuntu could not start. Please try again.")
                    }
                }
            }
        }) { Text("Install Ubuntu rootfs") }

        if (busy) CircularProgressIndicator(color = Color(0xFF8CC8FF))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                status,
                color = if (paths.ready()) Color(0xFF7DDBB0) else Color(0xFF9AA7B5),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("CPPIDE status", status))
                Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
            }) {
                Text("Copy message", fontSize = 11.sp)
            }
        }
    }
}

private const val MAX_IMPORT_BYTES = 4L * 1024 * 1024 * 1024


/** Converts low-level runtime diagnostics into clear actions for the app user. */
private fun runtimeFailureMessage(details: String?, fallback: String): String {
    val message = details.orEmpty().lowercase()
    return when {
        message.contains("sha-256 value") || message.contains("invalid sha") ->
            "The SHA-256 value is invalid. Enter a 64-character checksum or leave it empty."
        message.contains("checksum") || message.contains("sha-256") || message.contains("digest") ->
            "The Ubuntu download could not be verified. Please download it again."
        message.contains("no space") || message.contains("enospc") || message.contains("not enough space") ->
            "There is not enough free storage. Free up some space and try again."
        message.contains("unknownhost") || message.contains("unable to resolve") ||
            message.contains("connection") || message.contains("timed out") || message.contains("http 4") ||
            message.contains("http 5") ->
            "Could not connect to the download server. Check your internet connection and try again."
        message.contains("unsupported archive") || message.contains("invalid archive") ||
            message.contains("truncated archive") || message.contains("archive exceeds") ->
            "This Ubuntu archive is damaged or unsupported. Select a valid .tar, .tar.gz, or .tar.xz archive."
        message.contains("rootfs") && (message.contains("missing") || message.contains("incomplete")) ->
            "Ubuntu files are missing or incomplete. Install the Ubuntu environment again."
        message.contains("proot") && (message.contains("permission denied") || message.contains("eacces") ||
            message.contains("missing") || message.contains("loader")) ->
            "The terminal component could not start. Please update or reinstall the app."
        message.contains("timed out") || message.contains("timeout") ->
            "Ubuntu is taking too long to start. Please try again."
        message.contains("apt-get check") || message.contains("dpkg") ->
            "Ubuntu's package database needs repair. Open the terminal and run: sudo apt --fix-broken install"
        message.contains("permission denied") || message.contains("eacces") ->
            "Ubuntu could not access a required file. Reinstall the Ubuntu environment and try again."
        else -> fallback
    }
}
