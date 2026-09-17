package com.gamefactory.core

import com.gamefactory.core.security.SecurityAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuditTest {

    @Test
    fun `detects hardcoded nvidia key`() {
        val files = mapOf("a.gd" to "const K = \"nvapi-abcdefghij1234567890ABCDE\"")
        val findings = SecurityAudit.scan(files)
        assertTrue(findings.any { it.rule == "hardcoded-api-key-nvidia" && it.file == "a.gd" && it.line == 1 })
    }

    @Test
    fun `detects hardcoded openai-style key and password`() {
        val files = mapOf(
            "b.gd" to "var key = \"sk-abcdefghijklmnopqrstuvwxyz012345\"",
            "c.gd" to "var password = \"hunter2secret\""
        )
        val findings = SecurityAudit.scan(files)
        assertTrue(findings.any { it.rule == "hardcoded-api-key-openai" })
        assertTrue(findings.any { it.rule == "hardcoded-password" })
    }

    @Test
    fun `clean code passes`() {
        val files = mapOf(
            "a.gd" to "var url = OS.get_environment(\"BACKEND_URL\")\nprint(url)\n",
            "b.gd" to "func add(a: int, b: int) -> int:\n    return a + b\n"
        )
        assertTrue(SecurityAudit.passes(files))
        assertEquals(emptyList<SecurityAudit.Finding>(), SecurityAudit.scan(files))
    }

    @Test
    fun `google style key detected`() {
        val files = mapOf("x.gd" to "key = \"AIzaSyA1234567890abcdefghijklmnopqrstuvw\"")
        assertTrue(SecurityAudit.scan(files).any { it.rule == "hardcoded-api-key-google" })
    }
}
