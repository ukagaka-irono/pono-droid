package net.pono.baseware.ghost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

class InputBoxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesOpenTags() {
        assertEquals(
            InputRequest(InputRequest.Kind.INPUT, "OnInputName", "ぽの", 5000, 1),
            InputRequest.fromOpenTag("inputbox", listOf("OnInputName", "5000", "ぽの"), 1),
        )
        assertEquals(InputRequest.Kind.PASSWORD, InputRequest.fromOpenTag("PasswordInput", listOf("pw"), 2)?.kind)
        assertEquals(InputRequest(InputRequest.Kind.COMMUNICATE, "", "", 0, 3), InputRequest.fromOpenTag("communicatebox", emptyList(), 3))
        // ID の無い inputbox と知らない種類は扱わない
        assertNull(InputRequest.fromOpenTag("inputbox", emptyList(), 4))
        assertNull(InputRequest.fromOpenTag("dateinput", listOf("x"), 5))
    }

    /** 送られたイベントを覚えておき、決めておいたスクリプトを返す SHIORI */
    private class ScriptedShiori(val replies: Map<String, String>) : Shiori {
        val events: MutableList<Pair<String, List<String>>> = Collections.synchronizedList(mutableListOf())

        override fun load(dir: File) = true

        override fun request(request: ByteArray): ByteArray {
            val headers = request.toString(Charsets.UTF_8).split("\r\n")
                .mapNotNull { line -> line.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                .toMap()
            val id = headers["ID"].orEmpty()
            events += id to generateSequence(0) { it + 1 }.map { headers["Reference$it"] }.takeWhile { it != null }.filterNotNull().toList()
            val value = replies[id]?.let { "Value: $it\r\n" }.orEmpty()
            return "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\n$value\r\n".toByteArray()
        }

        override fun unload() {}
    }

    @Test
    fun answersAndCancelsSendSspEvents() = runBlocking {
        val ghostDir = tmp.newFolder("ghost")
        File(ghostDir, "ghost/master").mkdirs()
        File(ghostDir, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,テスト\r\nshiori,yaya.dll\r\n")
        val shellDir = File(ghostDir, "shell/master").apply { mkdirs() }
        File(shellDir, "descript.txt").writeText("charset,UTF-8\r\nname,master\r\n")
        val shiori = ScriptedShiori(
            mapOf(
                "OnFirstBoot" to """\0\![open,inputbox,OnInputName,0,ぽの]\e""",
                "OnInputName" to """\0\![open,communicatebox]\e""",
                "OnCommunicateInputCancel" to """\0\![open,inputbox,MyBox,200]\e""",
                "OnUserInputCancel" to """\0\![open,teachbox]\![close,teachbox]\e""",
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = GhostController(
            ghost = Ghost(ghostDir),
            shell = Shell(shellDir),
            shioriFactory = { _, _ -> shiori },
            scope = scope,
            platform = object : GhostPlatform {
                override fun openUrl(url: String) {}
                override fun onQuit() {}
                override fun log(message: String) {}
            },
            player = ScriptPlayer(charWaitMs = 0),
        )
        suspend fun awaitInput() = withTimeout(5000) { controller.state.first { it.input != null } }.input!!
        suspend fun awaitEvent(id: String) = withTimeout(5000) {
            while (shiori.events.none { it.first == id }) delay(10)
            shiori.events.last { it.first == id }.second
        }
        try {
            controller.start()

            // 名前の入力。ID が On で始まるので、そのイベントに文字だけを渡す
            val name = awaitInput()
            assertEquals(InputRequest(InputRequest.Kind.INPUT, "OnInputName", "ぽの", 0, name.serial), name)
            controller.onInputSubmit(name.serial, "たろう")
            assertEquals(listOf("たろう"), awaitEvent("OnInputName"))

            // 話しかける箱を取り消す
            val talk = awaitInput()
            assertEquals(InputRequest.Kind.COMMUNICATE, talk.kind)
            // 古い箱への答えは無視される
            controller.onInputSubmit(name.serial, "古い")
            controller.onInputCancel(talk.serial)
            assertEquals(listOf("", "cancel"), awaitEvent("OnCommunicateInputCancel"))

            // 時間切れ
            awaitInput()
            assertEquals(listOf("MyBox", "timeout"), awaitEvent("OnUserInputCancel"))

            // スクリプトの中で開いて閉じた箱は残らない
            delay(300)
            assertNull(controller.state.value.input)
            assertEquals(emptyList<String>(), shiori.events.map { it.first }.filter { it == "OnUserInput" || it == "OnCommunicate" })
        } finally {
            scope.cancel()
        }
    }
}
