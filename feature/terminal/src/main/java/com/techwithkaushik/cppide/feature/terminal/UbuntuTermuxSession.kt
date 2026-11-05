package com.techwithkaushik.cppide.feature.terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import com.techwithkaushik.cppide.integration.linuxruntime.HostResolverConfig
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import java.io.File
import java.util.UUID

/**
 * Launches the app-owned Ubuntu rootfs through Termux's own PTY/session implementation.
 * TerminalSession owns the PTY, input/output threads, window-size signals and process waiter.
 */
internal object UbuntuTermuxSession {
    fun create(
        context: Context,
        paths: LinuxRuntimePaths,
        transcriptRows: Int = 5_000,
        onFinished: (TerminalSession) -> Unit = {},
    ): TerminalSession {
        paths.ensureDirectories()
        check(paths.hasRootfs()) { "Ubuntu rootfs is not installed or incomplete. ${paths.rootfsDiagnostic()}" }
        check(paths.hasProot()) { "App-owned PRoot or its loader is missing." }

        val sessionId = UUID.randomUUID().toString().take(8)
        val shell = if (File(paths.ubuntuRoot, "bin/bash").isFile) "/bin/bash" else "/bin/sh"
        val temp = paths.usrTmp.apply {
            if (!exists() && !mkdirs()) throw IllegalStateException("Cannot create PRoot temporary directory.")
        }
        val resolver = HostResolverConfig.prepare(temp, sessionId)
        val proot = paths.proot.absolutePath
        val rootfs = paths.ubuntuRoot.absolutePath
        val loader = paths.prootLoader.absolutePath

        val args = arrayOf(
            proot, "-0", "-v", "0", "--link2symlink", "-r", rootfs, "-w", "/root",
            "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
            "-b", "${resolver.absolutePath}:/etc/resolv.conf", shell, "--login", "-i"
        )
        val env = arrayOf(
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "SHELL=/bin/bash",
            "TMPDIR=${temp.absolutePath}",
            "TMP=${temp.absolutePath}",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "PROOT_TMP_DIR=${temp.absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "PROOT_LOADER=$loader"
        )

        DeveloperLog.event("UbuntuPTY", "termux_session_create", mapOf(
            "sessionId" to sessionId,
            "shell" to shell,
            "rootfsPresent" to paths.hasRootfs(),
            "prootPresent" to paths.proot.isFile
        ))
        return TerminalSession(
            proot,
            rootfs,
            args,
            env,
            transcriptRows.coerceIn(1_000, 20_000),
            createTermuxSessionClient(context.applicationContext) { finished ->
                DeveloperLog.event("UbuntuPTY", "termux_session_finished", mapOf(
                    "sessionId" to sessionId,
                    "exitCode" to finished.exitStatus
                ))
                onFinished(finished)
            }
        )
    }
}
