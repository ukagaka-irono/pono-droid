package net.pono.baseware.balloon

import net.pono.baseware.ghost.InputRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 画像ごとの上書き（balloons0s.txt）、座標の書き方、画像のパーツ、置き方・見え方の設定、リンクの影 */
class BalloonPartsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun balloon(descript: String, vararg files: Pair<String, String>): Balloon {
        val dir = tmp.newFolder("b${dirs++}")
        File(dir, "descript.txt").writeText("charset,UTF-8\r\nname,テスト\r\n" + descript.trimIndent().replace("\n", "\r\n") + "\r\n")
        for ((name, text) in files) File(dir, name).writeText(text)
        return Balloon(dir)
    }

    private var dirs = 0

    @Test
    fun negativeAndDoubleMinusCoordinates() {
        val b = balloon(
            """
            origin.x,-100
            origin.y,20
            validrect.left,-120
            validrect.top,--5
            sstpmessage.x,-50
            sstpmessage.y,--3
            onlinemarker.x,--2
            """,
        )
        // 負は右端・下端から、--数値 は本当の負の数（画像の中に収める）
        assertEquals(100, b.textArea(200, 100).left)
        assertEquals(80, b.validArea(200, 100).left)
        assertEquals(0, b.validArea(200, 100).top)
        assertEquals(150 to -3, b.markerPosition(200, 100))
        assertEquals(-2 to 0, b.onlinePosition(200, 100))
    }

    @Test
    fun imageOverridesWithItsOwnTxt() {
        val b = balloon(
            """
            origin.x,10
            font.height,12
            windowposition.x,center
            name,本当の名前
            """,
            "balloons0.png" to "", "balloons2.png" to "",
            "balloons2s.txt" to "charset,UTF-8\r\norigin.x,40\r\nfont.height,20\r\nname,上書きしない\r\n",
        )
        val small = b.variant(File(b.dir, "balloons0.png"))
        val big = b.variant(File(b.dir, "balloons2.png"))
        assertSame(b, small)
        assertEquals(10, small.textArea(200, 100).left)
        assertEquals(40, big.textArea(200, 100).left)
        assertEquals(20, big.fontHeight)
        assertEquals("テスト", big.name)
        assertEquals("center", big.windowPosition.anchor)
    }

    @Test
    fun partImagesFollowFilenamesAndScopes() {
        val b = balloon(
            """
            arrow.filename,yaji
            clickwaitmarker.x,-30
            """,
            "balloons0.png" to "", "yaji0.png" to "", "yaji1.png" to "", "yajik1.png" to "",
            "marker.png" to "", "markerk.png" to "", "clickwait.png" to "", "sstp.png" to "",
        )
        assertEquals("yaji0.png", b.arrowImage(0, 1)?.name)
        assertEquals("yajik1.png", b.arrowImage(1, 1)?.name)
        assertEquals("yaji1.png", b.arrowImage(1, 0)?.name)
        assertEquals("marker.png", b.markerImage(0)?.name)
        assertEquals("markerk.png", b.markerImage(1)?.name)
        assertEquals("clickwait.png", b.clickWaitImage(2)?.name)
        // sstp_new が無ければ sstp
        assertEquals("sstp.png", b.sstpMarkerImage(0)?.name)
        // クリック待ちの印の y は、下向きの矢印と同じ
        val (x, y) = b.clickWaitPosition(200, 100, 10, 10)
        assertEquals(170, x)
        assertEquals(b.arrowPosition(1, 200, 100, 10, 10).second, y)
    }

    @Test
    fun windowAndAppearanceSettings() {
        val b = balloon(
            """
            windowposition.x,-20
            windowposition.y,8
            windowposition.limit,0
            dpi,144
            use_self_alpha,full
            paint_transparent_region_black,0
            overlay_outside_balloon,1
            use_input_alpha,1
            recommended.ghost,さくら
            disable.font.height,9
            sstpmessage.xr,-10
            """,
        )
        assertEquals(BalloonWindowPosition(-20, 8, null), b.windowPosition)
        assertFalse(b.keepOnScreen)
        assertEquals(96f / 144, b.dpiFactor)
        assertEquals(BalloonSelfAlpha.FULL, b.selfAlpha)
        assertEquals(false, b.paintTransparentBlack)
        assertTrue(b.overlayOutside)
        assertTrue(b.useInputAlpha)
        assertEquals("さくら", b.recommendedGhost)
        assertEquals(9, b.disabledFontHeight)
        assertEquals(190, b.markerEnd(200))
        // 何も書いていなければ SSP の既定
        val plain = balloon("")
        assertEquals(BalloonSelfAlpha.OFF, plain.selfAlpha)
        assertTrue(plain.keepOnScreen)
        assertNull(plain.paintTransparentBlack)
        assertEquals(BalloonWindowPosition(0, 0, null), plain.windowPosition)
    }

    @Test
    fun linkShadowsAndBlend() {
        val b = balloon(
            """
            font.shadowstyle,outline
            cursor.font.shadowcolor.r,255
            cursor.font.shadowcolor.g,0
            cursor.font.shadowcolor.b,0
            cursor.blendmethod,notmaskpen
            anchor.notselect.font.shadowstyle,offset
            """,
        )
        val cursor = b.linkLook("cursor")
        assertEquals(0xFF0000, cursor.shadow)
        // 影の付け方が無ければ font.shadowstyle
        assertEquals(true, cursor.shadowOutline)
        assertEquals("notmaskpen", cursor.blend)
        // cursor.notselect の blendmethod は cursor のもの
        assertEquals("notmaskpen", b.linkLook("cursor.notselect").blend)
        assertEquals(false, b.linkLook("anchor.notselect").shadowOutline)
        assertNull(b.linkLook("anchor").blend)
    }

    @Test
    fun placeholderOption() {
        val r = InputRequest.fromOpenTag("inputbox", listOf("OnName", "0", "", "--placeholder=名前を入れてね"), 1)!!
        assertEquals("名前を入れてね", r.placeholder)
        val t = InputRequest.fromOpenTag("textareabox", listOf("OnNote", "0", "", "--placeholder=1行目\u00012行目"), 2)!!
        assertEquals("1行目\n2行目", t.placeholder)
        assertNull(InputRequest.fromOpenTag("inputbox", listOf("OnName"), 3)!!.placeholder)
    }
}
