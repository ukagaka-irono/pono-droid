package net.pono.baseware.sakura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** \_b のオプション（--option=・--clipping=・--scaling=）と、描く大きさ */
class BalloonImageOptionsTest {
    private fun size(scaling: String, w: Int, h: Int) =
        BalloonImageOptions.parse(listOf("--scaling=$scaling")).first.drawSize(w, h)

    @Test
    fun readsOptions() {
        val (o, opaque) = BalloonImageOptions.parse(
            listOf("--option=use_self_alpha", "--clipping=100 30 150 90", "--option=foreground", "--option=fixed", "--source=0"),
        )
        assertFalse(opaque)
        assertTrue(o.selfAlpha && o.fixed && o.foreground)
        assertEquals(BalloonImageOptions.Clip(100, 30, 150, 90), o.clip)
        // 古い書き方の opaque、background は既定（foreground より優先）
        val (old, oldOpaque) = BalloonImageOptions.parse(listOf("opaque", "--option=foreground,background"))
        assertTrue(oldOpaque)
        assertFalse(old.foreground)
        // 読めない切り抜きは無視
        assertNull(BalloonImageOptions.parse(listOf("--clipping=1 2 3")).first.clip)
    }

    @Test
    fun scalingSizes() {
        assertEquals(BalloonImageOptions.DrawSize(150, 100, false, false), size("150 0", 300, 200))
        assertEquals(BalloonImageOptions.DrawSize(150, 100, false, false), size("50% 50%", 300, 200))
        assertEquals(BalloonImageOptions.DrawSize(64, 32, true, false), size("-64px 32", 300, 200))
        assertEquals(BalloonImageOptions.DrawSize(300, 225, false, false), size("300 long", 400, 300))
        assertEquals(BalloonImageOptions.DrawSize(400, 300, false, false), size("300 short", 400, 300))
        // max は縮めるだけ、min は大きくするだけ。long / short を省けば long
        assertEquals(BalloonImageOptions.DrawSize(200, 100, false, false), size("300 longmax", 200, 100))
        assertEquals(BalloonImageOptions.DrawSize(300, 150, false, false), size("300 max", 600, 300))
        assertEquals(BalloonImageOptions.DrawSize(600, 300, false, false), size("300 min", 600, 300))
        assertEquals(BalloonImageOptions.DrawSize(300, 225, true, true), size("-300 long", 400, 300))
        // 1～4096 に収める
        assertEquals(BalloonImageOptions.DrawSize(4096, 4096, false, false), size("99999 99999", 10, 10))
        // % は長辺・短辺の書き方では使えない
        assertNull(BalloonImageOptions.parse(listOf("--scaling=50% long")).first.scaling)
    }
}
