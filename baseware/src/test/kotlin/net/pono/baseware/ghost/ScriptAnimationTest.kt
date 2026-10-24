package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.shell.PatternFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** \i[ID,wait]・\__w[animation,ID]・\![anim,pause / resume / add]・syncobject・collisionmode・selectmode */
class ScriptAnimationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val surfaces = """
        surface0 {
        collision0,10,20,30,40,Head
        animation5.interval,never
        animation5.name,まばたき
        animation5.pattern0,overlay,100,50,0,0
        }
    """.trimIndent()

    private fun run(replies: Map<String, String>, block: suspend (GhostController, ScriptedShiori) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            File(ghost.dir, "shell/master/surfaces.txt").writeText(surfaces)
            val c = testController(ghost, shiori, scope)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GhostController.awaitState(predicate: (GhostState) -> Boolean) = withTimeout(5000) { state.first(predicate) }

    private fun GhostState.commands(scope: Int) = characters[scope]?.animationCommands.orEmpty().map { it.second }

    private fun GhostState.text(scope: Int) =
        characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    @Test
    fun waitForAnimationByName() = run(mapOf("OnTest" to """\0\s[0]\i[まばたき,wait]あと\e""")) { c, _ ->
        c.raise("OnTest", emptyList())
        c.awaitState { AnimationCommand.Start(5) in it.commands(0) }
        // 表示する側がアニメーションを動かしている間は、続きを出さない
        c.onAnimationsPlaying(0, setOf(5))
        delay(300)
        assertEquals("", c.state.value.text(0))
        c.onAnimationsPlaying(0, emptySet())
        c.awaitState { it.text(0) == "あと" }
    }

    @Test
    fun waitDoesNotHangWhenNothingPlays() = run(mapOf("OnTest" to """\0\__w[animation,7]すぐ\e""")) { c, _ ->
        // 動いていないアニメーションは、少し待ってからあきらめる
        c.raise("OnTest", emptyList())
        c.awaitState { it.text(0) == "すぐ" }
    }

    @Test
    fun pauseResumeAndAdd() = run(
        mapOf("OnTest" to """\0\![anim,pause,まばたき]\![anim,resume,5]\![anim,add,overlay,30,4,5]\![anim,add,overlayfast,31,0,0,100,32,1,2,200]""" +
            """\![anim,add,base,40]\![anim,add,move,3,-4]\![anim,add,text,1,2,50,10,こんにちは,1500,255,0,0,14,serif]\e"""),
    ) { c, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.commands(0).any { cmd -> cmd is AnimationCommand.AddText } }
        val cmds = s.commands(0)
        assertTrue(AnimationCommand.Pause(5) in cmds)
        assertTrue(AnimationCommand.Resume(5) in cmds)
        val adds = cmds.filterIsInstance<AnimationCommand.Add>()
        assertEquals(listOf(PatternFrame("overlay", 30, 0, 0, 4, 5)), adds[0].frames)
        assertEquals(listOf(PatternFrame("overlay-fast", 31, 100, 100, 0, 0), PatternFrame("overlay-fast", 32, 200, 200, 1, 2)), adds[1].frames)
        assertEquals("base", adds[2].frames.single().method)
        assertEquals(PatternFrame("move", -1, 0, 0, 3, -4), adds[3].frames.single())
        assertEquals(
            AnimationCommand.AddText(1, 2, 50, 10, "こんにちは", 1500, 0xFF0000, 14, "serif"),
            cmds.filterIsInstance<AnimationCommand.AddText>().single(),
        )
    }

    @Test
    fun syncObjectsWaitUntilSet() = run(
        mapOf("OnWait" to """\0\![wait,syncobject,pono-test,--timeout=5000]届いた\e""", "OnTimeout" to """\0\![wait,syncobject,pono-none,100]時間切れ\e"""),
    ) { c, _ ->
        c.raise("OnWait", emptyList())
        delay(200)
        assertEquals("", c.state.value.text(0))
        SyncObjects.set("pono-test", true)
        c.awaitState { it.text(0) == "届いた" }
        SyncObjects.set("pono-test", false)
        c.raise("OnTimeout", emptyList())
        c.awaitState { it.text(0) == "時間切れ" }
    }

    @Test
    fun collisionMode() = run(mapOf("OnTest" to """\![enter,collisionmode,rect]\e""", "OnTest2" to """\![leave,collisionmode]\e""")) { c, _ ->
        c.raise("OnTest", emptyList())
        c.awaitState { it.collisionMode == "rect" }
        c.raise("OnTest2", emptyList())
        c.awaitState { it.collisionMode == null }
    }

    @Test
    fun selectModeCompletesWithTheRect() = run(
        mapOf("OnTest" to """\0\![enter,selectmode,rect,Head]\e""", "OnLeave" to """\![leave,selectmode]\e"""),
    ) { c, shiori ->
        c.raise("OnTest", emptyList())
        assertEquals(listOf("0", "rect"), shiori.awaitEvent("OnSelectModeBegin"))
        val s = c.awaitState { it.selectMode != null }
        // 選んでいる間はサーフェスを描き直さない
        assertNotNull(s.surfaceSnapshot)
        c.onSelectDown(0, 5, 6)
        assertEquals(listOf("0", "rect", "5,6"), shiori.awaitEvent("OnSelectModeMouseDown"))
        c.onSelectUp(0, 25, 36, SelectRect(5, 6, 25, 36))
        shiori.awaitEvent("OnSelectModeMouseUp")
        c.raise("OnLeave", emptyList())
        assertEquals(listOf("0", "rect", "5,6,25,36"), shiori.awaitEvent("OnSelectModeComplete"))
        val after = c.awaitState { it.selectMode == null }
        assertNull(after.surfaceSnapshot)
    }

    @Test
    fun selectModeCancelsWithoutASelection() = run(
        mapOf("OnTest" to """\0\![enter,selectmode]\e""", "OnLeave" to """\![leave,selectmode]\e"""),
    ) { c, shiori ->
        c.raise("OnTest", emptyList())
        shiori.awaitEvent("OnSelectModeBegin")
        c.raise("OnLeave", emptyList())
        shiori.awaitEvent("OnSelectModeCancel")
        assertFalse(shiori.count("OnSelectModeComplete") > 0)
    }

    @Test
    fun selectModeStartsFromTheCollision() = run(mapOf("OnTest" to """\0\![enter,selectmode,rect,Head]\e""")) { c, _ ->
        c.raise("OnTest", emptyList())
        // 当たり判定の名前を書けば、その範囲から始める（指で選ぶ前に終えれば、その範囲で完了）
        val s = c.awaitState { it.selectMode != null }
        assertEquals(SelectRect(10, 20, 30, 40), s.selectMode?.selection)
    }
}
