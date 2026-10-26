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

/** シェルとゴーストの descript.txt の既定（名前・吸着・バルーンの置き方とずれ・バルーンの番号・重なり順・don't need） */
class DescriptDefaultsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var runs = 0

    private fun run(
        ghostDescript: String,
        shellDescript: String,
        replies: Map<String, String> = emptyMap(),
        block: suspend (GhostController) -> Unit,
    ) = runBlocking<Unit> {
        val ghost = tmp.newTestGhost("ghost${runs++}", extraDescript = ghostDescript.replace("\n", "\r\n") + "\r\n")
        File(ghost.shellDir()!!, "descript.txt").writeText("charset,UTF-8\r\nname,master\r\n" + shellDescript.replace("\n", "\r\n") + "\r\n")
        val scope = testScope()
        try {
            val c = testController(ghost, ScriptedShiori(replies), scope)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            block(c)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GhostController.awaitState(check: (GhostState) -> Boolean) = withTimeout(5000) { state.first(check) }

    @Test
    fun shellNamesOverrideUnlessForbidden() {
        run("sakura.name,ゴースト\nkero.name,うにゅう", "sakura.name,シェルの名前") { c ->
            assertEquals("シェルの名前", c.property("currentghost.scope(0).name"))
            assertEquals("うにゅう", c.property("currentghost.scope(1).name"))
        }
        run("sakura.name,ゴースト\nname.allowoverride,0", "sakura.name,シェルの名前") { c ->
            assertEquals("ゴースト", c.property("currentghost.scope(0).name"))
        }
    }

    @Test
    fun alignmentDefaultsAndScriptOverrides() = run(
        "seriko.alignmenttodesktop,bottom",
        "kero.seriko.alignmenttodesktop,top",
        mapOf(
            "OnFree" to """\0\![set,alignmenttodesktop,free]\e""",
            "OnDefault" to """\0\![set,alignmenttodesktop,default]\e""",
        ),
    ) { c ->
        assertEquals("bottom", c.state.value.characters[0]?.alignment)
        assertEquals("top", c.state.value.characters[1]?.alignment)
        c.raise("OnFree", emptyList())
        c.awaitState { it.characters[0]?.alignment == null }
        c.raise("OnDefault", emptyList())
        c.awaitState { it.characters[0]?.alignment == "bottom" }
    }

    @Test
    fun balloonAlignAndOffsets() = run(
        "sakura.balloon.alignment,none\nballoon.defaultsurface,2\nkero.balloon.defaultsurface,3",
        "sakura.balloon.offsetx,10\nsakura.balloon.offsetyc,5\nsakura.balloon.offsetxb,7",
        mapOf(
            "OnLeft" to """\0\![set,balloonalign,left]\e""",
            "OnNone" to """\0\![set,balloonalign,none]\e""",
            "OnOffset" to """\0\![set,balloonoffset,@3,4]\e""",
        ),
    ) { c ->
        val sakura = c.state.value.characters[0]!!
        assertEquals("auto", sakura.balloonAlign)
        assertEquals(BalloonOffsets(left = 10 to 0, right = 10 to 0, center = 10 to 5, bottom = 7 to 5), sakura.balloonOffsets)
        assertEquals(2, sakura.defaultBalloonId)
        assertEquals(3, c.state.value.characters[1]?.defaultBalloonId)
        c.raise("OnLeft", emptyList())
        c.awaitState { it.characters[0]?.balloonAlign == "left" }
        c.raise("OnNone", emptyList())
        c.awaitState { it.characters[0]?.balloonAlign == "auto" }
        c.raise("OnOffset", emptyList())
        val after = c.awaitState { it.characters[0]?.balloonOffset != null }.characters[0]!!
        assertEquals(3 to 4, after.balloonOffset)
        assertEquals(true to false, after.balloonOffsetRelative)
    }

    @Test
    fun windowDefaultsAndDontNeed() = run(
        "don't need bind,1\ndon't need seriko talk,1\nballoon.syncscale,true",
        "seriko.zorder,1,0\nseriko.sticky-window,0,1\nsakura.bindgroup5.name,服,制服\nsakura.bindgroup5.default,1",
    ) { c ->
        val s = c.state.value
        assertTrue(s.window.zorder.isNotEmpty())
        assertEquals(listOf(setOf(0, 1)), s.window.stickyGroups)
        assertTrue(s.characters[0]!!.binds.isEmpty())
        assertTrue(c.serikoTalkDisabled)
        assertTrue(c.balloonSyncScale(0))
    }

    @Test
    fun nothingWrittenKeepsTheOldBehaviour() = run("", "") { c ->
        val sakura = c.state.value.characters[0]!!
        assertNull(sakura.alignment)
        assertNull(sakura.balloonAlign)
        assertNull(sakura.defaultBalloonId)
        assertEquals(BalloonOffsets(), sakura.balloonOffsets)
        assertFalse(c.serikoTalkDisabled)
        assertFalse(c.balloonSyncScale(0))
    }
}
