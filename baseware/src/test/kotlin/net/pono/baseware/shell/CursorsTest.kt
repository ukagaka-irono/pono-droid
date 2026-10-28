package net.pono.baseware.shell

import net.pono.baseware.text.Descript
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** surfaces.txt の cursor ブレス、descript.txt の mousecursor.*、.cur / .ani の読み込み */
class CursorsTest {
    private val none: (String) -> File? = { null }

    @Test
    fun readsCursorBracesPerCharacter() {
        val table = SurfaceTable.parse(
            """
            sakura.cursor
            {
            mouseup0,Head,system:hand
            mousedown0,Head,なでる.cur
            mousewheel3,Bust,system:cross
            }
            kero.cursor { mousehover0,Face,system:wait }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                CursorRule(CursorAction.UP, "Head", "system:hand"),
                CursorRule(CursorAction.DOWN, "Head", "なでる.cur"),
                CursorRule(CursorAction.WHEEL, "Bust", "system:cross"),
            ),
            table.cursors[0],
        )
        assertEquals(listOf(CursorRule(CursorAction.HOVER, "Face", "system:wait")), table.cursors[1])
    }

    @Test
    fun choosesWrittenCursorsThenSspDefaults() {
        val rules = listOf(CursorRule(CursorAction.UP, "Head", "system:hand"), CursorRule(CursorAction.DOWN, "Head", "なでる.cur"))
        val shellFile = File("なでる.cur")
        val ghost = Descript.parse("mousecursor.grip,grip.cur\r\n")
        val gripFile = File("grip.cur")
        fun pick(collision: String?, action: CursorAction) =
            Cursors.forCharacter(rules, collision, action, ghost, { if (it == "なでる.cur") shellFile else null }, { if (it == "grip.cur") gripFile else null })
        assertEquals(CursorChoice.System("hand"), pick("Head", CursorAction.UP))
        assertEquals(CursorChoice.FromFile(shellFile), pick("Head", CursorAction.DOWN))
        // 書いていなければ SSP の既定。Bust は hand と grip（grip はゴーストの mousecursor.grip のファイル）
        assertEquals(CursorChoice.System("finger"), pick("Face", CursorAction.UP))
        assertEquals(CursorChoice.System("hand"), pick("bust", CursorAction.UP))
        assertEquals(CursorChoice.FromFile(gripFile), pick("Bust", CursorAction.DOWN))
        assertEquals(CursorChoice.System("arrow"), pick("Head", CursorAction.WHEEL))
        assertEquals(CursorChoice.System("arrow"), pick(null, CursorAction.UP))
    }

    @Test
    fun balloonChoicesUseTheBalloonThenTheGhostCursor() {
        val balloon = Descript.parse("name,b\r\n")
        val ghost = Descript.parse("cursor,finger.cur\r\n")
        val finger = File("finger.cur")
        assertEquals(CursorChoice.FromFile(finger), Cursors.forBalloonChoice(listOf(balloon to none, ghost to { if (it == "finger.cur") finger else null })))
        // どちらにも無ければ矢印
        assertEquals(CursorChoice.System("arrow"), Cursors.forBalloonChoice(listOf(balloon to none, ghost to none)))
        assertEquals(CursorChoice.System("text"), Cursors.fromDescripts("text", listOf(balloon to none)))
    }

    /** 2×2 の 32 ビット .cur（指す点 1,0）。下の行から：青・透明 / 赤・緑 */
    private fun cur32(): ByteArray {
        val dib = ByteArrayOutputStream()
        fun le32(v: Int) = dib.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = dib.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        le32(40); le32(2); le32(4); le16(1); le16(32); le32(0); le32(0); le32(0); le32(0); le32(0); le32(0)
        // BGRA。下の行から
        dib.write(byteArrayOf(-1, 0, 0, -1, 0, 0, 0, 0))
        dib.write(byteArrayOf(0, 0, -1, -1, 0, -1, 0, -1))
        // AND マスク（使わない）
        repeat(8) { dib.write(0) }
        val data = dib.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 2, 0, 1, 0))
        out.write(byteArrayOf(2, 2, 0, 0, 1, 0, 0, 0))
        val size = data.size
        out.write(byteArrayOf(size.toByte(), (size shr 8).toByte(), 0, 0, 22, 0, 0, 0))
        out.write(data)
        return out.toByteArray()
    }

    @Test
    fun decodesCurAndTheFirstAniFrame() {
        val img = Cursors.decode(cur32())
        assertNotNull(img)
        img!!
        assertEquals(2, img.width)
        assertEquals(2, img.height)
        assertEquals(1, img.hotX)
        assertEquals(0, img.hotY)
        assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0), img.argb)
        // .ani（RIFF ACON の LIST fram の最初の icon）
        val icon = cur32()
        val fram = ByteArrayOutputStream().apply {
            write("fram".toByteArray())
            write("icon".toByteArray())
            write(byteArrayOf(icon.size.toByte(), (icon.size shr 8).toByte(), 0, 0))
            write(icon)
            if (icon.size % 2 == 1) write(0)
        }.toByteArray()
        val ani = ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            write(byteArrayOf(0, 0, 0, 0))
            write("ACON".toByteArray())
            write("LIST".toByteArray())
            write(byteArrayOf(fram.size.toByte(), (fram.size shr 8).toByte(), 0, 0))
            write(fram)
        }.toByteArray()
        assertArrayEquals(img.argb, Cursors.decode(ani)?.argb)
        // 壊れたものは読まない
        assertNull(Cursors.decode(byteArrayOf(0, 0, 2, 0, 9, 0)))
        assertNull(Cursors.decode(ByteArray(3)))
    }
}
