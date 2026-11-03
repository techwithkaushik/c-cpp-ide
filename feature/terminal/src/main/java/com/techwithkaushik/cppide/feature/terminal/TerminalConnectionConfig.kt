package com.techwithkaushik.cppide.feature.terminal

/**
 * One-process SSH credential handoff from Terminal Settings to the terminal.
 * Secrets are deliberately not persisted to SharedPreferences or disk.
 */
object TerminalConnectionConfig {
    @Volatile
    var sshPassword: String = ""
}
