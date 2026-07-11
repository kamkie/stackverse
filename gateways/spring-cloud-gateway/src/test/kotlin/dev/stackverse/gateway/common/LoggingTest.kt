package dev.stackverse.gateway.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LoggingTest {

    @Test
    fun `sanitizer preserves absence instead of inventing log content`() {
        assertNull(sanitizeForLog(null))
    }

    @Test
    fun `sanitizer encodes newlines and removes other control characters`() {
        val clientInput = "first\r\nsecond\rlast\u0000\tend"

        assertEquals("first\\nsecond\\nlastend", sanitizeForLog(clientInput))
    }

    @Test
    fun `sanitizer caps client input and marks truncation`() {
        assertEquals("abc", sanitizeForLog("abc", maxLength = 3))
        assertEquals("abc…", sanitizeForLog("abcdef", maxLength = 3))
    }
}
