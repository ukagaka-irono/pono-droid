package net.pono.shiori.yaya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.pono.baseware.shiori.ShioriSession
import net.pono.baseware.text.TextCharsets
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

@RunWith(AndroidJUnit4::class)
class Kawari7NativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "kawari7-test")
    @After fun cleanUp() { root.deleteRecursively() }

    private fun ghost(dictionary: String, charset: Charset = TextCharsets.CP932, name: String = "ghost", extra: String = ""): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "kawari.ini").writeText("locale : ${if (charset == Charsets.UTF_8) "ja_JP.UTF-8" else "ja_JP.SJIS"}\ndict : dict.txt\n$extra\n")
        // '%' avoids Kotlin interpolation of Kawari's ${...} references and $(...) commands.
        File(dir, "dict.txt").writeBytes(dictionary.trimIndent().replace('%', '$').toByteArray(charset))
        return dir
    }
    private fun native(dir: File) = NativeShiori(ShioriEngine.KAWARI7, NativeShiori.Sandbox(dir, context.dataDir))

    @Test fun japaneseDictionariesAndRequestsUseTheirDeclaredCharset() = runBlocking {
        for (charset in listOf(TextCharsets.CP932, Charsets.UTF_8)) {
            val dir = ghost("event.OnBoot : \\0髙①ｶﾅ%{system.Reference0}\\e\nevent.OnIndex : [%(expr index abc z)]|%(expr index abc b)", charset)
            val session = ShioriSession(native(dir), Charsets.UTF_8)
            try {
                assertTrue(session.load(dir))
                assertEquals(charset, session.charset)
                assertEquals("7.5.0", session.get("version")?.value)
                assertEquals("\\0髙①ｶﾅぽの\\e", session.get("OnBoot", "ぽの")?.value)
                assertEquals("[]|2", session.get("OnIndex")?.value)
            } finally { session.unload() }
        }
    }

    @Test fun legacyResourcesCommunicationTeachTranslateAndGhostNotifications() = runBlocking {
        val dir = ghost("""
            resource.homeurl : https://example.com/ghost/
            event.OnNames : %{system.Ghost}|%(size system.OtherGhost)|%(size system.OtherGhostEx)
            system.OnResponse : %{system.Sender}|%{system.Sentence}|%{system.Reference0}
            system.OnTeach : taught:%{system.Sentence}
            system.OnTranslate : translated:%{system.Sentence}
        """)
        val session = ShioriSession(native(dir), Charsets.UTF_8)
        try {
            assertTrue(session.load(dir))
            assertEquals("https://example.com/ghost/", session.get("homeurl")?.value)
            session.notify("ownerghostname", "ぽの")
            session.notify("otherghostname", "あいて\u00010\u000110", "もう一人\u00011\u000111")
            assertEquals("ぽの|2|2", session.get("OnNames")?.value)
            val reply = session.get("OnCommunicate", "あいて", "こんにちは", "追加")
            assertEquals("あいて|こんにちは|追加", reply?.value)
            assertEquals("あいて", reply?.reference(0))
            assertEquals("taught:言葉", session.get("OnTeach", "言葉")?.value)
            assertEquals("translated:台詞", session.get("OnTranslate", "台詞")?.value)
        } finally { session.unload() }
    }

    @Test fun saoriWorksInAllThreeLoadingModes() = runBlocking {
        for (mode in listOf("preload", "loadoncall", "noresident")) {
            val dir = ghost("event.OnTest : %(callsaori calc \"\" 1+2*3)|%(callsaori host ぽの)",
                extra = "saori : calcs.dll,calc,$mode\nsaori : internal_test.dll,host,$mode")
            File(dir, "calcs.dll").writeBytes(ByteArray(4))
            val shiori = native(dir)
            shiori.setSaoriHandler { request, _ ->
                val text = request.toString(TextCharsets.CP932)
                if (text.startsWith("EXECUTE")) assertTrue(text, text.contains("Argument0: ぽの"))
                "SAORI/1.0 200 OK\r\nCharset: Shift_JIS\r\nResult: 日本語\r\n\r\n".toByteArray(TextCharsets.CP932)
            }
            val session = ShioriSession(shiori, Charsets.UTF_8)
            try {
                assertTrue(session.load(dir))
                repeat(2) { assertEquals(mode, "7|日本語", session.get("OnTest")?.value) }
            } finally { session.unload() }
        }
    }

    @Test fun independentInstancesRetainTheirDictionaryAndLocale() = runBlocking {
        val extra = "saori : internal_test.dll,host,loadoncall"
        val sjis = ghost("event.OnTest : 一人目%{system.Reference0}\nsystem.OnUnload : %(callsaori host 一人目)", name = "sjis", extra = extra)
        val utf8 = ghost("event.OnTest : 二人目%{system.Reference0}\nsystem.OnUnload : %(callsaori host 二人目)", Charsets.UTF_8, "utf8", extra)
        val unloadCalls = mutableListOf<String>()
        fun instance(dir: File, name: String, charset: Charset): NativeShiori = native(dir).apply {
            setSaoriHandler { bytes, _ ->
                val text = bytes.toString(charset)
                if (text.startsWith("EXECUTE")) {
                    assertTrue(text, text.contains("Argument0: $name"))
                    unloadCalls += name
                }
                "SAORI/1.0 200 OK\r\n\r\n".toByteArray()
            }
        }
        val first = ShioriSession(instance(sjis, "一人目", TextCharsets.CP932), Charsets.UTF_8)
        val second = ShioriSession(instance(utf8, "二人目", Charsets.UTF_8), Charsets.UTF_8)
        try {
            assertTrue(first.load(sjis)); assertTrue(second.load(utf8))
            repeat(2) {
                assertEquals("一人目ぽの", first.get("OnTest", "ぽの")?.value)
                assertEquals("二人目ぽの", second.get("OnTest", "ぽの")?.value)
            }
        } finally { first.unload(); second.unload() }
        assertEquals(listOf("一人目", "二人目"), unloadCalls)
    }

    @Test fun remoteServiceUsesLegacyProtocolToo() = runBlocking {
        val dir = ghost("event.OnBoot : \\0別プロセスからこんにちは\\e")
        val session = ShioriSession(RemoteShiori(context, ShioriEngine.KAWARI7), Charsets.UTF_8)
        try {
            assertTrue(session.load(dir))
            assertEquals("\\0別プロセスからこんにちは\\e", session.get("OnBoot")?.value)
        } finally { session.unload() }
    }

    @Test fun savedDictionaryReloadsAndCannotOverwriteSiblingGhost() = runBlocking {
        val dir = ghost("""
            system.OnLoad : %(load save.txt)
            event.OnSet : %(set user.name 保存した名前)%(save save.txt user.name)done
            event.OnGet : %{user.name}
            event.OnEscape : %(save ../sentinel.txt user.name)done
            event.OnUnknown : %(saoriregist unknown_windows.dll unknown)%(callsaori unknown)done
        """)
        val sentinel = File(root, "sentinel.txt").apply { writeText("unchanged") }
        val first = ShioriSession(native(dir), TextCharsets.CP932)
        try {
            assertTrue(first.load(dir))
            assertEquals("done", first.get("OnSet")?.value)
            assertTrue(File(dir, "save.txt").isFile)
            assertEquals("done", first.get("OnEscape")?.value)
            assertEquals("unchanged", sentinel.readText())
            assertEquals("done", first.get("OnUnknown")?.value)
        } finally { first.unload() }
        val second = ShioriSession(native(dir), TextCharsets.CP932)
        try {
            assertTrue(second.load(dir))
            assertEquals("保存した名前", second.get("OnGet")?.value)
        } finally { second.unload() }
    }
}
