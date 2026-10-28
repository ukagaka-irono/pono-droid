package net.pono.baseware.saori

import net.pono.baseware.ghost.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

/** 互換モジュール（textcopy・clipcopy・saori_ip・saori_cpuid・cpuusage・string・httpc・mciaudio・houchi・hanayu） */
class CompatSaoriTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Env(override val ghostMaster: File?, override val ghostRoot: File?) : SaoriEnv {
        override val random = SecureRandom()
        val copied = mutableListOf<String>()
        val events = mutableListOf<Pair<String, List<String>>>()
        val audio = mutableListOf<Pair<String, String>>()
        var pages = mapOf<String, ByteArray>()
        var houchiStarted = 0
        var hanayu: Pair<String, List<Pair<String, Double>>>? = null

        override fun protect(data: ByteArray) = data
        override fun unprotect(data: ByteArray) = data
        override fun async(work: () -> Pair<String, List<String>>) {
            events += work()
        }

        override fun copyText(text: String): Boolean {
            copied += text
            return true
        }

        override fun localAddress() = "192.168.0.5"
        override fun hostName() = "pixel"
        override fun device() = DeviceInfo(osName = "Android", osVersion = "15", cpuCount = 8, memoryTotalMb = 8000, memoryFreeMb = 2000, cpuVendor = "Qualcomm", cpuClockMhz = 2400)
        override fun cpuLoad() = 37
        override fun online() = true
        override fun httpGet(url: String) = pages[url]
        override fun audio(command: String, file: File) {
            audio += command to file.name
        }

        override fun houchi() {
            houchiStarted++
        }

        override fun showHanayu(style: String, data: List<Pair<String, Double>>): Boolean {
            hanayu = style to data
            return true
        }
    }

    private val ghostRoot by lazy { tmp.newFolder("ghost1") }
    private val master by lazy { File(ghostRoot, "ghost/master").also { it.mkdirs() } }
    private val env by lazy { Env(master, ghostRoot) }
    private val saori by lazy { InternalSaori(env) }

    /** YAYA が DLL として読んだもの（Module ヘッダ無し）と同じに呼ぶ */
    private fun call(module: String, vararg args: String, method: String = "EXECUTE"): SaoriResponse {
        val sb = StringBuilder("$method SAORI/1.0\r\nCharset: UTF-8\r\nSender: test\r\n")
        args.forEachIndexed { i, a -> sb.append("Argument$i: $a\r\n") }
        sb.append("\r\n")
        return saori.answer(SaoriRequest.parse(sb.toString().toByteArray())!!, InternalSaori.moduleNameOf("saori\\$module.dll"))
    }

    @Test
    fun dllNamesAreRecognised() {
        assertEquals("textcopy", InternalSaori.moduleNameOf("C:\\ghost\\saori\\TextCopy.DLL"))
        assertEquals("string", InternalSaori.moduleNameOf("string.dll"))
        assertEquals(null, InternalSaori.moduleNameOf("other.dll"))
    }

    @Test
    fun textcopyAndClipcopy() {
        assertEquals(204, call("textcopy", "こんにちは").code)
        val back = call("textcopy", "a|b", "1", "|")
        assertEquals(200 to "a|b", back.code to back.result)
        assertEquals(400, call("textcopy", "").code)
        assertEquals(204, call("clipcopy", "1行目\\n2行目\\\\nのまま").code)
        assertEquals(listOf("こんにちは", "a\r\nb", "1行目\r\n2行目\\nのまま"), env.copied)
    }

    @Test
    fun ipCpuidAndCpuusage() {
        assertEquals("192.168.0.5", call("saori_ip", "GetLocalIP").result)
        assertEquals("pixel", call("saori_ip", "getlocalhost").result)
        assertEquals(500, call("saori_ip", "GetInternetIP").code)
        assertEquals(400, call("saori_ip", "nothing").code)
        val r = call("saori_cpuid", "cpu.num", "mem.os", "platform", "cpu.mmx", "unknown.key")
        assertEquals("8", r.result)
        assertEquals(listOf("8", "75", "pono-droid", "Not Ready", ""), r.values)
        assertEquals(400, call("saori_cpuid", "nothing").code)
        assertEquals(204, call("saori_cpuid").code)
        assertEquals("37", call("cpuusage", "get").result)
    }

    @Test
    fun stringRegexAndConversions() {
        assertEquals("1", call("string", "match", "abc123", "/([a-z]+)(\\d+)/").result)
        assertEquals("abc", call("string", "get", "1").result)
        assertEquals("2", call("string", "match", "a1b2", "m/\\d/g").result)
        assertEquals("2", call("string", "replace", "a-b-c", "s/-(\\w)/[$1]/g").result)
        assertEquals("a[b][c]", call("string", "get", "0").result)
        assertEquals("3", call("string", "split", "a,b,,c,,", "/,+/").result)
        assertEquals("c", call("string", "get", "2").result)
        assertEquals(400, call("string", "match", "x", "/(/").code)
        assertEquals("1", call("string", "isinteger", "-42").result)
        assertEquals("0", call("string", "isdecimal", "1.2.3").result)
        assertEquals("ＡＢ１", call("string", "tozenkaku", "AB1").result)
        assertEquals("AB1かな", call("string", "tohankaku", "ＡＢ１かな").result)
        assertEquals("x y", call("string", "trim", "\u3000 x y\t").result)
        assertEquals(400, call("string", "nothing").code)
    }

    @Test
    fun httpcFetchesExtractsSavesAndRunsInTheBackground() {
        env.pages = mapOf(
            "https://e.test/a" to "<html><meta charset=\"euc-jp\"><b>はい</b>\r\n<li>1</li><li>2</li>".toByteArray(charset("EUC-JP")),
        )
        assertEquals("1", call("httpc").result)
        assertEquals("はい12", call("httpc", "erase_tag", "https://e.test/a").result)
        val hits = call("httpc", "https://e.test/a", "<li>", "</li>")
        assertEquals("1" to listOf("2"), hits.result to hits.values)
        assertEquals("1", call("httpc", "https://e.test/a", "save/a.html").result)
        assertTrue(File(master, "save/a.html").isFile)
        // ゴーストのフォルダの外には保存しない
        assertEquals("0", call("httpc", "https://e.test/a", "../../../out.html").result)
        assertEquals("", call("httpc", "bg", "id1", "https://e.test/a", "<li>", "</li>").result)
        assertEquals("OnHttpcNotify" to listOf("id1", "", "1", "2"), env.events.single())
        assertEquals("bgするならIDを指定していただけませんと。", call("httpc", "bg").result)
    }

    @Test
    fun mciaudioHouchiAndHanayu() {
        File(master, "bgm.mp3").writeBytes(byteArrayOf(0))
        assertEquals(400, call("mciaudio", "play").code)
        assertEquals(204, call("mciaudio", "load", "bgm.mp3").code)
        call("mciaudio", "loop")
        call("mciaudio", "stop")
        assertEquals(listOf("loop" to "bgm.mp3", "stop" to "bgm.mp3"), env.audio)
        assertEquals(200, call("houchi", method = "GET Version").code)
        assertEquals(1, env.houchiStarted)
        assertEquals(204, call("hanayu", "show", "週", "月", "3", "火", "x").code)
        assertEquals("週" to listOf("月" to 3.0, "火" to 0.0), env.hanayu)
        assertEquals(400, call("hanayu", "hide").code)
    }
}
