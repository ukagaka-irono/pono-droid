package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

/** \![open,ipinput]、\![open,dialog,...]、開発用の画面を開くタグ */
class DialogsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ipInputParsesBothForms() {
        assertEquals(listOf(192, 168, 0, 1), InputRequest.fromOpenTag("ipinput", listOf("IP", "30000", "192", "168", "0", "1"), 1)!!.values)
        val r = InputRequest.fromOpenTag("ipinput", listOf("IP", "--timeout=30000", "--text=10,0,300,x"), 2)!!
        assertEquals(InputRequest.Kind.IP, r.kind)
        assertEquals(30000L, r.timeoutMs)
        assertEquals(listOf(10, 0, 255, 0), r.values)
    }

    private class DialogPlatform(val inner: RecordingPlatform = RecordingPlatform(), val dev: Boolean = false) : GhostPlatform by inner {
        val dialogs: MutableList<SystemDialogRequest> = Collections.synchronizedList(mutableListOf())
        val closed: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val screens: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun openSystemDialog(request: SystemDialogRequest): Boolean {
            dialogs += request
            return true
        }

        override fun closeSystemDialog(id: String?) {
            closed += id
        }

        override fun developerMode() = dev

        override fun openDeveloperScreen(page: String) {
            screens += page
        }
    }

    private fun run(script: String, dev: Boolean = false, block: suspend (GhostController, ScriptedShiori, DialogPlatform) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to script))
        val scope = testScope()
        try {
            val platform = DialogPlatform(dev = dev)
            val c = testController(tmp.newTestGhost(), shiori, scope, platform)
            c.start()
            // 起動のスクリプトを話し終えるまで（話し始める前に「話していない」を見ないように）
            withTimeout(5000) { c.state.first { it.booted } }
            block(c, shiori, platform)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ipAnswerIsOnUserInput() = run("""\0\![open,ipinput,IP入力,0,192,168,0,1]\e""") { c, shiori, _ ->
        val input = withTimeout(5000) { c.state.first { it.input != null } }.input!!
        c.onInputSubmit(input.serial, "192.168.0.1")
        assertEquals(listOf("IP入力", "192.168.0.1"), shiori.awaitEvent("OnUserInput"))
    }

    @Test
    fun dialogsReportTheirAnswers() = run(
        """\0\![open,dialog,save,--title=保存,--filter=テキスト|*.txt;*.dic|全部|*.*,--ext=txt,--name=test,--id=保存]""" +
            """\![open,dialog,color,"--color=255 0 0",--id=OnColor]\![open,dialog,folder]\![open,dialog,nope]\![close,dialog,__SYSTEM_ALL_DIALOG__]\e""",
    ) { c, shiori, platform ->
        // スクリプトが最後まで進むのを待つ（負荷が高いと、話し始める前に「話していない」になることがある）
        withTimeout(5000) { while (platform.closed.isEmpty() || platform.inner.logs.none { "nope" in it }) delay(10) }
        val (save, color, folder) = platform.dialogs.toList()
        assertEquals("save", save.kind)
        assertEquals(listOf("テキスト" to "*.txt;*.dic", "全部" to "*.*"), save.filters)
        assertEquals("test.txt", save.defaultName)
        assertEquals(0xFF0000, color.color)
        assertEquals("folder", folder.kind)
        assertEquals(listOf<String?>(null), platform.closed.toList())
        assertTrue(platform.inner.logs.any { it.startsWith("未対応のタグ") && "nope" in it })
        c.onSystemDialogResult(save.serial, "/ghost/pono/dialog/save/test.txt")
        assertEquals(listOf("save", "保存", "/ghost/pono/dialog/save/test.txt"), shiori.awaitEvent("OnSystemDialog"))
        c.onSystemDialogResult(color.serial, "255,0,0")
        assertEquals(listOf("color", "OnColor", "255,0,0"), shiori.awaitEvent("OnColor"))
        c.onSystemDialogResult(folder.serial, null)
        assertEquals(listOf("folder", ""), shiori.awaitEvent("OnSystemDialogCancel"))
        // 答えたダイアログには、もう答えられない
        c.onSystemDialogResult(save.serial, "again")
        delay(200)
        assertEquals(1, shiori.count("OnSystemDialog"))
    }

    @Test
    fun developerScreensNeedTheSetting() = run("""\0\![open,backlogviewer]\![open,errorlog]\![open,surfacetest]\![open,developer]\![open,shiorirequest]\0おわり\e""") { c, _, platform ->
        withTimeout(5000) { c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.Text && "おわり" in it.text } == true } }
        assertEquals(listOf("backlog", "log"), platform.screens.toList())
    }

    @Test
    fun developerScreensOpenWithTheSetting() = run("""\0\![open,surfacetest]\![open,developer]\![open,shiorirequest]\e""", dev = true) { _, _, platform ->
        withTimeout(5000) { while (platform.screens.size < 3) delay(10) }
        assertEquals(listOf("surfacetest", "developer", "shiorirequest"), platform.screens.toList())
    }

    @Test
    fun shioriRequestFromTheDeveloperScreen() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnTest" to "\\0ためし\\e"))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope, RecordingPlatform())
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            assertEquals("\\0ためし\\e", c.sendShioriRequest("OnTest", listOf("a", "b"), play = false))
            assertEquals(listOf("a", "b"), shiori.awaitEvent("OnTest"))
            assertEquals(null, c.sendShioriRequest(" ", emptyList(), play = true))
        } finally {
            scope.cancel()
        }
    }
}
