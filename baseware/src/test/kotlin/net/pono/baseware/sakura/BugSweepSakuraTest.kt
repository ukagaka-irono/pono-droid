package net.pono.baseware.sakura

import kotlinx.coroutines.test.runTest
import net.pono.baseware.balloon.BalloonRepository
import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BugSweepSakuraTest {
    @get:Rule val tmp = TemporaryFolder()

    private open class MiniHost : ScriptHost {
        val log = mutableListOf<String>()
        override fun setSurface(scope: Int, surface: Int) {}
        override fun appendText(scope: Int, text: String) { log += "t$scope:$text" }
        override fun newLine(scope: Int) { log += "n$scope" }
        override fun clearBalloon(scope: Int) {}
        override fun addChoice(scope: Int, choice: Choice) { log += "q:${choice.title}/${choice.id}" }
        override suspend fun waitClick() {}
        override fun raise(event: String, references: List<String>) {}
        override fun openUrl(url: String) {}
        override fun envVariable(name: String): String? = null
    }

    @Test fun s01_longWordAfterPercent() = runTest {
        var calls = 0
        val host = object : MiniHost() { override fun envVariable(name: String): String? { calls++; return null } }
        ScriptPlayer(charWaitMs = 0).play("%" + "a".repeat(20_000), host)
        assertTrue("envVariable was called $calls times", calls < 100)
    }

    @Test fun s02_hugeCoordinatesStayFinite() {
        val huge = "1" + "0".repeat(40)
        val down = BalloonCoord.parse(huge)
        val back = BalloonCoord.parse("@-$huge")
        val pen = down?.resolve(10f, 14f, 2f, 0f, 0f) ?: 0f
        val y = back?.resolve(10f, 14f, 2f, pen, 0f) ?: pen
        assertTrue("pen=$pen y=$y", pen.isFinite() && y.isFinite())
    }

    @Test fun s03_strayQuote() {
        val tokens = SakuraScript.tokenize("""\q[17" monitor,OnX]続き\e""")
        assertEquals(tokens.toString(), Token.Tag("e"), tokens.last())
    }

    @Test fun s04_escapedBackslashBeforeBracket() {
        val tokens = SakuraScript.tokenize("""\![raise,OnX,C:\\]続き""")
        assertEquals(tokens.toString(), Token.Text("続き"), tokens.last())
    }

    @Test fun s05_doubleUnderscoreTag() {
        val tokens = SakuraScript.tokenize("""\__q[OnA]Yes\__qand more""")
        assertTrue(tokens.toString(), tokens.contains(Token.Tag("__q")))
    }

    @Test fun s06_emojiAfterBackslash() {
        val tokens = SakuraScript.tokenize("a\\😀b")
        val lone = tokens.any { t ->
            val s = when (t) { is Token.Text -> t.text; is Token.Tag -> t.name; else -> "" }
            s.indices.any { i -> val c = s[i]
                (Character.isHighSurrogate(c) && (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1]))) ||
                (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s[i - 1]))) }
        }
        assertTrue(tokens.toString(), !lone)
    }

    @Test fun s07_waitWithSpaces() = runTest {
        val start = testScheduler.currentTime
        ScriptPlayer(charWaitMs = 0, unitWaitMs = 50).play("""\_w[ 300]\w[ 2]""", MiniHost())
        assertEquals(400L, testScheduler.currentTime - start)
    }

    @Test fun s08_unclosedAnchor() = runTest {
        val h = MiniHost()
        ScriptPlayer(charWaitMs = 0).play("""\__q[OnA]はい""", h)
        assertTrue(h.log.toString(), h.log.any { "はい" in it })
    }

    @Test fun s09_balloonFindDotDot() {
        val root = tmp.newFolder("balloon")
        val outside = tmp.newFolder("outside")
        File(outside, "descript.txt").writeText("name,外\n")
        File(outside, "balloons0.png").writeBytes(byteArrayOf())
        assertNull(BalloonRepository(root).find("../outside"))
    }

    @Test fun s10_bomBrokenByte() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "name,あいう\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte()) + "\n".toByteArray()
        assertEquals("あいう", Descript.parse(TextCharsets.decode(bytes))["name"])
    }

    @Test fun s11_privateUse() = runTest {
        val h = MiniHost()
        ScriptPlayer(charWaitMs = 0).play("\uE000", h)
        assertEquals(listOf("t0:\uE000"), h.log)
    }
}
