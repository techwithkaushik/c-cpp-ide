package com.techwithkaushik.cppide.integration.termux

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.IntentService
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal adapter for the documented Termux RUN_COMMAND API.
 *
 * This is intentionally limited to a fixed, harmless command probe. Do not expose an arbitrary
 * shell-string API from this class. Runtime behavior must be verified on supported devices.
 */
object TermuxCommandClient {
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    // RUN_COMMAND_PATH is an executable path, not a shell expression: $PREFIX is not expanded here.
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val RESULT_SERVICE_EXTRA = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val RESULT_BUNDLE_KEY = "result"
    private const val RESULT_STDOUT_KEY = "stdout"
    private const val RESULT_STDERR_KEY = "stderr"
    private const val RESULT_EXIT_CODE_KEY = "exitCode"
    private const val RESULT_ERROR_KEY = "err"
    private const val RESULT_ERROR_MESSAGE_KEY = "errmsg"
    private const val RESULT_ID_EXTRA = "cppide.termux.execution_id"
    private const val PROBE_MARKER = "CPPIDE_TERMUX_COMMAND_API_OK"
    private const val PROBE_TIMEOUT_MS = 20_000L

    private val nextId = AtomicInteger((SystemClock.elapsedRealtime() % Int.MAX_VALUE).toInt().coerceAtLeast(1))
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

    data class Status(
        val installed: Boolean,
        val serviceResolvable: Boolean,
        val permissionGranted: Boolean,
        val packageVersion: String?,
        val message: String
    )

    data class TermuxCommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val termuxErrorCode: Int?,
        val termuxErrorMessage: String?,
        val timedOut: Boolean = false
    )

    fun inspect(context: Context): Status {
        val appContext = context.applicationContext
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.packageManager.getPackageInfo(
                    TERMUX_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return Status(false, false, false, null, "Termux is not installed or is not visible to this app.")
        } catch (e: RuntimeException) {
            return Status(false, false, false, null, "Could not inspect Termux: ${e.javaClass.simpleName}.")
        }

        val serviceIntent = Intent(RUN_COMMAND_ACTION).setComponent(
            ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
        )
        val resolvable = appContext.packageManager.resolveService(serviceIntent, 0) != null
        val permissionGranted = appContext.checkSelfPermission(RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

        return Status(
            installed = true,
            serviceResolvable = resolvable,
            permissionGranted = permissionGranted,
            packageVersion = packageInfo.versionName,
            message = when {
                !resolvable -> "Termux is installed, but its RUN_COMMAND service could not be resolved."
                !permissionGranted -> "Grant the Run commands in Termux permission in Android App Info."
                else -> "Termux is discoverable and the Android permission is granted; external-command configuration still needs a real probe."
            }
        )
    }

    /**
     * Runs only Termux's printf executable with a constant argument. It does not run user code.
     * A successful result proves the command API works, not that compiler/LSP/PTY capabilities exist.
     */
    suspend fun probeCommandApi(context: Context): TermuxCommandResult {
        val appContext = context.applicationContext
        val status = inspect(appContext)
        require(status.installed) { status.message }
        require(status.serviceResolvable) { status.message }
        require(status.permissionGranted) { status.message }

        val id = nextId.getAndIncrement().coerceAtLeast(1)
        val result = CompletableDeferred<TermuxCommandResult>()
        check(pending.putIfAbsent(id, result) == null) { "Duplicate Termux execution id." }

        try {
            val callbackIntent = Intent(appContext, TermuxCommandResultService::class.java)
                .putExtra(RESULT_ID_EXTRA, id)
            val callback = PendingIntent.getService(
                appContext,
                id,
                callbackIntent,
                PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            )

            val command = Intent(RUN_COMMAND_ACTION).apply {
                component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/printf")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(PROBE_MARKER))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "C/C++ IDE Termux capability probe")
                putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Runs a fixed harmless command to verify authorized command execution.")
                putExtra(RESULT_SERVICE_EXTRA, callback)
            }

            try {
                appContext.startService(command)
            } catch (e: SecurityException) {
                throw IllegalStateException(
                    "Termux rejected the request. Check the RUN_COMMAND permission and allow-external-apps setting.",
                    e
                )
            } catch (e: RuntimeException) {
                throw IllegalStateException("Could not start Termux RUN_COMMAND: ${e.javaClass.simpleName}.", e)
            }

            val received = withTimeout(PROBE_TIMEOUT_MS) { result.await() }
            if (received.exitCode == 0 && received.stdout.contains(PROBE_MARKER)) return received
            throw IllegalStateException(
                received.termuxErrorMessage?.takeIf { it.isNotBlank() }
                    ?: "Termux command probe failed (exit code ${received.exitCode ?: "unknown"}). Check allow-external-apps and Termux configuration."
            )
        } finally {
            pending.remove(id)
        }
    }

    internal fun deliverResult(id: Int, result: TermuxCommandResult) {
        pending.remove(id)?.complete(result)
    }

    internal fun parseResult(intent: Intent): Pair<Int, TermuxCommandResult>? {
        val id = intent.getIntExtra(RESULT_ID_EXTRA, -1)
        if (id <= 0) return null
        // Prefer the current result key while accepting the legacy key used by older releases.
        val bundle: Bundle = intent.getBundleExtra(RESULT_BUNDLE_KEY)
            ?: intent.getBundleExtra("result")
            ?: return null
        return id to TermuxCommandResult(
            stdout = bundle.getString(RESULT_STDOUT_KEY) ?: bundle.getString("stdout").orEmpty(),
            stderr = bundle.getString(RESULT_STDERR_KEY) ?: bundle.getString("stderr").orEmpty(),
            exitCode = when {
                bundle.containsKey(RESULT_EXIT_CODE_KEY) -> bundle.getInt(RESULT_EXIT_CODE_KEY)
                bundle.containsKey("exit_code") -> bundle.getInt("exit_code")
                bundle.containsKey("exitCode") -> bundle.getInt("exitCode")
                else -> null
            },
            termuxErrorCode = when {
                bundle.containsKey(RESULT_ERROR_KEY) -> bundle.getInt(RESULT_ERROR_KEY)
                bundle.containsKey("err") -> bundle.getInt("err")
                else -> null
            },
            termuxErrorMessage = bundle.getString(RESULT_ERROR_MESSAGE_KEY) ?: bundle.getString("errmsg")
        )
    }
}

/** Receives the one-shot PendingIntent result from Termux; never exports this service. */
class TermuxCommandResultService : IntentService("CppIdeTermuxCommandResult") {
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) return
        val parsed = TermuxCommandClient.parseResult(intent) ?: return
        TermuxCommandClient.deliverResult(parsed.first, parsed.second)
    }
}
