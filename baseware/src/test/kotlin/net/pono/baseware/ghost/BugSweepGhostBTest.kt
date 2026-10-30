package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.SstpServer
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BugSweepGhostBTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(
        replies: Map<String, String> = emptyMap(),
        headers: Map<String, Map<String, String>> = emptyMap(),
        block: suspend (GhostController, ScriptedShiori, RecordingPlatform) -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies, headers)
        val platform = RecordingPlatform()
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope, platform)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            block(c, shiori, platform)
        } finally {
            scope.cancel()
        }
    }

    private fun GhostState.text(scope: Int) =
        characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    @Test
    fun b01_cancellingAChoiceScriptClearsWaitingChoice() = run(
        mapOf("OnMenu" to """\0\q[はい,OnYes]\e""", "OnPoke" to """\0つん\e"""),
    ) { c, _, _ ->
        c.raise("OnMenu", emptyList())
        withTimeout(5000) { c.state.first { it.waitingChoice } }
        c.raise("OnPoke", emptyList())
        withTimeout(5000) { c.state.first { "つん" in it.text(0) && !it.talking } }
        delay(300)
        assertFalse("選択肢はもう出ていないのに waitingChoice が残っている", c.state.value.waitingChoice)
    }

    @Test
    fun b02_sstpChoiceCannotRunAFullyTrustedScript() = run { c, _, platform ->
        fun req(text: String) = SstpServer.parse(text.replace("\n", "\r\n").toByteArray())!!
        val script = """\0押してね\q[OK,"script:\j[https://evil.example/]"]\e"""
        assertEquals(200, c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: app\nScript: $script\n\n")).code)
        val choice = withTimeout(5000) {
            c.state.first { it.waitingChoice }.characters[0]!!.balloon!!
                .filterIsInstance<BalloonItem.ChoiceItem>().single().choice
        }
        c.onChoice(choice)
        delay(500)
        assertTrue("SSTP の選択肢から \\j が制限なしで動いた: ${platform.urls}", platform.urls.isEmpty())
    }

    private class TranslatingShiori(val inner: ScriptedShiori) : Shiori by inner {
        override fun request(request: ByteArray): ByteArray {
            val lines = request.toString(Charsets.UTF_8).split("\r\n")
            if ("ID: OnTranslate" in lines) {
                val script = lines.firstOrNull { it.startsWith("Reference0: ") }?.removePrefix("Reference0: ").orEmpty()
                return "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\nValue: $script\\_w[1]\r\n\r\n".toByteArray()
            }
            return inner.request(request)
        }
    }

    @Test
    fun b03_externalScriptStaysUntrustedAfterOnTranslate() = runBlocking<Unit> {
        val inner = ScriptedShiori(
            mapOf("OnTest" to """\0外から\![open,browser,https://example.test/]\e"""),
            mapOf("OnTest" to mapOf("SecurityLevel" to "external")),
        )
        val platform = RecordingPlatform()
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), TranslatingShiori(inner), scope, platform)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            c.raise("OnTest", emptyList())
            withTimeout(5000) { c.state.first { "外から" in it.text(0) && !it.talking } }
            assertTrue("SecurityLevel: external のスクリプトがブラウザを開いた: ${platform.urls}", platform.urls.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun b04_valueNotifyCannotRaiseItselfForever() = run(
        headers = mapOf("OnLoop" to mapOf("ValueNotify" to """\![raise,OnLoop]""")),
    ) { c, shiori, _ ->
        c.raise("OnLoop", emptyList())
        delay(2000)
        val n = shiori.count("OnLoop")
        assertTrue("OnLoop が $n 回送られた（連鎖の上限が効いていない）", n <= GhostController.MAX_RAISE_CHAIN + 1)
    }

    @Test
    fun b05_choicesAreWaitedEvenWhenTheScriptAlsoRaises() = run(
        mapOf("OnMenu" to """\0\q[はい,OnYes]\![raise,OnNothing]\e"""),
    ) { c, shiori, _ ->
        c.raise("OnMenu", emptyList())
        shiori.awaitEvent("OnNothing")
        withTimeout(5000) { c.state.first { !it.talking } }
        delay(300)
        assertTrue("選択肢が出ているのに選択待ちになっていない", c.state.value.waitingChoice)
    }

    @Test
    fun b06_openAiGraphIsNotHiddenByTheOldScriptsBalloonTimeout() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnTest" to """\0見てね\![open,aigraph]\e""", "getaistate" to "10,20,30"))
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            val c = GhostController(
                ghost = ghost, shell = Shell(ghost.shellDir()!!), shioriFactory = { _, _ -> shiori }, scope = scope,
                platform = RecordingPlatform(), player = ScriptPlayer(charWaitMs = 0), balloonTimeoutMs = 300,
            )
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            c.raise("OnTest", emptyList())
            withTimeout(5000) { c.state.first { "AI グラフ" in it.text(0) } }
            delay(1000)
            assertTrue("AI グラフの一覧が、前のスクリプトのバルーンのタイムアウトで消えた", "AI グラフ" in c.state.value.text(0))
            assertEquals(0, shiori.count("OnBalloonTimeout"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun b07_changingShellInSelectModeDoesNotFreezeSurfaces() = run(
        mapOf(
            "OnTest" to """\0\![enter,selectmode,rect]\e""",
            "OnLeave" to """\![leave,selectmode]\e""",
            "OnAfter" to """\0\s[0]あと\e""",
        ),
    ) { c, shiori, _ ->
        c.raise("OnTest", emptyList())
        withTimeout(5000) { c.state.first { it.selectMode != null } }
        c.changeShell("master")
        shiori.awaitEvent("OnShellChanged")
        c.raise("OnLeave", emptyList())
        delay(300)
        c.raise("OnAfter", emptyList())
        withTimeout(5000) { c.state.first { "あと" in it.text(0) && !it.talking } }
        assertNull("選択モードを抜けたのにサーフェスの描き直しが止まったまま", c.state.value.surfaceSnapshot)
    }

    @Test
    fun b08_scalingIgnoresNaN() = run(
        mapOf("OnNaN" to """\0\![set,scaling,NaN]\e""", "OnBack" to """\0\![set,scaling,100]\e"""),
    ) { c, _, _ ->
        c.raise("OnNaN", emptyList())
        delay(300)
        c.raise("OnBack", emptyList())
        delay(300)
        assertEquals(1f, c.state.value.characters[0]!!.scaleX, 0f)
    }

    @Test
    fun b09_animationPropertyCannotCreateHugeScopes() = run(
        mapOf("OnTest" to """\0\![set,property,currentghost.scope(4000).animation.num,1]\e"""),
    ) { c, _, _ ->
        c.raise("OnTest", emptyList())
        delay(500)
        assertFalse(c.state.value.characters.keys.any { it > ScriptPlayer.MAX_SCOPE })
    }

    @Test
    fun b10_shellNameWithParenthesesCanBeSelected() {
        val ghost = tmp.newTestGhost()
        File(ghost.dir, "shell/winter").apply { mkdirs() }.resolve("descript.txt").writeText("charset,UTF-8\r\nname,通常(冬服)\r\n")
        val scope = testScope()
        try {
            val c = testController(ghost, ScriptedShiori(emptyMap()), scope)
            assertEquals(File(ghost.dir, "shell/winter").absolutePath + "/", c.property("currentghost.shelllist(通常(冬服)).path"))
        } finally {
            scope.cancel()
        }
    }

    private class SlowSecondShiori(val inner: ScriptedShiori) : Shiori by inner {
        override fun request(request: ByteArray): ByteArray {
            if ("\r\nID: OnSecondChange\r\n" in request.toString(Charsets.UTF_8)) Thread.sleep(2000)
            return inner.request(request)
        }
    }

    @Test
    fun b11_slowOnSecondChangeDoesNotPileUp() = runBlocking<Unit> {
        val inner = ScriptedShiori(emptyMap())
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), SlowSecondShiori(inner), scope)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            delay(9000)
            c.raise("OnPing", emptyList())
            inner.awaitEvent("OnPing", timeoutMs = 3500)
        } finally {
            scope.cancel()
        }
    }
}
