package com.techwithkaushik.cppide.integration.linuxruntime

import org.junit.Assert.assertEquals
import org.junit.Test

class HostResolverConfigTest {
    @Test fun parsesIpv4AndIpv6DnsFromAndroidGetpropSnapshot() {
        val snapshot = """
            [net.dns1]: [8.8.8.8]
            [net.dns2]: [2001:4860:4860::8888]
            [net.dns3]: []
        """.trimIndent()

        assertEquals(
            listOf("8.8.8.8", "2001:4860:4860::8888"),
            HostResolverConfig.parseDnsServers(snapshot)
        )
    }

    @Test fun rejectsMalformedDnsValuesAndDuplicateServers() {
        val snapshot = """
            [net.dns1]: [999.1.1.1]
            [net.dns2]: [192.0.2.53]
            [net.dns3]: [192.0.2.53]
            [net.dns4]: [not-an-address]
        """.trimIndent()

        assertEquals(listOf("192.0.2.53"), HostResolverConfig.parseDnsServers(snapshot))
    }
}
