package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ChoiceLooks
import net.pono.baseware.sakura.LinkLook
import net.pono.baseware.sakura.ScriptPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 選択肢とリンクの見た目（\f[cursor*] など）、長押しの OnChoiceEnter、バルーンの置き方と印 */
class BalloonChoicesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(replies: Map<String, String>, block: suspend (GhostController, ScriptedShiori) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GhostController.awaitState(predicate: (GhostState) -> Boolean) = withTimeout(5000) { state.first(predicate) }

    private fun GhostState.choices(scope: Int) = characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.ChoiceItem>()

    private fun ScriptedShiori.all(id: String) = synchronized(events) { events.filter { it.first == id }.map { it.second } }

    @Test
    fun choiceLooksFollowTheFontTags() = run(
        mapOf(
            "OnTest" to """\0\f[cursorstyle,underline]\f[cursorbrushcolor,red]\f[cursornotselectfontcolor,#07f]""" +
                """\f[anchor.font.color,0,128,0]\f[anchorvisitedstyle,square]\f[cursormethod,notmaskpen]\q[はい,OnYes]""" +
                """\f[default]\_a[OnLink]リンク\_a\e""",
        ),
    ) { c, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.choices(0).size == 2 }
        val (yes, link) = s.choices(0)
        assertEquals(
            ChoiceLooks(
                cursor = LinkLook(shape = "underline", brush = 0xFF0000),
                cursorNotSelect = LinkLook(font = 0x0077FF),
                anchorNotSelect = LinkLook(font = 0x008000),
                anchorVisited = LinkLook(shape = "square"),
            ),
            yes.looks,
        )
        // \f[default] で戻る
        assertEquals(ChoiceLooks.Default, link.looks)
        assertTrue(link.choice.anchor)
    }

    @Test
    fun linkLookFillsFromTheBalloon() {
        val script = LinkLook(font = 1)
        assertEquals(LinkLook(shape = "square", brush = 2, font = 1), script.or(LinkLook(shape = "square", brush = 2, font = 3)))
        assertEquals(0x0077FF, ScriptPlayer.parseColor(listOf("#07f")))
    }

    @Test
    fun longPressSendsEnterHoverAndLeave() = run(mapOf("OnTest" to """\0\q[はい,OnYes,r2]\_a[OnLink,x]リンク\_a\e""")) { c, shiori ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.waitingChoice }
        val (yes, link) = s.choices(0).map { it.choice }
        c.onChoiceHover(yes)
        assertEquals(listOf("はい", "OnYes", "r2"), shiori.awaitEvent("OnChoiceHover"))
        c.onChoiceHover(null)
        withTimeout(5000) { while (shiori.count("OnChoiceEnter") < 2) delay(10) }
        assertEquals(listOf(listOf("はい", "OnYes", "r2"), emptyList()), shiori.all("OnChoiceEnter"))
        c.onChoiceHover(link)
        assertEquals(listOf("リンク", "OnLink", "x"), shiori.awaitEvent("OnAnchorHover"))
        shiori.awaitEvent("OnAnchorEnter")
        // カーソルを乗せただけでは選ばない
        assertTrue(c.state.value.waitingChoice)
    }

    @Test
    fun visitedAnchorsAreRemembered() = run(mapOf("OnTest" to """\0\_a[OnLink]リンク\_a\e""")) { c, shiori ->
        c.raise("OnTest", emptyList())
        // リンクだけのスクリプトは選択肢を待たない（話し終えても、出ているリンクは選べる）
        val s = c.awaitState { it.choices(0).isNotEmpty() && !it.talking }
        assertFalse(s.waitingChoice)
        c.onChoice(s.choices(0).single().choice.copy(title = "リンク"))
        shiori.awaitEvent("OnAnchorSelect")
        c.awaitState { "OnLink" in it.visitedAnchors }
    }

    @Test
    fun choicesStaySelectableWhileTalking() = run(mapOf("OnTest" to """\0\q[はい,OnYes]まだ話している\_w[3000]\e""")) { c, shiori ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.choices(0).isNotEmpty() && it.talking }
        // 選択肢を待つ前（話している途中）でも、出ている選択肢は選べる。題名の空白が落ちていても同じもの
        c.onChoice(Choice(" はい ", "OnYes"))
        shiori.awaitEvent("OnYes")
        c.awaitState { !it.talking }
        // 出ていない選択肢は選べない
        c.onChoice(Choice("いいえ", "OnNo"))
        delay(200)
        assertEquals(0, shiori.count("OnNo"))
        assertTrue(s.talking)
    }

    @Test
    fun balloonPlacementAndLabels() = run(
        mapOf(
            "OnTest" to """\1\![set,balloonoffset,@10,-20]\![set,balloonalign,top]\![set,balloonmarker,ぽの通信]""" +
                """\![set,balloonnum,update.txt,1,5]\e""",
            "OnTest2" to """\1\![set,balloonalign,none]\![set,balloonmarker]\![set,balloonnum,,,]\e""",
        ),
    ) { c, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.characters[1]?.balloonNum != null }.characters[1]!!
        assertEquals(10 to -20, s.balloonOffset)
        // top は center と同じ
        assertEquals("center", s.balloonAlign)
        assertEquals("ぽの通信", s.balloonMarker)
        assertEquals("update.txt 1/5", s.balloonNum)
        c.raise("OnTest2", emptyList())
        val r = c.awaitState { it.characters[1]?.balloonNum == null }.characters[1]!!
        assertNull(r.balloonAlign)
        assertNull(r.balloonMarker)
        // ずらした量はそのまま
        assertEquals(10 to -20, r.balloonOffset)
    }

    @Test
    fun balloonScalingSendsBothAxes() = run(emptyMap()) { c, shiori ->
        c.onBalloonScaling(120, 100)
        assertEquals(listOf("120", "100", "120", "100"), shiori.awaitEvent("OnBalloonScaling"))
    }
}
