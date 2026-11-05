package com.techwithkaushik.cppide.core.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperLogTest {
    @Test fun redactsCredentialsAndPrivateKeys() {
        val source = "password=hunter2 token:abc123 Authorization=Bearer xyz " +
            "-----BEGIN PRIVATE KEY-----secret-material-----END PRIVATE KEY-----"
        val safe = DeveloperLog.redactSensitiveValues(source)
        assertFalse(safe.contains("hunter2"))
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("xyz"))
        assertFalse(safe.contains("secret-material"))
        assertTrue(safe.contains("[REDACTED]"))
    }
    @Test fun redactsCredentialQueryParameters() {
        val safe = DeveloperLog.redactSensitiveValues("https://example.test/path?token=topsecret&mode=fast")
        assertFalse(safe.contains("topsecret"))
        assertTrue(safe.contains("mode=fast"))
    }
}
