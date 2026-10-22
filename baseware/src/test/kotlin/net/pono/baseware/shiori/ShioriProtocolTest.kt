package net.pono.baseware.shiori

import net.pono.baseware.text.TextCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShioriProtocolTest {
    @Test
    fun encodesRequest() {
        val bytes = ShioriRequest(ShioriMethod.GET, "OnBoot", listOf("master", "a\r\nb")).encode(TextCharsets.CP932, "pono-droid")
        val text = String(bytes, TextCharsets.CP932)
        assertTrue(text.startsWith("GET SHIORI/3.0\r\nCharset: Shift_JIS\r\n"))
        assertTrue("ID: OnBoot\r\n" in text)
        assertTrue("Reference1: a\u0001b\r\n" in text)
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun parsesResponseUsingDeclaredCharset() {
        val raw = "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\nValue: \\h\\s0こんにちは\\e\r\n\r\n".toByteArray(Charsets.UTF_8)
        val res = ShioriResponse.parse(raw, TextCharsets.CP932)!!
        assertEquals(200, res.statusCode)
        assertEquals("\\h\\s0こんにちは\\e", res.value)
    }

    @Test
    fun noContentHasNoValue() {
        val res = ShioriResponse.parse("SHIORI/3.0 204 No Content\r\n\r\n".toByteArray(), Charsets.UTF_8)!!
        assertEquals(204, res.statusCode)
        assertEquals(null, res.value)
    }
}
