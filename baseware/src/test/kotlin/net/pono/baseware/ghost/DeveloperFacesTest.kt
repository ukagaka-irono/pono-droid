package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.shell.Shell
import net.pono.baseware.shell.SurfaceRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** 発言履歴の顔（icon.rect と face / head の当たり判定）と、開発用パレットの SHIORI のログ（log_path） */
class DeveloperFacesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun shell(surfaces: String, descript: String = ""): Shell {
        val dir = tmp.newFolder()
        File(dir, "descript.txt").writeText("charset,UTF-8\r\nname,master\r\n$descript")
        File(dir, "surfaces.txt").writeText("charset,UTF-8\r\n$surfaces")
        return Shell(dir)
    }

    @Test
    fun iconRectWinsAndBecomesASquare() {
        val s = shell("surface0\r\n{\r\nicon.rect,10,20,30,60\r\ncollision0,0,0,5,5,Face\r\n}\r\n")
        // 縦長の 20×40 は、中心のまま 40 四方に
        assertEquals(SurfaceRect(0, 20, 40, 60), s.faceRect(0, 100, 200))
    }

    @Test
    fun faceThenHeadCollisionWithMargin() {
        val s = shell("surface0\r\n{\r\ncollision0,40,40,80,80,Head\r\ncollision1,50,10,70,30,face\r\n}\r\nsurface1\r\n{\r\ncollision0,40,40,80,80,頭\r\n}\r\n")
        // face（20 四方）に 4 分の 1 ずつ余白を足して 30 四方
        assertEquals(SurfaceRect(45, 5, 75, 35), s.faceRect(0, 200, 200))
        // head しか無ければ head。絵の端からはみ出す分はずらす
        assertEquals(SurfaceRect(30, 30, 90, 90), s.faceRect(1, 200, 200))
        assertEquals(SurfaceRect(30, 0, 90, 60), s.faceRect(1, 100, 60))
        assertNull(s.faceRect(2, 200, 200))
    }

    @Test
    fun frameColourPerCharacter() {
        val s = shell("", "sakura.icon.frame.color.r,255\r\nchar2.icon.frame.color.g,128\r\nchar2.icon.frame.color.b,300\r\n")
        assertEquals(0xFF0000, s.iconFrameColor(0))
        assertNull(s.iconFrameColor(1))
        assertEquals(0x0080FF, s.iconFrameColor(2))
    }

    private class LogPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun openFile(file: File, mimeType: String): Boolean {
            opened += "${file.name}:$mimeType"
            return true
        }
    }

    @Test
    fun backlogRemembersTheFaceAndLogPathStaysInTheGhost() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        File(ghost.masterDir, "yaya.log").writeText("log")
        var logPath = "yaya.log"
        val inner = ScriptedShiori(mapOf("OnFirstBoot" to """\0\s[5]こんにちは\e"""))
        // log_path だけはテストで変える
        val shiori = object : net.pono.baseware.shiori.Shiori by inner {
            override fun request(request: ByteArray): ByteArray {
                val text = request.toString(Charsets.UTF_8)
                if ("\r\nID: log_path\r\n" in text) return "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\nValue: $logPath\r\n\r\n".toByteArray()
                return inner.request(request)
            }
        }
        val platform = LogPlatform()
        val scope = testScope()
        try {
            val c = testController(ghost, shiori, scope, platform)
            c.start()
            withTimeout(5000) { while (c.backlog.isEmpty()) delay(10) }
            assertEquals(5, c.backlog.single().surface)
            assertEquals(true, c.openShioriLog())
            assertEquals(listOf("yaya.log:text/plain"), platform.opened.toList())
            // ゴーストのフォルダの外や、無いファイルは開かない
            logPath = "../../../../etc/passwd"
            assertNull(c.openShioriLog())
            logPath = "none.log"
            assertNull(c.shioriLogFile())
            assertTrue(platform.opened.size == 1)
        } finally {
            scope.cancel()
        }
    }
}
