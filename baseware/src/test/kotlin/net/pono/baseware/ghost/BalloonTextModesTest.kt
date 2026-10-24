package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** \t・\![set,serikotalk]・\![set,autoscroll]・\![lock]・\![enter,...mode]・\c[char] と、\f[name] のフォントファイル */
class BalloonTextModesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(replies: Map<String, String>, block: suspend (GhostController, ScriptedShiori, Ghost) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            val c = testController(ghost, shiori, scope)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori, ghost)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GhostController.awaitState(predicate: (GhostState) -> Boolean) = withTimeout(5000) { state.first(predicate) }

    private fun GhostState.text(scope: Int) =
        characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    @Test
    fun timeCriticalSectionBlocksTouchUntilTheScriptEnds() = run(mapOf("OnTest" to """\t\0待って\_w[400]\e""")) { c, shiori, _ ->
        c.raise("OnTest", emptyList())
        c.awaitState { it.timeCritical }
        c.onMouseDown(0, 1, 2, null)
        c.onTouchEnter(0, 1, 2, null)
        c.awaitState { !it.talking }
        assertFalse(c.state.value.timeCritical)
        assertEquals(0, shiori.count("OnMouseDown"))
        assertEquals(0, shiori.count("OnMouseEnterAll"))
        c.onMouseDown(0, 1, 2, null)
        shiori.awaitEvent("OnMouseDown")
    }

    @Test
    fun serikoTalkOffDoesNotCountCharacters() = run(
        mapOf("OnTest" to """\0\![set,serikotalk,false]だまって\e""", "OnTest2" to """\0はなす\e"""),
    ) { c, _, _ ->
        c.raise("OnTest", emptyList())
        c.awaitState { it.text(0) == "だまって" && !it.talking }
        assertEquals(0, c.state.value.characters[0]?.talkCount)
        // 次のスクリプトでは元に戻る
        c.raise("OnTest2", emptyList())
        c.awaitState { it.text(0) == "はなす" && !it.talking }
        assertEquals(3, c.state.value.characters[0]?.talkCount)
    }

    @Test
    fun autoscrollIsResetWithTheBalloon() = run(
        mapOf("OnTest" to """\0\![set,autoscroll,disable]ながい\e""", "OnTest2" to """\0つぎ\e"""),
    ) { c, _, _ ->
        c.raise("OnTest", emptyList())
        c.awaitState { it.text(0) == "ながい" }
        assertEquals(false, c.state.value.characters[0]?.autoScroll)
        c.raise("OnTest2", emptyList())
        c.awaitState { it.text(0) == "つぎ" }
        assertEquals(true, c.state.value.characters[0]?.autoScroll)
    }

    @Test
    fun balloonLockKeepsTheOldPictureUntilTheScriptEnds() = run(
        mapOf("OnTest" to """\0まえ\![lock,balloonrepaint]あと\_w[200]\e""", "OnTest2" to """\0\![lock,repaint,manual]ずっと\e"""),
    ) { c, _, _ ->
        c.raise("OnTest", emptyList())
        val locked = c.awaitState { it.text(0) == "まえあと" }
        // 止めたときの見た目（「まえ」まで）を持つ
        assertEquals("まえ", locked.balloonSnapshot?.get(0)?.balloon?.filterIsInstance<BalloonItem.Text>()?.joinToString("") { it.text })
        assertNull(locked.surfaceSnapshot)
        c.awaitState { !it.talking }
        assertNull(c.state.value.balloonSnapshot)
        // manual なら、スクリプトが終わっても止めたまま。サーフェスも止める
        c.raise("OnTest2", emptyList())
        c.awaitState { it.text(0) == "ずっと" && !it.talking }
        delay(100)
        assertNotNull(c.state.value.balloonSnapshot)
        assertNotNull(c.state.value.surfaceSnapshot)
    }

    @Test
    fun modesStayUntilLeft() = run(
        mapOf(
            "OnTest" to """\![enter,onlinemode]\![enter,nouserbreakmode]\![enter,inductionmode]\0ながい話\_w[3000]\e""",
            "OnTest2" to """\![leave,onlinemode]\![leave,nouserbreakmode]\![leave,inductionmode]\e""",
        ),
    ) { c, shiori, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.online && it.noUserBreak && it.induction && it.text(0) == "ながい話" }
        assertTrue(c.property("currentghost.status").orEmpty().split(',').containsAll(listOf("online", "nouserbreak", "induction")))
        // 話している途中でバルーンを叩いても止まらない
        c.onBalloonClick(0)
        delay(200)
        assertTrue(c.state.value.talking)
        assertEquals(0, shiori.count("OnBalloonBreak"))
        // 「話せるか」は 0
        assertEquals("0", shiori.awaitEvent("OnSecondChange", timeoutMs = 3000)[3])
        assertTrue(s.online)
    }

    @Test
    fun talkStartsAndEndsPerCharacter() = run(mapOf("OnTest" to """\0はじめ\1あいて\0もどる\e""")) { c, _, _ ->
        c.raise("OnTest", emptyList())
        c.awaitState { !it.talking && it.text(0) == "はじめもどる" }
        val s = c.state.value
        // \0 は2回話し始め、\1 に移ったときと終わりで2回話し終える。\1 は1回ずつ
        assertEquals(2, s.characters[0]?.talkStarts)
        assertEquals(2, s.characters[0]?.talkEnds)
        assertEquals(1, s.characters[1]?.talkStarts)
        assertEquals(1, s.characters[1]?.talkEnds)
    }

    @Test
    fun partialClearEditsTheBalloon() = run(mapOf("OnTest" to """\0トークを3文字削除\c[char,3]削除終了。\e""")) { c, _, _ ->
        c.raise("OnTest", emptyList())
        c.awaitState { it.text(0) == "トークを3文削除終了。" }
    }

    @Test
    fun fontFilesResolveInsideTheGhost() = run(
        mapOf("OnTest" to """\0\f[name,mine.ttf,../../outside.ttf,serif]字\e"""),
    ) { c, _, ghost ->
        File(ghost.masterDir, "mine.ttf").writeBytes(byteArrayOf(0, 1, 0, 0))
        File(ghost.dir.parentFile, "outside.ttf").writeBytes(byteArrayOf(0, 1, 0, 0))
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.text(0) == "字" }
        val font = s.characters[0]!!.balloon!!.filterIsInstance<BalloonItem.Text>().single().style.font
        // ゴーストの外を指すファイルは使わない（空にする）。名前はそのまま
        assertEquals(listOf(File(ghost.masterDir, "mine.ttf").canonicalPath, "", "serif"), font)
    }
}
