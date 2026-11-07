package com.techwithkaushik.cppide.feature.terminal

import com.jcraft.jsch.ChannelShell
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.OutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

data class SshHost(
    val host: String, val port: Int = 22, val username: String, val password: String? = null,
    val privateKey: ByteArray? = null, val privateKeyPassphrase: String? = null,
    val expectedSha256Fingerprint: String? = null,
) {
    fun validate() {
        require(host.matches(Regex("[A-Za-z0-9.:-]{1,253}")) && host.any { it.isLetterOrDigit() }) { "Invalid SSH host or IP address." }
        require(port in 1..65535) { "SSH port must be between 1 and 65535." }
        require(username.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid SSH username." }
        require(!password.isNullOrEmpty() || privateKey != null) { "Provide a password or private key." }
        expectedSha256Fingerprint?.let { require(it.startsWith("SHA256:") && it.length >= 20) { "Invalid SSH fingerprint." } }
    }
}
class HostKeyConfirmationRequired(val fingerprint: String) : SecurityException("Confirm the SSH server host key before connecting: $fingerprint")

/** Direct SSH shell with fingerprint pinning; unknown keys fail before authentication. */
class SshShellSession private constructor(private val session: Session, private val channel: ChannelShell) : AutoCloseable {
    val input: OutputStream = channel.outputStream
    private val closed = AtomicBoolean(false)
    fun resize(columns: Int, rows: Int) {
        val safeColumns = columns.coerceIn(20, 300)
        val safeRows = rows.coerceIn(5, 120)
        runCatching { channel.setPtySize(safeColumns, safeRows, safeColumns * 8, safeRows * 16) }
            .onSuccess { DeveloperLog.event("SSH", "pty_resize_completed", mapOf("columns" to safeColumns, "rows" to safeRows)) }
            .onFailure { DeveloperLog.failure("SSH", "pty_resize_failed", it, mapOf("columns" to safeColumns, "rows" to safeRows)) }
            .getOrThrow()
    }

    fun readOutput(onChunk: (String) -> Unit, onExit: (Int) -> Unit) {
        DeveloperLog.event("SSH", "output_reader_started", mapOf("connected" to channel.isConnected))
        Thread({
            val reader = Thread {
                try {
                    InputStreamReader(channel.inputStream, StandardCharsets.UTF_8).use { input ->
                        val chars = CharArray(2048)
                        while (true) {
                            val count = input.read(chars)
                            if (count < 0) break
                            if (count > 0) onChunk(String(chars, 0, count))
                        }
                    }
                } catch (error: Exception) {
                    DeveloperLog.failure("SSH", "output_reader_failed", error, mapOf("channelConnected" to channel.isConnected, "channelClosed" to channel.isClosed))
                }
            }.apply { isDaemon = true; start() }
            while (channel.isConnected && !channel.isClosed) Thread.sleep(150)
            reader.join(500)
            DeveloperLog.event("SSH", "shell_channel_exited", mapOf("exitCode" to channel.exitStatus, "connected" to channel.isConnected, "closed" to channel.isClosed))
            onExit(channel.exitStatus)
        }, "ssh-shell-exit").apply { isDaemon = true; start() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        DeveloperLog.event("SSH", "session_close_started", mapOf("channelConnected" to channel.isConnected, "sessionConnected" to session.isConnected))
        try { input.write("exit\n".toByteArray()); input.flush() }
        catch (error: Exception) { DeveloperLog.warn("SSH", "exit_command_write_failed", error) }
        try { channel.disconnect() } finally { session.disconnect() }
        DeveloperLog.event("SSH", "session_closed", mapOf("channelConnected" to channel.isConnected, "sessionConnected" to session.isConnected))
    }

    companion object {
        fun connect(host: SshHost): SshShellSession {
            val startedAt = System.nanoTime()
            DeveloperLog.event("SSH", "connection_started", mapOf("host" to host.host, "port" to host.port, "authentication" to if (host.privateKey != null) "private_key" else "password", "fingerprintPinConfigured" to (host.expectedSha256Fingerprint != null)))
            try { host.validate() } catch (error: Exception) {
                DeveloperLog.failure("SSH", "connection_configuration_invalid", error, mapOf("host" to host.host, "port" to host.port))
                throw error
            }
            val jsch = JSch()
            val repository = PinningHostKeyRepository(host.expectedSha256Fingerprint)
            jsch.hostKeyRepository = repository
            host.privateKey?.let { jsch.addIdentity("ide-key", it, null, host.privateKeyPassphrase?.toByteArray(Charsets.UTF_8)) }
            val session = jsch.getSession(host.username, host.host, host.port)
            host.password?.toByteArray(StandardCharsets.UTF_8)?.let(session::setPassword)
            session.setConfig(Properties().apply {
                setProperty("StrictHostKeyChecking", "yes")
                setProperty("PreferredAuthentications", if (host.privateKey != null) "publickey,password,keyboard-interactive" else "password,keyboard-interactive")
                setProperty("ServerAliveInterval", "15000")
                setProperty("ServerAliveCountMax", "3")
            })
            try {
                DeveloperLog.event("SSH", "transport_connect_started", mapOf("host" to host.host, "port" to host.port, "timeoutMs" to 15_000))
                session.connect(15_000)
                DeveloperLog.event("SSH", "transport_connected", mapOf("host" to host.host, "port" to host.port, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000, "fingerprint" to repository.presentedFingerprint))
            } catch (error: Exception) {
                DeveloperLog.warn("SSH", "SSH transport or authentication failed", error)
                session.disconnect()
                repository.presentedFingerprint?.let { presented ->
                    if (host.expectedSha256Fingerprint == null) throw HostKeyConfirmationRequired(presented)
                    if (!MessageDigest.isEqual(host.expectedSha256Fingerprint.toByteArray(), presented.toByteArray())) {
                        throw SecurityException("SSH host key changed. Expected ${host.expectedSha256Fingerprint}, received $presented.")
                    }
                }
                throw error
            }
            try {
                DeveloperLog.event("SSH", "shell_channel_open_started", mapOf("host" to host.host, "timeoutMs" to 10_000))
                val channel = session.openChannel("shell") as ChannelShell
                channel.setPtyType("xterm-256color", 100, 30, 800, 600)
                channel.connect(10_000)
                DeveloperLog.event("SSH", "connection_completed", mapOf("host" to host.host, "port" to host.port, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000, "fingerprint" to repository.presentedFingerprint))
                return SshShellSession(session, channel)
            } catch (error: Exception) {
                DeveloperLog.failure("SSH", "shell_channel_open_failed", error, mapOf("host" to host.host, "port" to host.port, "transportConnected" to session.isConnected, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
                session.disconnect()
                throw error
            }
        }
    }
}

private class PinningHostKeyRepository(private val expected: String?) : HostKeyRepository {
    @Volatile var presentedFingerprint: String? = null
        private set
    override fun check(host: String, key: ByteArray): Int {
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        presentedFingerprint = fingerprint
        return when {
            expected == null -> HostKeyRepository.NOT_INCLUDED
            MessageDigest.isEqual(expected.toByteArray(), fingerprint.toByteArray()) -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }
    override fun add(hostkey: HostKey, userinfo: com.jcraft.jsch.UserInfo?) = Unit
    override fun remove(host: String, type: String) = Unit
    override fun remove(host: String, type: String, key: ByteArray) = Unit
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String, type: String?): Array<HostKey> = emptyArray()
    override fun getKnownHostsRepositoryID(): String = "Code Studio host-key pin"
}
