package net.pono.baseware.sakura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** \_l[x,y] の座標の書き方（ukadoc の例） */
class BalloonCoordTest {
    @Test
    fun parses() {
        assertEquals(BalloonCoord(30f, BalloonCoord.Unit.PX), BalloonCoord.parse("30"))
        assertEquals(BalloonCoord(5f, BalloonCoord.Unit.EM), BalloonCoord.parse("5em"))
        assertEquals(BalloonCoord(-1650f, BalloonCoord.Unit.PERCENT, relative = true), BalloonCoord.parse("@-1650%"))
        assertEquals(BalloonCoord(-100f, BalloonCoord.Unit.PX, relative = true), BalloonCoord.parse("@-100"))
        assertEquals(BalloonCoord(1.5f, BalloonCoord.Unit.LH), BalloonCoord.parse("1.5lh"))
        assertEquals(BalloonCoord.Unit.CENTER, BalloonCoord.parse("centerx")?.unit)
        assertNull(BalloonCoord.parse(""))
        assertNull(BalloonCoord.parse(null))
        assertNull(BalloonCoord.parse("abc"))
    }

    @Test
    fun resolves() {
        // em = 10、行 = 14、画像 1px = 2、今の位置 = 50、真ん中 = 80
        fun r(s: String) = BalloonCoord.parse(s)!!.resolve(10f, 14f, 2f, 50f, 80f)
        assertEquals(60f, r("30"))
        assertEquals(50f, r("5em"))
        assertEquals(42f, r("3lh"))
        assertEquals(35f, r("@-150%"))
        assertEquals(80f, r("centery"))
    }
}
