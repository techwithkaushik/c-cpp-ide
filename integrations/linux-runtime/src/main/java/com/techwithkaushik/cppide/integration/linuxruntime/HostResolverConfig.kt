package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File

/**
 * Builds a resolver file from Android's active DNS properties. PRoot does not
 * automatically inherit Android's per-network DNS resolver inside Ubuntu.
 */
internal object HostResolverConfig {
    private val propertyPattern = Regex("""\\[net\\.dns[1-4]\\]: \\[([^]]+)]""")
    private val ipv4Pattern = Regex("""(?:[0-9]{1,3}\\.){3}[0-9]{1,3}""")
    private val ipv6Pattern = Regex("""[0-9a-fA-F:]+""")

    fun prepare(hostTemp: File): File {
        if (!hostTemp.exists() && !hostTemp.mkdirs()) {
            throw IllegalStateException("Cannot create host temporary directory for DNS configuration.")
        }

        // One getprop process is cheaper than spawning four Android processes on
        // every shell launch. Read only DNS properties from its complete snapshot.
        val propertyDump = runCatching {
            ProcessBuilder("/system/bin/getprop")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val servers = linkedSetOf<String>()
        propertyPattern.findAll(propertyDump).forEach { match ->
            val value = match.groupValues[1].trim()
            val valid = (ipv4Pattern.matches(value) || ipv6Pattern.matches(value) && value.contains(':')) &&
                value.split('.').filter { it.isNotEmpty() }.all { it.toIntOrNull() in 0..255 }
            if (valid) servers += value
        }

        // Android's DNS properties may be empty on some vendor ROMs. Public
        // resolvers are only a fallback; prefer the device's network DNS above.
        if (servers.isEmpty()) {
            servers += "1.1.1.1"
            servers += "8.8.8.8"
        }
        val content = servers.joinToString(separator = "\\n", postfix = "\\n") { "nameserver $it" } +
            "options timeout:3 attempts:2\\n"
        val file = File(hostTemp, "resolv.conf")
        if (!file.isFile || runCatching { file.readText() != content }.getOrDefault(true)) {
            file.writeText(content)
        }
        return file
    }
}
