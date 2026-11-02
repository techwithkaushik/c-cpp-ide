package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File

/**
 * Builds a resolver file from Android's active DNS properties. PRoot does not
 * automatically inherit Android's per-network DNS resolver inside Ubuntu.
 */
internal object HostResolverConfig {
    fun prepare(hostTemp: File): File {
        if (!hostTemp.exists() && !hostTemp.mkdirs()) {
            throw IllegalStateException("Cannot create host temporary directory for DNS configuration.")
        }
        val servers = linkedSetOf<String>()
        for (key in 1..4) {
            val value = runCatching {
                ProcessBuilder("/system/bin/getprop", "net.dns$key")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            if (value.matches(Regex("[0-9a-fA-F:.]+")) && value.contains(':') || value.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
                servers += value
            }
        }
        // Android's DNS properties may be empty on some vendor ROMs. Public
        // resolvers are only a fallback; prefer the device's network DNS above.
        if (servers.isEmpty()) {
            servers += "1.1.1.1"
            servers += "8.8.8.8"
        }
        val file = File(hostTemp, "resolv.conf")
        file.writeText(servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" } +
            "options timeout:3 attempts:2\n")
        return file
    }
}
