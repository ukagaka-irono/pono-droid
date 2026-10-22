package net.pono.baseware.sakura

import kotlinx.coroutines.test.runTest
import net.pono.baseware.sakura.Token.Tag
import net.pono.baseware.sakura.Token.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SakuraScriptTest {
    @Test
    fun tokenizesTagsAndText() {
        val tokens = SakuraScript.tokenize("""\h\s0こんにちは\w9\n\u\s[10]やあ\_w[300]\e""")
        assertEquals(
            listOf(
                Tag("h"), Tag("s", listOf("0")), Text("こんにちは"), Tag("w", listOf("9")), Tag("n"),
                Tag("u"), Tag("s", listOf("10")), Text("やあ"), Tag("_w", listOf("300")), Tag("e"),
            ),
            tokens,
        )
    }

    @Test
    fun parsesQuotedArgsAndEscapes() {
        val tokens = SakuraScript.tokenize("""\q["a,b",OnTest,r0]\\\]\![raise,OnX,"1,2"]""")
        assertEquals(
            listOf(
                Tag("q", listOf("a,b", "OnTest", "r0")),
                Text("\\]"),
                Tag("!", listOf("raise", "OnX", "1,2")),
            ),
            tokens,
        )
    }

    @Test
    fun parsesUnderscoreTags() {
        val tokens = SakuraScript.tokenize("""\__q[ID1]選択\__q\_q早い\_q""")
        assertEquals(
            listOf(Tag("__q", listOf("ID1")), Text("選択"), Tag("__q"), Tag("_q"), Text("早い"), Tag("_q")),
            tokens,
        )
    }

    @Test
    fun playerReportsChoicesSurfacesAndEnv() = runTest {
        val log = mutableListOf<String>()
        val host = object : ScriptHost {
            override fun setSurface(scope: Int, surface: Int) { log += "s$scope=$surface" }
            override fun appendText(scope: Int, text: String) { log += "t$scope:$text" }
            override fun newLine(scope: Int) { log += "n$scope" }
            override fun clearBalloon(scope: Int) { log += "c$scope" }
            override fun addChoice(scope: Int, choice: Choice) { log += "q$scope:${choice.title}/${choice.id}" }
            override suspend fun waitClick() {}
            override fun raise(event: String, references: List<String>) { log += "raise:$event" }
            override fun openUrl(url: String) {}
            override fun envVariable(name: String) = if (name == "username") "ぽの" else null
        }
        val result = ScriptPlayer(charWaitMs = 0).play("""\0\s[5]%usernameさん\% \n\q[はい,OnYes]\__q[ID2]いいえ\__q\-""", host)
        assertEquals(
            listOf("s0=5", "t0:ぽのさん% ", "n0", "q0:はい/OnYes", "q0:いいえ/ID2"),
            log,
        )
        assertTrue(result.quit)
        assertTrue(result.hasChoices)
    }
}
