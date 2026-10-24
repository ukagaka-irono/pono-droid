package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 「共有」で渡すファイル（OnFileDrop2） */
class FileDropTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun safeNameKeepsFilesInsideTheDropFolder() {
        assertEquals("写真.jpg", FileDrop.safeName("写真.jpg", 1))
        // 区切り文字や「..」でフォルダの外を指させない
        assertEquals("_.._etc_passwd", FileDrop.safeName("/../etc/passwd", 1))
        assertEquals("a_b_c.txt", FileDrop.safeName("a/b\\c.txt", 1))
        assertEquals("file3", FileDrop.safeName("..", 3))
        assertEquals("file2", FileDrop.safeName(null, 2))
        assertEquals("tab_name.txt", FileDrop.safeName("tab\tname.txt", 1))
        // 同じ名前（大文字小文字の違いも）なら番号を付ける
        assertEquals("memo (2).txt", FileDrop.safeName("memo.txt", 2, setOf("MEMO.txt")))
        assertEquals("memo (3).txt", FileDrop.safeName("memo.txt", 3, setOf("memo.txt", "memo (2).txt")))
        // 長すぎる名前は縮める（拡張子は残す）
        val long = FileDrop.safeName("あ".repeat(300) + ".png", 1)
        assertTrue(long, long.length <= 100 && long.endsWith(".png"))
    }

    @Test
    fun dropFolderIsInsideTheGhost() {
        val ghost = tmp.newTestGhost()
        assertEquals(File(ghost.dir, "pono/drop"), FileDrop.dir(ghost))
    }

    @Test
    fun droppedFilesAreSentAsOnFileDrop2() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFileDrop2" to """\0ありがとう"""))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            c.onFileDrop(listOf("/g/pono/drop/a.txt", "/g/pono/drop/b.png"), listOf("text/plain", "image/png"))
            // パスと MIME タイプは 0x01 区切り、Reference1 は渡した先のキャラクター
            assertEquals(listOf("/g/pono/drop/a.txt\u0001/g/pono/drop/b.png", "0", "text/plain\u0001image/png"), shiori.awaitEvent("OnFileDrop2"))
            withTimeout(5000) { c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.Text && it.text == "ありがとう" } == true } }
            // 答えたので古いイベントは送らない
            delay(200)
            assertEquals(0, shiori.count("OnFileDropEx"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oldGhostsGetOnFileDropEx() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFileDropEx" to """\0ふるいほう"""))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            c.onFileDrop(listOf("/g/pono/drop/a.txt"), listOf("text/plain"))
            assertEquals(listOf("/g/pono/drop/a.txt", "0", "text/plain"), shiori.awaitEvent("OnFileDropEx"))
            withTimeout(5000) { c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.Text && it.text == "ふるいほう" } == true } }
        } finally {
            scope.cancel()
        }
    }
}
