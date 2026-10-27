package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** キャラクター自由移動（アプリの設定の3つ）と、指で動かしたバルーンのずれ・バルーン位置初期化・\![lock,balloonmove] */
class FreeMoveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var runs = 0

    private class PolicyPlatform(var policy: String) : GhostPlatform by RecordingPlatform() {
        override fun alignmentPolicy(): String = policy
    }

    private fun run(
        ghostDescript: String,
        replies: Map<String, String> = emptyMap(),
        platform: PolicyPlatform = PolicyPlatform("ghost"),
        ghost: Ghost? = null,
        block: suspend (GhostController, Ghost) -> Unit,
    ) = runBlocking<Unit> {
        val g = ghost ?: tmp.newTestGhost("ghost${runs++}", extraDescript = ghostDescript.replace("\n", "\r\n") + "\r\n")
        val scope = testScope()
        try {
            val c = testController(g, ScriptedShiori(replies), scope, platform)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            block(c, g)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GhostController.awaitState(check: (GhostState) -> Boolean) = withTimeout(5000) { state.first(check) }

    @Test
    fun theThreePolicies() {
        val platform = PolicyPlatform("ghost")
        run("seriko.alignmenttodesktop,top", mapOf("OnFree" to """\0\![set,alignmenttodesktop,free]\e"""), platform) { c, _ ->
            assertEquals("top", c.state.value.characters[0]?.alignment)
            // 常に自由移動: descript.txt も見ない
            platform.policy = "free"
            c.onAlignmentPolicyChanged()
            c.awaitState { it.characters[0]?.alignment == null }
            // 常に制限: ゴーストの指定に従う
            platform.policy = "limit"
            c.onAlignmentPolicyChanged()
            c.awaitState { it.characters[0]?.alignment == "top" }
            // スクリプトで自由にしても、下の端に付ける
            c.raise("OnFree", emptyList())
            c.awaitState { it.characters[0]?.alignment == "bottom" }
        }
        // 何も書いていなければ、ゴースト設定では自由・常に制限では下
        run("", platform = PolicyPlatform("ghost")) { c, _ -> assertNull(c.state.value.characters[0]?.alignment) }
        run("", platform = PolicyPlatform("limit")) { c, _ -> assertEquals("bottom", c.state.value.characters[0]?.alignment) }
        // 常に自由移動はスクリプトの指定も無視
        run("", mapOf("OnTop" to """\0\![set,alignmenttodesktop,top]\e"""), PolicyPlatform("free")) { c, _ ->
            c.raise("OnTop", emptyList())
            c.raise("OnTop", emptyList())
            assertNull(c.state.value.characters[0]?.alignment)
        }
    }

    @Test
    fun draggedBalloonIsRememberedAndReset() {
        var ghost: Ghost? = null
        run("", mapOf("OnReset" to """\0\![execute,resetballoonpos]\e""")) { c, g ->
            ghost = g
            // 動かしていなければ、メニューに初期化は出さない
            c.onMouseLongPress(0, 0, 0, null)
            c.awaitState { s -> s.characters.values.any { ch -> ch.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == "pono:close" } == true } }
            assertFalse(choiceIds(c).contains("pono:resetballoonpos"))
            c.onBalloonDragged(1, 10, -5, false)
            c.onBalloonDragged(1, 3, 2, true)
            c.awaitState { it.characters[1]?.balloonDrag == (13 to -3) }
            assertEquals("13,-3", g.loadProfile().getProperty("balloon.drag.1"))
        }
        // 次の起動でも残っている
        run("", mapOf("OnReset" to """\0\![execute,resetballoonpos]\e"""), ghost = ghost) { c, g ->
            assertEquals(13 to -3, c.state.value.characters[1]?.balloonDrag)
            c.onMouseLongPress(0, 0, 0, null)
            c.awaitState { s -> s.characters.values.any { ch -> ch.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == "pono:resetballoonpos" } == true } }
            c.raise("OnReset", emptyList())
            c.awaitState { it.characters[1]?.balloonDrag == null }
            assertNull(g.loadProfile().getProperty("balloon.drag.1"))
        }
    }

    @Test
    fun lockBalloonMove() {
        run("", mapOf("OnLock" to """\![lock,balloonmove]\e""", "OnUnlock" to """\![unlock,balloonmove]\e""")) { c, _ ->
            c.raise("OnLock", emptyList())
            c.awaitState { it.characters[0]?.balloonMoveLocked == true }
            c.onBalloonDragged(0, 10, 10, true)
            assertNull(c.state.value.characters[0]?.balloonDrag)
            c.raise("OnUnlock", emptyList())
            c.awaitState { it.characters[0]?.balloonMoveLocked == false }
            c.onBalloonDragged(0, 10, 10, true)
            c.awaitState { it.characters[0]?.balloonDrag == (10 to 10) }
        }
        // descript.txt の sakura.balloon.dontmove は外せない
        run("sakura.balloon.dontmove,true", mapOf("OnUnlock" to """\![unlock,balloonmove]\e""")) { c, g ->
            assertTrue(c.state.value.characters[0]?.balloonMoveLocked == true)
            assertFalse(c.state.value.characters[1]?.balloonMoveLocked == true)
            c.raise("OnUnlock", emptyList())
            c.onBalloonDragged(0, 5, 5, true)
            assertNull(c.state.value.characters[0]?.balloonDrag)
            assertFalse(File(g.dir, "ghost/master/profile").exists() && g.loadProfile().getProperty("balloon.drag.0") != null)
        }
    }

    private fun choiceIds(c: GhostController) =
        c.state.value.characters.values.flatMap { it.balloon.orEmpty().filterIsInstance<BalloonItem.ChoiceItem>() }.map { it.choice.id }
}
