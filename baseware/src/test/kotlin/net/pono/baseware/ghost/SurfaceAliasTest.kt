package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** \s[...] がシェルの surface.alias の別名で呼べること */
class SurfaceAliasTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun surfaceTagUsesShellAliases() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\s[照れ]\1\s[300]\0\s[35]こんにちは\e"""))
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            File(ghost.shellDir()!!, "surfaces.txt").writeText(
                "charset,UTF-8\nsakura.surface.alias\n{\n照れ,[1,101]\n35,[55]\n}\nkero.surface.alias\n{\n300,[600]\n}\n",
            )
            val c = testController(ghost, shiori, scope)
            c.start()
            withTimeout(5000) { c.state.first { it.scriptEnds == 1 } }
            val s = c.state.value
            // 本体は 35 → 55、相方は 300 → 600。照れ（1 か 101）は途中で 35 に替わっている
            assertEquals(55, s.characters[0]!!.surface)
            assertEquals(600, s.characters[1]!!.surface)
            // OnSurfaceChange には別名ではなく本当の番号を伝える
            assertTrue(shiori.events.filter { it.first == "OnSurfaceChange" }.any { it.second.getOrNull(2)?.startsWith("0,1,") == true || it.second.getOrNull(2)?.startsWith("0,101,") == true })
        } finally {
            scope.cancel()
        }
    }
}
