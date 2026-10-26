package net.pono.baseware.balloon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 縦書き（vertical,1）の文字の範囲と、1文字ごとの出し方 */
class VerticalTextTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun verticalAreaUsesOriginAsTheFirstColumnsRightEdge() {
        val dir = tmp.newFolder("v")
        File(dir, "descript.txt").writeText(
            listOf(
                "charset,UTF-8", "vertical,1", "origin.x,-14", "origin.y,20", "validrect.left,10", "validrect.top,10",
                "validrect.right,-10", "validrect.bottom,-10", "wordwrappoint.x,50", "wordwrappoint.y,-30",
                "sstpmessage.xr,100", "sstpmessage.yb,-5", "number.x,-20", "number.yb,-8",
            ).joinToString("\r\n"),
        )
        val b = Balloon(dir)
        assertTrue(b.vertical)
        // 書き始めは右端から 14（1列目の右端）、上から 20。折り返しは wordwrappoint.y（wordwrappoint.x は見ない）
        assertEquals(TextArea(10, 20, 186, 70), b.textArea(200, 100))
        // 範囲の全体は validrect
        assertEquals(TextArea(10, 10, 190, 90), b.validArea(200, 100))
        // 印の文字の終わりは sstpmessage.yb、数の下端は number.yb
        assertNull(b.markerEnd(200))
        assertEquals(95, b.markerEndY(100))
        assertEquals(180 to 92, b.numberPosition(200, 100))
    }

    @Test
    fun glyphsForVerticalWriting() {
        assertEquals(VerticalGlyph("︑"), VerticalText.glyph('、'))
        assertEquals(VerticalGlyph("﹁"), VerticalText.glyph('「'))
        assertTrue(VerticalText.glyph('ー').rotate)
        assertTrue(VerticalText.glyph('A').rotate)
        assertTrue(VerticalText.glyph('っ').small)
        assertFalse(VerticalText.glyph('あ').rotate)
        // 絵文字（サロゲートペア）や結合文字も1文字
        assertEquals(listOf("あ", "😀", "が"), VerticalText.split("あ😀が"))
    }
}
