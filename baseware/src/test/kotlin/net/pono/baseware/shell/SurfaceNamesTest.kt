package net.pono.baseware.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** surfaces.txt の名前の参照・--alpha・-2・旧形式の start・ファイルごとの descript・重ねる順と当たり判定の順 */
class SurfaceNamesTest {
    @Test
    fun namesStandForNumbers() {
        val table = SurfaceTable.parse(
            """
            surface0 {
            animation1.name,まばたき
            animation1.interval,never
            animation1.pattern0,overlay,100,50,0,0
            animation2.interval,runonce
            animation2.option,exclusive,(まばたき)
            animation2.pattern0,start,まばたき
            animation2.pattern1,alternativestart,[まばたき.3]
            animation2.pattern2,overlay,笑顔,50,0,0
            animation2.pattern3,overlay,定義,50,0,0
            animation2.pattern4,overlay,-2,0,0,0
            }
            surface5 { name,定義 }
            surface7 { }
            sakura.surface.alias {
            笑顔,[7]
            }
            """.trimIndent(),
        )
        val anim = table[0]!!.animations[2]!!
        assertEquals(setOf(1), anim.exclusiveOf)
        assertEquals(listOf(1), anim.frames[0].targets)
        assertEquals(listOf(1, 3), anim.frames[1].targets)
        assertEquals(7, anim.frames[2].surface)
        assertEquals(5, anim.frames[3].surface)
        assertEquals(-2, anim.frames[4].surface)
    }

    @Test
    fun alphaAndOldControlPatterns() {
        val table = SurfaceTable.parse(
            """
            surface0 {
            animation1.pattern0,overlay,100,50,0,0,--alpha,128
            animation1.pattern1,overlay,100,50,0,0
            animation1.pattern2,overlayscreen,100,50,0,0,--alpha,999
            3interval,always
            3pattern0,0,0,alternativestart,[1,2]
            3pattern1,100,5,overlay,0,0,--alpha,64
            }
            """.trimIndent(),
        )
        val a = table[0]!!.animations[1]!!.frames
        assertEquals(listOf(128, 255, 255), a.map { it.alpha })
        assertEquals("overlayscreen", a[2].method)
        val old = table[0]!!.animations[3]!!.frames
        assertEquals("alternativestart", old[0].method)
        assertEquals(listOf(1, 2), old[0].targets)
        assertEquals(64, old[1].alpha)
    }

    @Test
    fun descriptSortsOnlyItsOwnFile() {
        val table = SurfaceTable.parse(
            listOf(
                "descript { animation-sort,ascend\ncollision-sort,descend }\nsurface0 { collision0,0,0,10,10,A }",
                "surface1 { collision0,0,0,10,10,B }\nsurface.append0 { collision1,0,0,10,10,C }",
            ).joinToString("\n" + SurfaceTable.FILE_BREAK + "\n"),
        )
        assertEquals(SortOrder.ASCEND, table[0]!!.animationSort)
        assertEquals(SortOrder.DESCEND, table[0]!!.collisionSort)
        assertEquals(SortOrder.DESCEND, table[1]!!.animationSort)
        assertEquals(SortOrder.NONE, table[1]!!.collisionSort)
    }

    @Test
    fun backgroundCollisionsComeAfterTheBase() {
        val def = SurfaceTable.parse(
            """
            surface0 {
            collision0,0,0,10,10,Base
            animation1.option,background
            animation1.collision0,0,0,20,20,Back
            animation2.collision0,0,0,20,20,Front
            }
            """.trimIndent(),
        )[0]!!
        assertEquals("Base", def.hitTest(5, 5, listOf(1)))
        assertEquals("Back", def.hitTest(15, 15, listOf(1)))
        assertEquals("Front", def.hitTest(5, 5, listOf(1, 2)))
    }

    @Test
    fun bindFramesRememberTheirAnimation() {
        val def = SurfaceTable.parse(
            """
            surface0 {
            animation30.interval,bind
            animation30.pattern0,overlay,100,0,0,0
            animation20.interval,bind
            animation20.pattern0,overlay,101,0,0,0
            }
            """.trimIndent(),
        )[0]!!
        val frames = def.bindFrames(setOf(20, 30))
        assertTrue(frames.isNotEmpty())
        assertEquals(mapOf(100 to 30, 101 to 20), frames.associate { it.surface to it.layerOf })
    }
}
