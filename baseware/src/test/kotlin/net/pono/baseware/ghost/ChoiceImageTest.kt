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

/** \__q[…] と \__q の間の \_b[ファイル,inline] は、その選択肢の画像になる（画像だけのボタン。BalloonSelector など） */
class ChoiceImageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun inlineImageInsideAChoiceBelongsToIt() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnTest" to """\0\__q[OnPick,a]\_b[pic.png,inline,--clipping=0 0 4 4]\__q\_b[pic.png,inline]\e"""))
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            File(ghost.masterDir, "pic.png").writeBytes(ByteArray(8))
            val c = testController(ghost, shiori, scope)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            c.raise("OnTest", emptyList())
            val s = withTimeout(5000) { c.state.first { st -> st.characters[0]?.balloon.orEmpty().any { it is BalloonItem.ChoiceItem } && !st.talking } }
            val items = s.characters[0]!!.balloon!!
            val choice = items.filterIsInstance<BalloonItem.ChoiceItem>().single().choice
            assertEquals("OnPick", choice.id)
            assertEquals("", choice.title)
            assertTrue(choice.image!!.path.endsWith("pic.png"))
            assertEquals("0 0 4 4", choice.image!!.options.clip?.let { "${it.left} ${it.top} ${it.right} ${it.bottom}" })
            // 選択肢の外の画像は今までどおり文の中に
            assertEquals(1, items.filterIsInstance<BalloonItem.Image>().size)
        } finally {
            scope.cancel()
        }
    }
}
