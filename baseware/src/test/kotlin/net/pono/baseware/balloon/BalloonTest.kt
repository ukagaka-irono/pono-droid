package net.pono.baseware.balloon

import net.pono.baseware.ghost.GhostRepository
import net.pono.baseware.ghost.NarInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BalloonTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Maid Classic と同じ書き方の descript.txt */
    private val descript = """
        charset,UTF-8
        type,balloon
        name,Maid Classic
        font.height,12
        font.color.r,58
        font.color.g,40
        font.color.b,68
        origin.x,22
        origin.y,24
        validrect.left,22
        validrect.top,24
        validrect.right,-18
        validrect.bottom,-28
        wordwrappoint.x,-20
        cursor.font.color.r,74
        cursor.font.color.g,52
        cursor.font.color.b,86
    """.trimIndent()

    private fun balloonDir(vararg images: String): File {
        val dir = tmp.newFolder("maid")
        File(dir, "descript.txt").writeText(descript)
        for (name in images) File(dir, name).writeBytes(byteArrayOf())
        return dir
    }

    @Test
    fun ignoresImageNamesWithOverflowingNumbers() {
        val balloon = Balloon(balloonDir("balloons0.png", "balloons99999999999.png", "balloonk1.png"))
        assertTrue(balloon.isValid)
        assertEquals(listOf(0), balloon.images(0).map { it.index })
        assertEquals(listOf(1), balloon.images(1).map { it.index })
    }

    @Test
    fun readsTextAreaAndColors() {
        val balloon = Balloon(balloonDir("balloons0.png"))
        // 負の値は右端・下端からの距離
        assertEquals(TextArea(22, 24, 310 - 18, 200 - 28), balloon.textArea(310, 200))
        assertEquals(12, balloon.fontHeight)
        assertEquals(BalloonColor(58, 40, 68), balloon.fontColor)
        // リンクの色が無ければ、カーソルの色で代わりにする
        assertNull(balloon.anchorColor)
        assertEquals(BalloonColor(74, 52, 86), balloon.choiceColor)
    }

    @Test
    fun keepsTextAreaInsideTinyImages() {
        val area = Balloon(balloonDir("balloons0.png")).textArea(30, 30)
        assertTrue(area.left >= 0 && area.right <= 30 && area.width > 0 && area.height > 0)
    }

    @Test
    fun picksImagesBySideAndFallsBack() {
        val balloon = Balloon(balloonDir("balloons0.png", "balloons1.png", "balloons2.png", "balloons3.png", "balloonk1.png"))
        // \0 は偶数番を小さい順、\1 は balloonk の奇数番
        assertEquals(listOf(0, 2), balloon.images(0).map { it.index })
        assertEquals(listOf("balloonk1.png"), balloon.images(1).map { it.file.name })

        // balloonk が無ければ balloons の奇数番で代わりにする
        val noKero = Balloon(File(tmp.root, "maid").apply { File(this, "balloonk1.png").delete() })
        assertEquals(listOf(1, 3), noKero.images(1).map { it.index })
    }

    @Test
    fun picksImageForBalloonIdBySide() {
        val balloon = Balloon(balloonDir("balloons0.png", "balloons1.png", "balloons2.png", "balloons3.png", "balloonk2.png", "balloonk3.png"))
        // \b[2] は組 2/3 のうち、\0 は偶数（2）、\1 は奇数（3）
        assertEquals(2, balloon.imageForId(0, 2)?.index)
        assertEquals(3, balloon.imageForId(1, 2)?.index)
        // \b[3] と書いても同じ組
        assertEquals(2, balloon.imageForId(0, 3)?.index)
        // 無い番号は null（文字の量で選ぶ）
        assertNull(balloon.imageForId(0, 4))
    }

    @Test
    fun arrowPositionsFromDescriptOrTextArea() {
        val dir = balloonDir("balloons0.png", "arrow0.png")
        File(dir, "descript.txt").appendText("\narrow0.x,-26\narrow0.y,14\n")
        val balloon = Balloon(dir)
        assertEquals(File(dir, "arrow0.png"), balloon.arrowImage(0))
        assertNull(balloon.arrowImage(1))
        // 負の値は右端から
        assertEquals(310 - 26 to 14, balloon.arrowPosition(0, 310, 200, 12, 12))
        // 書いてなければ文字の範囲の右下
        assertEquals((310 - 18 - 12) to (200 - 28 - 12), balloon.arrowPosition(1, 310, 200, 12, 12))
    }

    @Test
    fun uninstallsOnlyInsideTheBalloonFolder() {
        val repo = BalloonRepository(tmp.newFolder("balloon"))
        val inside = File(repo.root, "maid").apply { mkdirs(); File(this, "descript.txt").writeText(descript); File(this, "balloons0.png").writeBytes(byteArrayOf()) }
        val outside = Balloon(balloonDir("balloons0.png"))
        assertTrue(repo.uninstall(Balloon(inside)))
        assertTrue(!inside.exists())
        // 置き場の外にあるものは消さない
        assertEquals(false, repo.uninstall(outside))
        assertTrue(outside.dir.exists())
    }

    private fun nar(vararg files: Pair<String, String>): File {
        val nar = tmp.newFile("test${files.hashCode()}.nar")
        ZipOutputStream(nar.outputStream()).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return nar
    }

    @Test
    fun installsBalloonNarAndBundledBalloon() {
        val repo = GhostRepository(File(tmp.root, "files/ghost"))
        val balloonNar = nar(
            "install.txt" to "charset,UTF-8\ntype,balloon\nname,Maid Classic\ndirectory,maid_classic\n",
            "descript.txt" to descript,
            "balloons0.png" to "",
        )
        val result = repo.installer().install(balloonNar)
        assertEquals(NarInstaller.Type.BALLOON, result.type)
        assertEquals("Maid Classic", repo.balloons.find("maid_classic")?.name)

        // ゴーストの .nar に入っているバルーンも、バルーン置き場に入る
        val ghostNar = nar(
            "install.txt" to "type,ghost\ndirectory,g\n",
            "ghost/master/descript.txt" to "name,テスト\nballoon,同梱バルーン\n",
            "balloon/bundled/descript.txt" to "name,同梱バルーン\n",
            "balloon/bundled/balloons0.png" to "",
        )
        assertEquals(NarInstaller.Type.GHOST, repo.installer().install(ghostNar).type)
        val ghost = repo.find("g")!!
        // 何も選んでいなければ、ゴーストの descript.txt の balloon 指定を使う
        assertEquals("同梱バルーン", ghost.balloon(repo.balloons)?.name)
        // 簡易バルーンを選べば null
        ghost.selectBalloon(null)
        assertNull(ghost.balloon(repo.balloons))
        ghost.selectBalloon("maid_classic")
        assertEquals("Maid Classic", ghost.balloon(repo.balloons)?.name)
    }
}
