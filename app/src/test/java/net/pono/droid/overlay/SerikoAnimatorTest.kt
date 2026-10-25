package net.pono.droid.overlay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.pono.baseware.shell.SurfaceTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SERIKO のウェイト（コマを描くまでの待ち時間）、-1 と -2、shared-index で always のアニメーションの続きから動かすこと */
@OptIn(ExperimentalCoroutinesApi::class)
class SerikoAnimatorTest {
    private fun TestScope.animator() = SerikoAnimator(backgroundScope)

    @Test
    fun waitComesBeforeTheFrameAndMinusOneEnds() = runTest {
        val def = SurfaceTable.parse(
            """
            surface0 {
            animation1.interval,never
            animation1.pattern0,overlay,100,200,0,0
            animation1.pattern1,overlay,-1,100,0,0
            animation1.pattern2,overlay,101,100,0,0
            }
            """.trimIndent(),
        )[0]!!
        val a = animator()
        a.show(def)
        a.start(1)
        advanceTimeBy(150)
        assertNull(a.layers.value[1])
        advanceTimeBy(100)
        assertEquals(100, a.layers.value[1]?.surface)
        // -1 で止まり、その後のコマは出さない
        advanceTimeBy(500)
        assertNull(a.layers.value[1])
        assertFalse(1 in a.playing.value)
    }

    @Test
    fun minusTwoStopsTheOthers() = runTest {
        val def = SurfaceTable.parse(
            """
            surface0 {
            animation1.interval,always
            animation1.pattern0,overlay,100,50,0,0
            animation1.pattern1,overlay,101,1000,0,0
            animation2.interval,never
            animation2.pattern0,overlay,200,100,0,0
            animation2.pattern1,overlay,-2,0,0,0
            animation2.pattern2,overlay,201,100,0,0
            }
            """.trimIndent(),
        )[0]!!
        val a = animator()
        a.show(def)
        advanceTimeBy(60)
        assertEquals(100, a.layers.value[1]?.surface)
        a.start(2)
        advanceTimeBy(300)
        // 2 は続き、1 は止まってコマも消える
        assertFalse(1 in a.layers.value)
        assertEquals(201, a.layers.value[2]?.surface)
        // 止めた always の 1 は、start するまで動かない
        advanceTimeBy(2000)
        assertFalse(1 in a.playing.value)
        a.start(1)
        advanceTimeBy(100)
        assertTrue(1 in a.playing.value)
    }

    @Test
    fun sharedIndexCarriesAnAlwaysAnimation() = runTest {
        val table = SurfaceTable.parse(
            """
            surface0,1 {
            animation1.interval,always
            animation1.option,shared-index
            animation1.pattern0,overlay,100,100,0,0
            animation1.pattern1,overlay,101,100,0,0
            animation1.pattern2,overlay,102,100,0,0
            }
            """.trimIndent(),
        )
        val a = animator()
        a.show(table[0])
        advanceTimeBy(250)
        assertEquals(101, a.layers.value[1]?.surface)
        a.show(table[1])
        runCurrent()
        // 替えた先でも、3 コマ目の続きから
        advanceTimeBy(110)
        assertEquals(102, a.layers.value[1]?.surface)
    }
}
