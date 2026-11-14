package net.pono.shiori.yaya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.pono.baseware.text.TextCharsets
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NashiNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "nashi-test")

    @After fun cleanUp() { root.deleteRecursively() }

    private fun ghost(scripts: String, charset: Charset = Charsets.UTF_8): File {
        root.mkdirs()
        File(root, "ghost.json").writeBytes("""
            {"format":"nashi/1","settings":{"randomTalkEnabled":false},
             "variables":[{"name":"count","value":0},{"name":"answer","value":"pending"}],
             "scripts":[$scripts]}
        """.trimIndent().toByteArray(charset))
        return root
    }

    private fun shiori() = NativeShiori(ShioriEngine.NASHI, NativeShiori.Sandbox(root, context.dataDir))

    private fun request(shiori: NativeShiori, event: String, reference: String = "", charset: Charset = Charsets.UTF_8): String {
        val name = if (charset == Charsets.UTF_8) "UTF-8" else "Shift_JIS"
        val bytes = "GET SHIORI/3.0\r\nCharset: $name\r\nSecurityLevel: local\r\nID: $event\r\nReference0: $reference\r\n\r\n"
        return requireNotNull(shiori.request(bytes.toByteArray(charset))).toString(Charsets.UTF_8)
    }

    @Test fun utf8AndCp932DictionaryAndRequest() {
        for (charset in listOf(Charsets.UTF_8, TextCharsets.CP932)) {
            ghost("""{"kind":"event","event":"OnTest","blocks":[
                {"type":"say","who":0,"text":"髙①ｶﾅ","nl":false},
                {"type":"say","who":0,"text":{"type":"ref","index":0},"nl":false}]}""", charset)
            val shiori = shiori()
            try {
                assertTrue(shiori.load(root))
                val reply = request(shiori, "OnTest", "ぽの", charset)
                assertTrue(reply, reply.startsWith("SHIORI/3.0 200"))
                assertTrue(reply, reply.contains("髙①ｶﾅ"))
                assertTrue(reply, reply.contains("ぽの"))
            } finally { shiori.unload() }
        }
    }

    @Test fun variablesSurviveUnloadAndReload() {
        ghost("""{"kind":"event","event":"OnCount","blocks":[
            {"type":"change","name":"count","value":1},
            {"type":"say","who":0,"text":{"type":"var","name":"count"},"nl":false}]}""")
        repeat(2) { index ->
            val shiori = shiori()
            try {
                assertTrue(shiori.load(root))
                val reply = request(shiori, "OnCount")
                assertTrue(reply, reply.contains("Value: \\0${index + 1}"))
            } finally { shiori.unload() }
        }
        assertTrue(File(root, "nashi_save.json").isFile)
        assertTrue(File(root, "nashi_save.bak.json").isFile)
        assertFalse(File(root, "nashi_save.json.tmp").exists())
    }

    @Test fun unsupportedSaoriReportsAnError() {
        ghost("""{"kind":"event","event":"OnTest","blocks":[
            {"type":"say","text":{"type":"saori","file":"unknown_windows.dll"},"nl":false}]}""")
        val shiori = shiori()
        try {
            assertTrue(shiori.load(root))
            val reply = request(shiori, "OnTest")
            assertTrue(reply, reply.contains("ErrorDescription:"))
            assertTrue(reply, reply.contains("unknown_windows.dll"))
        } finally { shiori.unload() }
    }

    @Test fun saoriCp932ResponseIsDecodedBeforeUtf8Repair() {
        ghost("""{"kind":"event","event":"OnTest","blocks":[
            {"type":"say","text":{"type":"saori","file":"internal_test.dll"},"nl":false}]}""")
        val shiori = shiori()
        shiori.setSaoriHandler { _, _ ->
            "SAORI/1.0 200 OK\r\nCharset: Shift_JIS\r\nResult: 髙①ｶﾅ\r\n\r\n".toByteArray(TextCharsets.CP932)
        }
        try {
            assertTrue(shiori.load(root))
            val reply = request(shiori, "OnTest")
            assertTrue(reply, reply.contains("髙①ｶﾅ"))
        } finally { shiori.unload() }
    }

    @Test fun asynchronousSaoriCompletesAfterTheShioriRequestHasReturned() {
        ghost("""{"kind":"event","event":"OnStart","blocks":[
            {"type":"saori_call","file":"internal_test.dll","into":"answer"}]},
            {"kind":"event","event":"OnRead","blocks":[
            {"type":"say","text":{"type":"var","name":"answer"},"nl":false}]}""")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val shiori = shiori()
        shiori.setSaoriHandler { request, _ ->
            if (request.toString(Charsets.UTF_8).startsWith("EXECUTE ")) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                completed.countDown()
            }
            "SAORI/1.0 200 OK\r\nCharset: UTF-8\r\nResult: 非同期の答え\r\n\r\n".toByteArray()
        }
        try {
            assertTrue(shiori.load(root))
            request(shiori, "OnStart")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var reply: String
            do {
                reply = request(shiori, "OnRead")
                if (reply.contains("非同期の答え")) break
                Thread.sleep(10)
            } while (System.nanoTime() < deadline)
            assertTrue(reply, reply.contains("非同期の答え"))
        } finally { release.countDown(); shiori.unload() }
    }
}
