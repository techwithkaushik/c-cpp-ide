package com.techwithkaushik.cppide.integration.termux

/**
 * Canonical default paths for the standard Termux app package.
 *
 * RUN_COMMAND executes inside Termux, where its environment is initialized by Termux itself.
 * These constants are path defaults for argv/workdir construction, not a substitute for reading
 * runtime environment values (such as PATH, locale, or a user-customized HOME) from Termux.
 */
object TermuxEnvironment {
    const val PACKAGE = "com.termux"

    const val APP_DATA = "/data/data/com.termux"
    const val FILES = "$APP_DATA/files"

    const val PREFIX = "$FILES/usr"
    const val HOME = "$FILES/home"
    const val TMPDIR = "$PREFIX/tmp"

    const val BIN = "$PREFIX/bin"
    const val ETC = "$PREFIX/etc"
    const val LIB = "$PREFIX/lib"
    const val INCLUDE = "$PREFIX/include"
    const val SHARE = "$PREFIX/share"

    /** Default PATH for current Android versions; Termux may customize its runtime environment. */
    const val DEFAULT_PATH = "$BIN"

    /** Newer Termux builds may expose this alias; do not assume it exists on older versions. */
    const val TERMUX_PREFIX = PREFIX
    const val TERMUX_HOME = HOME

    fun defaultEnvironment(): Map<String, String> = mapOf(
        "PREFIX" to PREFIX,
        "HOME" to HOME,
        "TMPDIR" to TMPDIR,
        "PATH" to DEFAULT_PATH
    )
}
