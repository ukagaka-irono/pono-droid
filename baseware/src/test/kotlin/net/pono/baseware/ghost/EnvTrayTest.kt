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
import kotlin.random.Random

/** 環境変数の残り（単語・%exh・%et・%wronghour・%* など）と、トレイの通知 */
class EnvTrayTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class TrayPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val balloons: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
        val trayTexts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun showTrayBalloon(title: String, text: String, icon: String) {
            balloons += Triple(title, text, icon)
        }

        override fun setTrayText(text: String) {
            trayTexts += text
        }
    }

    private fun run(script: String, block: suspend (GhostController, ScriptedShiori, TrayPlatform) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to script))
        val scope = testScope()
        try {
            val platform = TrayPlatform()
            platform.inner.device = DeviceInfo(
                osName = "Android", osVersion = "15", osBuild = "35", arch = "arm64", cpuCount = 8,
                memoryTotalMb = 8000, memoryFreeMb = 4000, uptimeSeconds = 3 * 3600 + 59, screenWidth = 1080, screenHeight = 2400, dpi = 420,
            )
            val c = testController(tmp.newTestGhost(), shiori, scope, platform)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            withTimeout(5000) { c.state.first { !it.talking } }
            block(c, shiori, platform)
        } finally {
            scope.cancel()
        }
    }

    private fun GhostController.text(scope: Int) =
        state.value.characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    @Test
    fun wordsAndClockVariables() = run("""\0x[%exh][%et][%wronghour][%ms][%m?][%dms]\e""") { c, _, _ ->
        val parts = Regex("""\[([^\]]*)]""").findAll(c.text(0)).map { it.groupValues[1] }.toList()
        assertEquals(6, parts.size)
        assertEquals("3", parts[0])
        assertTrue(parts[1], Regex("""\d+(年|万年|億年|世紀|光年)""").matches(parts[1]))
        assertTrue(parts[2].toInt() in 0..23)
        assertTrue(parts.drop(3).all { it.isNotEmpty() && !it.startsWith("%") })
        assertTrue(parts[5], "で" in parts[5] && "する" in parts[5])
    }

    @Test
    fun dmsIsBuiltFromTheTables() {
        val r = Random(1)
        val v = EnvWords.pick("dms", r)!!
        assertTrue(v, Regex(""".+で.+する.+""").matches(v))
        assertEquals(null, EnvWords.pick("mx"))
    }

    @Test
    fun percentStarIsAMarkerButEscapedIsText() = run("""\0前%*後\%*そのまま\e""") { c, _, _ ->
        val items = c.state.value.characters[0]!!.balloon!!
        assertEquals(1, items.count { it == BalloonItem.Marker })
        assertEquals("前後%*そのまま", c.text(0))
    }

    @Test
    fun trayBalloonAndIcon() = run(
        """\0\![set,trayballoon,--title=お知らせ,--text=1行目,--text=2行目,--icon=warning]\![set,tasktrayicon,normal.ico,通常モード]\![set,trayballoon,--title=空]\e""",
    ) { c, shiori, platform ->
        // 話し始める前に「話していない」を見ることがあるので、最後のタグが済むまで待つ
        withTimeout(5000) { while (platform.inner.logs.none { "--title=空" in it }) delay(10) }
        assertEquals(listOf(Triple("お知らせ", "1行目\n2行目", "warning")), platform.balloons.toList())
        assertEquals(listOf("通常モード"), platform.trayTexts.toList())
        // --text が無いものは出さない
        assertTrue(platform.inner.logs.any { it.startsWith("未対応のタグ") && "--title=空" in it })
        c.onTrayBalloonClick("お知らせ", "1行目\n2行目")
        assertEquals(listOf("お知らせ", "1行目\u00012行目"), shiori.awaitEvent("OnTrayBalloonClick"))
        c.onTrayBalloonTimeout("お知らせ", "x")
        assertEquals(listOf("お知らせ", "x"), shiori.awaitEvent("OnTrayBalloonTimeout"))
    }
}
