package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.logging.DeveloperLog
import java.io.File
import java.util.concurrent.TimeUnit

/** Builds a resolver file from Android's active DNS properties. */
object HostResolverConfig {
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

    fun prepare(hostTemp: File, sessionId: String? = null): File {
        val startedAt = System.nanoTime()
        if (!hostTemp.exists() && !hostTemp.mkdirs()) throw IllegalStateException("Cannot create host temporary directory for DNS configuration.")
        val propertyRead = runCatching {
            val process = ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); throw IllegalStateException("getprop timed out") }
            if (process.exitValue() != 0) throw IllegalStateException("getprop exit=${process.exitValue()}")
            output
        }
        val servers = propertyRead.getOrNull()?.let(::parseDnsServers).orEmpty().toCollection(linkedSetOf())
        val usingFallback = servers.isEmpty()
        val fallbackReason = if (propertyRead.isFailure) "getprop_failed" else if (usingFallback) "no_valid_dns_properties" else "none"
        if (usingFallback) { servers += "1.1.1.1"; servers += "8.8.8.8" }
        val content = servers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" } + "options timeout:3 attempts:2\n"
        val file = File(hostTemp, "resolv.conf")
        val changed = !file.isFile || runCatching { file.readText() != content }.getOrDefault(true)
        try {
            if (changed) file.writeText(content)
            check(file.isFile && file.canRead() && file.length() > 0L) { "Resolver file is missing or unreadable." }
        } catch (error: Exception) {
            DeveloperLog.failure("UbuntuDNS", "resolver_write_failed", error, mapOf("sessionId" to sessionId, "fallbackReason" to fallbackReason))
            throw error
        }
        DeveloperLog.event("UbuntuDNS", "resolver_ready", mapOf(
            "sessionId" to sessionId, "resolverSource" to if (usingFallback) "public_fallback" else "android_properties",
            "resolverCount" to servers.size, "fallbackUsed" to usingFallback, "fallbackReason" to fallbackReason,
            "getpropRead" to if (propertyRead.isSuccess) "success" else "failed", "fileChanged" to changed,
            "fileBytes" to file.length(), "durationMs" to (System.nanoTime() - startedAt) / 1_000_000
        ))
        return file
    }
}
