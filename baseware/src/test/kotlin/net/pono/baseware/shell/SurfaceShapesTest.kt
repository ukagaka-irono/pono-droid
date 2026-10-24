package net.pono.baseware.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** collisionex の形、当たり判定の順、アニメーションの option・名前・当たり判定、組み合わせた interval、point、tooltips */
class SurfaceShapesTest {
    @Test
    fun collisionShapes() {
        val s = SurfaceTable.parse(
            """
            surface0 {
            collisionex0,Eye,ellipse,0,0,100,50
            collisionex1,Dot,circle,200,200,10
            collisionex2,Tri,polygon,300,0,400,0,350,100
            collisionex3,Box,rect,500,500,510,510
            }
            """.trimIndent(),
        )[0]!!
        assertEquals("Eye", s.hitTest(50, 25))
        // 楕円の角（外接矩形の中だが楕円の外）は当たらない
        assertNull(s.hitTest(2, 2))
        assertEquals("Dot", s.hitTest(205, 205))
        assertNull(s.hitTest(209, 209))
        assertEquals("Tri", s.hitTest(350, 50))
        assertNull(s.hitTest(305, 90))
        assertEquals("Box", s.hitTest(505, 505))
        assertEquals(CollisionShape.POLYGON, s.collisions[2]!!.shape)
    }

    @Test
    fun firstWrittenCollisionIsOnTopUnlessSorted() {
        val text = """
            surface0 {
            collision5,0,0,100,100,Big
            collision1,10,10,20,20,Small
            }
        """.trimIndent()
        assertEquals("Big", SurfaceTable.parse(text)[0]!!.hitTest(15, 15))
        assertEquals("Small", SurfaceTable.parse("descript\n{\ncollision-sort,ascend\n}\n$text")[0]!!.hitTest(15, 15))
        assertEquals("Big", SurfaceTable.parse("descript\n{\ncollision-sort,descend\n}\n$text")[0]!!.hitTest(15, 15))
    }

    @Test
    fun animationCollisionsOnlyWhileShown() {
        val s = SurfaceTable.parse(
            """
            surface0 {
            collision0,0,0,100,100,Body
            animation1.interval,never
            animation1.pattern0,overlay,100,0,0,0
            animation1.collision0,0,0,50,50,Hand
            animation2.interval,never
            animation2.collisionex0,Hat,rect,0,0,30,30
            }
            """.trimIndent(),
        )[0]!!
        assertEquals("Body", s.hitTest(10, 10))
        assertEquals("Hand", s.hitTest(10, 10, active = listOf(1)))
        // 番号の大きいアニメーションが手前
        assertEquals("Hat", s.hitTest(10, 10, active = listOf(1, 2)))
        assertEquals("Body", s.hitTest(80, 80, active = listOf(1, 2)))
    }

    @Test
    fun optionsNamesAndCombinedIntervals() {
        val s = SurfaceTable.parse(
            """
            surface0 {
            animation1.interval,bind+runonce+random,5
            animation1.option,exclusive,(2,3)
            animation1.name,まばたき
            animation2.interval,starttalk
            animation2.option,background
            animation3.interval,endtalk
            animation3.option,shared-index
            animation4.interval,bind
            4option,exclusive
            }
            """.trimIndent(),
        )[0]!!
        val a1 = s.animations[1]!!
        assertEquals(setOf(IntervalKind.BIND, IntervalKind.RUNONCE, IntervalKind.RANDOM), a1.interval.kinds)
        assertEquals(5, a1.interval.arg)
        assertEquals(IntervalKind.RUNONCE, a1.interval.kind)
        assertFalse(a1.interval.isStaticBind)
        assertTrue(a1.exclusive)
        assertEquals(setOf(2, 3), a1.exclusiveOf)
        assertEquals(1, s.animationId("まばたき"))
        assertEquals(7, s.animationId("7"))
        assertNull(s.animationId("ない"))
        assertTrue(s.animations[2]!!.background)
        assertEquals(listOf(2), s.startTalkAnimations.map { it.id })
        assertEquals(listOf(3), s.endTalkAnimations.map { it.id })
        assertTrue(s.animations[3]!!.sharedIndex)
        assertTrue(s.animations[4]!!.interval.isStaticBind)
        assertTrue(s.animations[4]!!.exclusive)
    }

    @Test
    fun bindPlusSomethingIsNotStatic() {
        val s = SurfaceTable.parse(
            """
            surface0 {
            animation1.interval,bind
            animation1.pattern0,overlay,100,0,0,0
            animation1.pattern1,base,101,0,0,0
            animation2.interval,bind+always
            animation2.pattern0,overlay,200,50,0,0
            }
            """.trimIndent(),
        )[0]!!
        // bind だけのものは重ねたまま（2コマ目の base は overlay にする）。bind+always は動くので入れない
        assertEquals(listOf("overlay" to 100, "overlay" to 101), s.bindFrames(setOf(1, 2)).map { it.method to it.surface })
    }

    @Test
    fun pointsScalingFramesAndTooltips() {
        val table = SurfaceTable.parse(
            """
            surface3 {
            point.basepos.x,40
            point.basepos.y,180
            point.kinoko.centerx,10
            animation0.pattern0,scaling,-1,100,92.5,110
            }
            sakura.tooltips
            {
            Head,なでて
            Bust,怒ります。
            }
            kero.tooltips { Head,いたい }
            """.trimIndent(),
        )
        val s = table[3]!!
        assertEquals(40, s.baseposX)
        assertEquals(180, s.baseposY)
        assertEquals(10, s.kinokoX)
        val f = s.animations[0]!!.frames.single()
        assertEquals(92.5f, f.scaleX)
        assertEquals(110f, f.scaleY)
        assertEquals(mapOf("Head" to "なでて", "Bust" to "怒ります。"), table.tooltips[0])
        assertEquals("いたい", table.tooltips[1]?.get("Head"))
    }
}
