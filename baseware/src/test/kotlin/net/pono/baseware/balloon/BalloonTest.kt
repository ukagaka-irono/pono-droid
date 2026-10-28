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
    fun recommendedGhostReadmeAndCraftmanUrl() {
        val dir = tmp.newFolder("only")
        File(dir, "descript.txt").writeText("charset,UTF-8\r\nname,専用\r\nrecommended.ghost.path,ghost\\Emily4\r\ncraftmanurl,https://example.com/\r\nreadme,doc.txt\r\nreadme.charset,Shift_JIS\r\n")
        File(dir, "balloons0.png").writeBytes(byteArrayOf())
        File(dir, "doc.txt").writeBytes("せつめい".toByteArray(charset("windows-31j")))
        val b = Balloon(dir)
        // recommended.ghost.path はフォルダ名で比べる（大文字小文字は見ない）
        assertEquals(true, b.isRecommendedFor("えみり", "emily4"))
        assertEquals(false, b.isRecommendedFor("ほか", "other"))
        assertEquals("Emily4", b.recommendedGhostLabel)
        assertEquals("https://example.com/", b.craftmanUrl)
        assertEquals("せつめい", b.readmeText())
        // 推奨を書いていなければ null。javascript: などのURLは使わない
        File(dir, "descript.txt").writeText("charset,UTF-8\r\nname,ふつう\r\ncraftmanurl,javascript:alert(1)\r\nreadme,../outside.txt\r\n")
        File(tmp.root, "outside.txt").writeText("外")
        val plain = Balloon(dir)
        assertNull(plain.isRecommendedFor("ほか", "other"))
        assertNull(plain.craftmanUrl)
        assertNull(plain.readmeFile())
        val repo = BalloonRepository(tmp.root)
        assertEquals(listOf<String>(), repo.recommendedFor("ほか", "other").map { it.name })
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
        // 折り返しは wordwrappoint.x（validrect.right より優先）。負の値は右端・下端からの距離
        assertEquals(TextArea(22, 24, 310 - 20, 200 - 28), balloon.textArea(310, 200))
        assertEquals(12, balloon.fontHeight)
        assertEquals(BalloonColor(58, 40, 68), balloon.fontColor)
        // リンクの色が無ければ、カーソルの色で代わりにする
        assertNull(balloon.anchorColor)
        assertEquals(BalloonColor(74, 52, 86), balloon.choiceColor)
    }

    @Test
    fun textStartsAtOriginAndZeroMeansEdge() {
        // Emily/Phase4.5 のバルーンの書き方。validrect は 0 で「端まで」、文字は origin から wordwrappoint まで
        val dir = tmp.newFolder("emily")
        File(dir, "descript.txt").writeText(
            "charset,UTF-8\norigin.x,20\norigin.y,40\nwordwrappoint.x,-34\n" +
                "validrect.left,0\nvalidrect.top,40\nvalidrect.right,0\nvalidrect.bottom,-10\n",
        )
        File(dir, "balloons0.png").writeBytes(byteArrayOf())
        assertEquals(TextArea(20, 40, 340 - 34, 300 - 10), Balloon(dir).textArea(340, 300))

        // validrect.right,0 だけなら右端まで
        val dir2 = tmp.newFolder("edge")
        File(dir2, "descript.txt").writeText("charset,UTF-8\nvalidrect.right,0\nvalidrect.bottom,0\n")
        File(dir2, "balloons0.png").writeBytes(byteArrayOf())
        assertEquals(TextArea(16, 16, 340, 300), Balloon(dir2).textArea(340, 300))
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
    fun thirdCharacterUsesBalloonpImagesAndParts() {
        val dir = balloonDir(
            "balloons0.png", "balloonk1.png", "balloonp2def0.png", "balloonp2def1.png",
            "arrow0.png", "arrowp2def0.png", "markerp2def.png", "online0.png", "onlinep2def0.png",
        )
        File(dir, "balloonp2def1s.txt").writeText("font.height,20\r\nfont.color.r,255\r\nfont.color.g,0\r\nfont.color.b,0\r\n")
        val balloon = Balloon(dir)
        // \p[2] は balloonp2def の奇数番、\p[3] は無いので \1 用
        assertEquals(listOf("balloonp2def1.png"), balloon.images(2).map { it.file.name })
        assertEquals(listOf("balloonk1.png"), balloon.images(3).map { it.file.name })
        assertEquals("balloonp2def1.png", balloon.imageForId(2, 0)?.file?.name)
        // 矢印・印・オンラインの印も arrowp2def0.png などを先に
        assertEquals(File(dir, "arrowp2def0.png"), balloon.arrowImage(0, 2))
        assertEquals(File(dir, "arrow0.png"), balloon.arrowImage(0, 3))
        assertEquals(File(dir, "markerp2def.png"), balloon.markerImage(2))
        assertEquals(listOf(File(dir, "onlinep2def0.png")), balloon.onlineImages(2))
        // 画像ごとの balloonp2def1s.txt で文字の大きさと色が変わる
        val look = balloon.variant(File(dir, "balloonp2def1.png"))
        assertEquals(20, look.fontHeight)
        assertEquals(255, look.fontColor?.r)
        assertEquals(balloon.fontHeight, balloon.variant(File(dir, "balloonp2def0.png")).fontHeight)
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
        // 書いてなければ文字の範囲（右は wordwrappoint.x）の右下
        assertEquals((310 - 20 - 12) to (200 - 28 - 12), balloon.arrowPosition(1, 310, 200, 12, 12))
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

    @Test
    fun installsBalloonNamedInInstallTxt() {
        val repo = GhostRepository(File(tmp.root, "files/ghost"))
        // Emily/Phase4.5 と同じ書き方。.nar の balloon/ に直接入っているバルーンを emily4 として入れる
        val emily = nar(
            "install.txt" to "type,ghost\ndirectory,emily4\nballoon.directory,emily4\nballoon.source.directory,balloon\n",
            "ghost/master/descript.txt" to "name,Emily\nballoon,emily4\n",
            "balloon/descript.txt" to "name,Balloon for Emily\n",
            "balloon/balloons0.png" to "",
        )
        repo.installer().install(emily)
        assertEquals("Balloon for Emily", repo.balloons.find("emily4")?.name)
        // ゴーストの descript.txt の balloon 指定（ディレクトリ名）で見つかる
        assertEquals("Balloon for Emily", repo.find("emily4")!!.balloon(repo.balloons)?.name)

        // balloon.source.directory が無ければ、balloon.directory と同じ名前のフォルダから
        val same = nar(
            "install.txt" to "type,ghost\ndirectory,g2\nballoon.directory,b2\n",
            "ghost/master/descript.txt" to "name,テスト2\n",
            "b2/descript.txt" to "name,バルーン2\n",
            "b2/balloons0.png" to "",
        )
        repo.installer().install(same)
        assertEquals("バルーン2", repo.balloons.find("b2")?.name)
    }

    @Test
    fun bundledBalloonSourceMustStayInsideTheNar() {
        val root = File(tmp.root, "files/ghost")
        val repo = GhostRepository(root)
        // ゴースト置き場にある別のゴーストのフォルダを、バルーンとして写させない
        val other = File(root, "other").apply { mkdirs() }
        File(other, "descript.txt").writeText("name,よそのフォルダ\n")
        File(other, "balloons0.png").writeBytes(byteArrayOf())
        val evil = nar(
            "install.txt" to "type,ghost\ndirectory,g3\nballoon.directory,stolen\nballoon.source.directory,../other\n",
            "ghost/master/descript.txt" to "name,テスト3\n",
        )
        repo.installer().install(evil)
        assertNull(repo.balloons.find("stolen"))
        assertTrue(!File(repo.balloons.root, "stolen").exists())
    }

    @Test
    fun readsCommunicateBox() {
        val dir = balloonDir("balloons0.png", "balloonc0.png", "balloonc5.png")
        File(dir, "descript.txt").appendText(
            "\ncommunicatebox.x,22\ncommunicatebox.y,32\ncommunicatebox.width,296\ncommunicatebox.height,24\n" +
                "communicatebox.font.color.r,10\ncommunicatebox.font.color.g,20\ncommunicatebox.font.color.b,30\n",
        )
        // balloonc5s.txt は balloonc5.png だけの指定。descript.txt の値（1行の箱向け）は引き継がず、書いていなければ四辺から 8 ピクセル内側
        File(dir, "balloonc5s.txt").writeText("charset,UTF-8\ncommunicatebox.y,10\ncommunicatebox.height,120\n")
        val balloon = Balloon(dir)
        val c0 = balloon.communicateImage(multiLine = false)!!
        val c5 = balloon.communicateImage(multiLine = true)!!
        assertEquals("balloonc0.png", c0.name)
        assertEquals("balloonc5.png", c5.name)
        assertEquals(TextArea(22, 32, 22 + 296, 32 + 24), balloon.communicateBox(c0, 340, 64))
        assertEquals(TextArea(8, 10, 340 - 8, 10 + 120), balloon.communicateBox(c5, 340, 160))
        assertEquals(BalloonColor(10, 20, 30), balloon.communicateFontColor)
        assertNull(balloon.communicateBackground)
        // 画像からはみ出す指定は画像の中に収める
        assertEquals(TextArea(22, 32, 100, 50), balloon.communicateBox(c0, 100, 50))
    }

    @Test
    fun communicateBoxDefaultsToEightPixelsInside() {
        val balloon = Balloon(balloonDir("balloons0.png", "balloonc0.png"))
        val c0 = balloon.communicateImage(multiLine = true)!!
        // balloonc5.png が無ければ複数行の箱も balloonc0.png
        assertEquals("balloonc0.png", c0.name)
        assertEquals(TextArea(8, 8, 192, 42), balloon.communicateBox(c0, 200, 50))
        assertNull(Balloon(balloonDir2("balloons0.png")).communicateImage(multiLine = false))
    }

    private fun balloonDir2(vararg images: String): File {
        val dir = tmp.newFolder("plain")
        File(dir, "descript.txt").writeText(descript)
        for (name in images) File(dir, name).writeBytes(byteArrayOf())
        return dir
    }
}
