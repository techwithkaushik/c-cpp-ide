package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.logging.DeveloperLog
import java.io.File

/** Builds a resolver file from Android's active DNS properties. */
internal object HostResolverConfig {
    private val propertyPattern = Regex("""\[net\.dns[1-4]\]: \[(.*?)\]""")
    private val ipv4Pattern = Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}""")
    private val ipv6Pattern = Regex("""[0-9a-fA-F:]+""")

    internal fun parseDnsServers(propertyDump: String): List<String> {
        val servers = linkedSetOf<String>()
        propertyPattern.findAll(propertyDump).forEach { match ->
            val value = match.groupValues[1].trim()
            val isIpv4 = ipv4Pattern.matches(value) && value.split('.').all { it.toIntOrNull() in 0..255 }
            val isIpv6 = ipv6Pattern.matches(value) && value.contains(':')
            if (isIpv4 || isIpv6) servers += value
        }
        return servers.toList()
    }

    fun prepare(hostTemp: File): File {
        if (!hostTemp.exists() && !hostTemp.mkdirs()) throw IllegalStateException("Cannot create host temporary directory for DNS configuration.")
        val propertyDump = runCatching {
            ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start().inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val servers = parseDnsServers(propertyDump).toCollection(linkedSetOf())
        val usingFallback = servers.isEmpty()
        if (usingFallback) { servers += "1.1.1.1"; servers += "8.8.8.8" }
        val content = servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" } + "options timeout:3 attempts:2\n"
        DeveloperLog.info("UbuntuDNS", "Prepared guest resolver configuration; serverCount=${servers.size}; fallbackUsed=$usingFallback")
        val file = File(hostTemp, "resolv.conf")
        if (!file.isFile || runCatching { file.readText() != content }.getOrDefault(true)) file.writeText(content)
        return file
    }
}
