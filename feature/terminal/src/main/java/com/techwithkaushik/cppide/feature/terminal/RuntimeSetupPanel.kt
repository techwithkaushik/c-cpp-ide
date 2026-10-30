package com.techwithkaushik.cppide.feature.terminal

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.integration.termuxruntime.BootstrapSmokeTest
import com.techwithkaushik.cppide.integration.termuxruntime.EmbeddedBootstrap
import com.techwithkaushik.cppide.integration.termuxruntime.EmbeddedTermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

@Composable
internal fun RuntimeSetupPanel(
    message: String,
    onRuntimeReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    var attempt by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var status by remember(message) { mutableStateOf(message) }

    // Like AndroidIDE/Termux, bootstrap installation starts automatically when
    // the runtime setup screen appears. Users never select or import a ZIP.
    LaunchedEffect(attempt) {
        busy = true
        status = "Installing the embedded terminal runtime…"
        val result = withContext(Dispatchers.IO) {
            val stagedArchive = File(context.cacheDir, "embedded-termux-bootstrap.zip")
            var smoke: BootstrapSmokeTest.Result? = null
            try {
                val metadata = context.assets.open("bootstrap/bootstrap.json").bufferedReader().use {
                    JSONObject(it.readText())
                }
                val abi = metadata.optString("abi")
                if (abi.isBlank() || abi !in Build.SUPPORTED_ABIS) {
                    throw IOException("Bootstrap ABI '$abi' does not match this device")
                }
                val packageId = metadata.optString("applicationId")
                if (packageId != context.packageName) {
                    throw IOException("Bootstrap package identity mismatch")
                }
                if (!metadata.optBoolean("validatedByCI", false)) {
                    throw IOException("Bootstrap did not pass CI source, archive and checksum validation")
                }
                if (metadata.optString("prefix") != "/data/data/" + context.packageName + "/files/usr") {
                    throw IOException("Bootstrap prefix does not match Code Studio")
                }
                val sha256 = metadata.optString("sha256")
                if (!sha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                    throw IOException("Embedded bootstrap metadata has no valid SHA-256")
                }

                EmbeddedBootstrap.writeVerifiedArchive(stagedArchive, sha256)
                val runtime = EmbeddedTermuxRuntime(context.filesDir)
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
                    "Setup failed: ${error.message ?: error.javaClass.simpleName}"
                }
            } finally {
                stagedArchive.delete()
            }
        }
        status = result
        busy = false
        if (result.startsWith("Bootstrap verified")) onRuntimeReady()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Setting up terminal", color = Color(0xFF8CC8FF), fontSize = 16.sp)
        Text(
            "Code Studio prepares its ABI-matched terminal runtime automatically. No separate Termux app, manual download or ZIP import is required.",
            color = Color(0xFFE8EDF5),
            fontSize = 12.sp
        )
        if (busy) CircularProgressIndicator(color = Color(0xFF8CC8FF))
        Text(
            status,
            color = if (status.startsWith("Bootstrap verified")) Color(0xFF7DDBB0) else Color(0xFF9AA7B5),
            fontSize = 12.sp
        )
        if (!busy && !status.startsWith("Bootstrap verified")) {
            Button(onClick = { attempt++ }) {
                Text("Retry setup")
            }
        }
    }
}
