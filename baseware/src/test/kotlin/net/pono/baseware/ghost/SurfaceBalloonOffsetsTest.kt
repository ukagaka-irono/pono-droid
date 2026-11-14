package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.shell.SurfaceTable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SurfaceBalloonOffsetsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesAliasesDirectionsAndAppendWithoutAcceptingUnknownKeys() {
        val table = SurfaceTable.parse("""
            surface0
            {
            offset.x,10
            balloon.offsetyl,20
            sakura.balloon.offsetxb,-30
            kero.balloon.offsety,40
            balloon.offsetzz,50
            balloon.offsetx,invalid
            }
            surface.append0
            {
            balloon.offsetyr,60
            }
        """.trimIndent())
        assertEquals(mapOf(
            "balloon.offsetx" to 10, "balloon.offsetyl" to 20,
            "sakura.balloon.offsetxb" to -30, "kero.balloon.offsety" to 40,
            "balloon.offsetyr" to 60,
        ), table[0]!!.balloonOffsets)
    }

    @Test fun changesOffsetsWithSurfaceAndRestoresDescriptFallbacks() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost(extraDescript = "sakura.balloon.offsety,5\n")
        File(ghost.shellDir()!!, "descript.txt").appendText("sakura.balloon.offsetxr,99\nsakura.balloon.offsetx,7\n")
        File(ghost.shellDir()!!, "surfaces.txt").writeText("""
            surface0
            {
            balloon.offsetx,10
            balloon.offsetyc,20
            sakura.balloon.offsetxb,30
            kero.balloon.offsetx,40
            }
            surface1
            {
            balloon.offsetyl,-12
            }
        """.trimIndent())
        val shiori = ScriptedShiori(mapOf(
            "OnFirstBoot" to "\\0\\s[0]\\1\\s[0]\\e",
            "OnNext" to "\\0\\s[1]\\e",
            "OnNoDefinition" to "\\0\\s[999]\\e",
        ))
        val scope = testScope()
        val c = testController(ghost, shiori, scope)
        try {
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            assertEquals(BalloonOffsets(10 to 5, 10 to 5, 10 to 20, 30 to 20), c.state.value.characters[0]!!.balloonOffsets)
            assertEquals(40 to 0, c.state.value.characters[1]!!.balloonOffsets.left)
            c.raise("OnNext", emptyList())
            val next = withTimeout(5000) { c.state.first { it.characters[0]?.surface == 1 } }
            assertEquals(BalloonOffsets(7 to -12, 99 to 5, 7 to 5, 7 to 5), next.characters[0]!!.balloonOffsets)
            c.raise("OnNoDefinition", emptyList())
            val missing = withTimeout(5000) { c.state.first { it.characters[0]?.surface == 999 } }
            assertEquals(BalloonOffsets(7 to 5, 99 to 5, 7 to 5, 7 to 5), missing.characters[0]!!.balloonOffsets)
        } finally {
            c.dispose().join()
            scope.cancel()
        }
    }
}
