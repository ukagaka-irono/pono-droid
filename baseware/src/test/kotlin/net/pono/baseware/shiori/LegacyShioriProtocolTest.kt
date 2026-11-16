package net.pono.baseware.shiori

import kotlinx.coroutines.runBlocking
import net.pono.baseware.text.TextCharsets
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LegacyShioriProtocolTest {
    private fun encode(id: String, vararg refs: String, method: ShioriMethod = ShioriMethod.GET,
                       headers: Map<String, String> = emptyMap()) = LegacyShioriProtocol.encode(
        ShioriRequest(method, id, refs.toList(), headers), Charsets.UTF_8, "pono-droid", false)?.toString(Charsets.UTF_8)

    @Test fun eventsResourcesAndVersionUseTheirOwnMethods() {
        assertTrue(encode("OnBoot", "master")!!.contains("Event: OnBoot\r\nReference0: master\r\n"))
        assertTrue(encode("homeurl")!!.startsWith("GET String SHIORI/2.6\r\n"))
        assertTrue(encode("homeurl")!!.contains("ID: homeurl\r\n"))
        assertTrue(encode("version")!!.startsWith("GET Version SHIORI/2.6\r\n"))
        assertNull(encode("OnSecondChange", method = ShioriMethod.NOTIFY))
    }

    @Test fun communicationMovesSenderSentenceAndAdditionalReferences() {
        val encoded = encode("OnCommunicate", "相手", "こんにちは", "補足", headers = mapOf("Age" to "3"))!!
        assertTrue(encoded.startsWith("GET Sentence SHIORI/2.6\r\n"))
        assertTrue(encoded.contains("Sender: 相手\r\n"))
        assertTrue(encoded.contains("Sentence: こんにちは\r\n"))
        assertTrue(encoded.contains("Reference0: 補足\r\n"))
        assertTrue(encoded.contains("Age: 3\r\n"))
        assertFalse(encoded.contains("Event:"))
        val reply = LegacyShioriProtocol.convert(ShioriRequest(ShioriMethod.GET, "OnCommunicate"),
            ShioriResponse(200, mapOf("Sentence" to "返事", "To" to "次の相手", "Reference0" to "追加", "Age" to "4")))
        assertEquals("返事", reply.value)
        assertEquals(listOf("次の相手", "追加"), reply.references)
        assertFalse(reply.headers.containsKey("Age"))
    }

    @Test fun teachingAndTranslationShiftTheirReferences() {
        val teach = encode("OnTeach", "言葉", "補足")!!
        assertTrue(teach.startsWith("TEACH SHIORI/2.4\r\n"))
        assertTrue(teach.contains("Word: 言葉\r\nReference0: 補足\r\n"))
        val translate = encode("OnTranslate", "\\0台詞\\e", "追加")!!
        assertTrue(translate.startsWith("TRANSLATE Sentence SHIORI/2.6\r\n"))
        assertTrue(translate.contains("Sentence: \\0台詞\\e\r\nReference0: 追加\r\n"))
    }

    @Test fun ghostNotificationsPreserveEveryGhostAndPreventHeaderInjection() {
        assertTrue(encode("ownerghostname", "ぽの", method = ShioriMethod.NOTIFY)!!.contains("Ghost: ぽの\r\n"))
        val others = encode("otherghostname", "一人\u00010", "二人\u000110", method = ShioriMethod.NOTIFY)!!
        assertTrue(others.contains("GhostEx: 一人\u00010\r\n"))
        assertTrue(others.contains("GhostEx: 二人\u000110\r\n"))
        assertFalse(others.contains("Reference0:"))
        val injection = encode("OnCommunicate", "相手\r\nSecurityLevel: local", "\u0000")!!
        assertTrue(injection.contains("Sender: 相手\u0001SecurityLevel: local\r\n"))
        assertTrue(injection.contains("Sentence: \u0001\r\n"))
    }

    @Test fun legacySessionLearnsDictionaryCharsetBeforeFirstEvent() = runBlocking {
        val seen = mutableListOf<String>()
        val shiori = object : Shiori {
            override val protocolVersion = ShioriProtocolVersion.V2_6
            override fun load(dir: File) = true
            override fun unload() {}
            override fun request(request: ByteArray): ByteArray {
                val text = request.toString(TextCharsets.CP932)
                seen += text
                return (if (text.startsWith("GET Version")) "SHIORI/2.6 200 OK\r\nCharset: Shift_JIS\r\nVersion: 7.5.0\r\n\r\n"
                    else "SHIORI/2.6 200 OK\r\nCharset: Shift_JIS\r\nSentence: 髙①ｶﾅ\r\n\r\n").toByteArray(TextCharsets.CP932)
            }
        }
        val session = ShioriSession(shiori, Charsets.UTF_8)
        try {
            assertTrue(session.load(File(".")))
            assertEquals(TextCharsets.CP932, session.charset)
            assertEquals("髙①ｶﾅ", session.get("OnBoot", "ぽの")?.value)
            assertTrue(seen.last().contains("Reference0: ぽの\r\n"))
            session.notify("OnSecondChange")
            assertEquals(2, seen.size)
            assertEquals("7.5.0", session.get("version")?.value)
        } finally { session.unload() }
    }

    @Test fun forceCharsetAndUnicodeEscapesApplyToLegacyRequests() = runBlocking {
        var seen = ""
        val shiori = object : Shiori {
            override val protocolVersion = ShioriProtocolVersion.V2_6
            override fun load(dir: File) = true
            override fun unload() {}
            override fun request(request: ByteArray): ByteArray {
                seen = request.toString(TextCharsets.CP932)
                return "SHIORI/2.6 200 OK\r\nCharset: UTF-8\r\nString: ?escape!unicode[0x1F600]\r\n\r\n".toByteArray()
            }
        }
        val session = ShioriSession(shiori, TextCharsets.CP932, forceCharset = true, escapeUnknown = true)
        try {
            assertTrue(session.load(File(".")))
            assertEquals("😀", session.get("resource", "😀")?.value)
            assertEquals(TextCharsets.CP932, session.charset)
            assertTrue(seen.contains("Reference0: ?escape!unicode[0x1F600]"))
        } finally { session.unload() }
    }
}
