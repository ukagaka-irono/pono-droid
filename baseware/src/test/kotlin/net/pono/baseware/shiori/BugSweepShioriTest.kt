package net.pono.baseware.shiori

import org.junit.Assert.assertEquals
import org.junit.Test

class BugSweepShioriTest {
    @Test
    fun p8_valueWithoutSpaceAfterColonKeepsColonsInTheValue() {
        val res = ShioriResponse.parse("SHIORI/3.0 200 OK\r\nValue:12: 30\r\n\r\n".toByteArray(), Charsets.UTF_8)!!
        assertEquals("12: 30", res.value)
    }
}
