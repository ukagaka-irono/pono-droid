package net.pono.baseware.headline

import kotlinx.coroutines.runBlocking
import net.pono.baseware.shiori.Shiori
import net.pono.baseware.text.TextCharsets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HeadlineModuleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun preservesRepeatedHeadersAndNegotiatesRequestCharset() {
        val raw = "HEADLINE/2.0 200 OK\r\nCharset: Shift_JIS\r\nRequestCharset: UTF-8\r\nHeadline: 一\u0001https://example.test/1\r\nHeadline: 二\r\n\r\nHeadline: ignored\r\n"
        val r = HeadlineModule.parse(raw.toByteArray(TextCharsets.CP932), Charsets.UTF_8)!!
        assertEquals(Charsets.UTF_8, r.requestCharset)
        assertEquals(listOf(HeadlineItem("一", "https://example.test/1"), HeadlineItem("二")), r.items)
        assertNull(HeadlineModule.parse("SHIORI/3.0 200 OK\r\n\r\n".toByteArray(), Charsets.UTF_8))
    }

    @Test fun moduleReceivesBothCacheFilesAndUnloadsOnParseFailure() = runBlocking {
        val dir = tmp.newFolder("module")
        File(dir, "descript.txt").writeText("charset,UTF-8\nname,Module\ndllname,module.dll\nurl,https://example.test/feed\nopenurl,https://example.test/\n")
        File(dir, "module.dll").writeText("fixture")
        val old = tmp.newFile("old.xml")
        val fresh = tmp.newFile("new.xml")
        val requests = mutableListOf<String>()
        var unloaded = false
        var fail = false
        val module = HeadlineModule({ _, _ -> object : Shiori {
            override fun load(dir: File) = true
            override fun unload() { unloaded = true }
            override fun request(request: ByteArray): ByteArray {
                val text = request.toString(Charsets.UTF_8)
                requests += text
                return when {
                    text.startsWith("GET Version") -> "HEADLINE/2.0 200 OK\r\nCharset: UTF-8\r\n\r\n"
                    fail -> "HEADLINE/2.0 500 Error\r\n\r\n"
                    "old.xml" in text -> "HEADLINE/2.0 200 OK\r\nHeadline: Old\r\n\r\n"
                    else -> "HEADLINE/2.0 200 OK\r\nHeadline: New\r\n\r\n"
                }.toByteArray()
            }
        } }, { "Z:\\cache\\${it.name}" })
        val pair = module.analyze(Headline(dir), old, fresh)!!
        assertEquals(listOf(HeadlineItem("Old")), pair.first)
        assertEquals(listOf(HeadlineItem("New")), pair.second)
        assertTrue(unloaded)
        assertTrue(requests[1].contains("Option: url\r\nPath: Z:\\cache\\old.xml\r\n"))
        fail = true; unloaded = false
        assertNull(module.analyze(Headline(dir), old, fresh))
        assertTrue(unloaded)
    }
}
