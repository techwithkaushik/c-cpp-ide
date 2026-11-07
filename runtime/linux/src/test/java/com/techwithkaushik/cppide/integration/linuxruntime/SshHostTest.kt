package com.techwithkaushik.cppide.integration.linuxruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SshHostTest {
    @Test fun acceptsValidPasswordHost() {
        val host = SshHost(host = "example.com", port = 22, username = "developer", password = "not-stored")
        host.validate()
        assertEquals("example.com", host.host)
    }

    @Test fun rejectsInvalidPort() {
        val host = SshHost(host = "example.com", port = 70000, username = "developer", password = "pw")
        assertThrows(IllegalArgumentException::class.java) { host.validate() }
    }

    @Test fun rejectsMissingAuthentication() {
        val host = SshHost(host = "example.com", username = "developer")
        assertThrows(IllegalArgumentException::class.java) { host.validate() }
    }

    @Test fun rejectsInvalidHostFingerprint() {
        val host = SshHost(host = "example.com", username = "developer", password = "pw", expectedSha256Fingerprint = "MD5:bad")
        assertThrows(IllegalArgumentException::class.java) { host.validate() }
    }
    @Test fun acceptsIpv4AndIpv6Literals() {
        SshHost(host = "192.0.2.10", username = "developer", password = "pw").validate()
        SshHost(host = "2001:db8::1", username = "developer", password = "pw").validate()
    }

    @Test fun rejectsHostStringsThatCouldBeMalformed() {
        assertThrows(IllegalArgumentException::class.java) {
            SshHost(host = "bad host", username = "developer", password = "pw").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshHost(host = ":::;", username = "developer", password = "pw").validate()
        }
    }

}
