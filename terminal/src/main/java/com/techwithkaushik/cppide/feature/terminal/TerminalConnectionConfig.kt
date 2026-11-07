package com.techwithkaushik.cppide.feature.terminal

/** In-process SSH credential handoff; secrets are not persisted. */
object TerminalConnectionConfig {
    @Volatile var sshPassword: String = ""
    @Volatile var sshPrivateKeyPassphrase: String = ""
    @Volatile private var privateKeyBytes: ByteArray? = null

    var sshPrivateKey: ByteArray?
        get() = privateKeyBytes?.copyOf()
        set(value) = replacePrivateKey(value)

    @Synchronized
    fun replacePrivateKey(newKey: ByteArray?) {
        privateKeyBytes?.fill(0)
        privateKeyBytes = newKey?.copyOf()
    }

    @Synchronized
    fun clearCredentials() {
        sshPassword = ""
        privateKeyBytes?.fill(0)
        privateKeyBytes = null
        sshPrivateKeyPassphrase = ""
    }
}
