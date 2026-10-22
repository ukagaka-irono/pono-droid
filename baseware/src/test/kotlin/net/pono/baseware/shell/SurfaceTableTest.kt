package net.pono.baseware.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceTableTest {
    private val text = """
        charset,UTF-8
        descript
        {
        version,1
        }

        surface0,surface2-4,!surface3
        {
        collision0,10,20,30,40,Head
        animation0.interval,sometimes
        animation0.pattern0,overlay,100,50,0,0
        animation0.pattern1,overlay,-1,50-100,0,0
        }

        surface10 {
        element0,overlay,body.png,0,0
        element1,overlay,face.png,12,34
        1interval,random,3
        1pattern0,101,5,overlay,1,2
        collisionex1,Bust,ellipse,0,0,10,20
        }
    """.trimIndent()

    @Test
    fun parsesNewFormat() {
        val table = SurfaceTable.parse(text)
        assertEquals(setOf(0, 2, 4, 10), table.definitions.keys)
        val s0 = table[0]!!
        assertEquals("Head", s0.hitTest(15, 25))
        assertNull(s0.hitTest(50, 50))
        val anim = s0.animations[0]!!
        assertEquals(IntervalKind.SOMETIMES, anim.interval.kind)
        assertEquals(PatternFrame("overlay", -1, 50, 100, 0, 0), anim.frames[1])
    }

    @Test
    fun parsesControlFrames() {
        val table = SurfaceTable.parse(
            """
            surface0
            {
            animation1.interval,always
            animation1.pattern0,start,3
            animation1.pattern1,alternativestart,[4.5]
            animation1.pattern2,alternativestop,(6,7)
            animation2.interval,bind
            animation2.pattern0,overlay,200,0,10,20
            }
            """.trimIndent(),
        )
        val frames = table[0]!!.animations[1]!!.frames
        assertEquals(listOf(3), frames[0].targets)
        assertEquals(listOf(4, 5), frames[1].targets)
        assertEquals(listOf(6, 7), frames[2].targets)
        assertEquals(true, frames.all { it.isControl })
        assertEquals(IntervalKind.BIND, table[0]!!.animations[2]!!.interval.kind)
    }

    @Test
    fun readsBindGroupsFromShellDescript() {
        val dir = java.nio.file.Files.createTempDirectory("shell").toFile()
        try {
            java.io.File(dir, "descript.txt").writeText(
                "charset,UTF-8\nsakura.bindgroup2.name,帽子,麦わら\nsakura.bindgroup2.default,1\nsakura.bindgroup3.name,帽子,ベレー\nkero.bindgroup5.name,服,セーター\n",
            )
            val shell = Shell(dir)
            assertEquals(
                listOf(BindGroup(2, "帽子", "麦わら", true), BindGroup(3, "帽子", "ベレー", false)),
                shell.bindGroups(0),
            )
            assertEquals(listOf(BindGroup(5, "服", "セーター", false)), shell.bindGroups(1))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parsesElementsAndOldFormat() {
        val s10 = SurfaceTable.parse(text)[10]!!
        assertEquals(SurfaceElement(1, "overlay", "face.png", 12, 34), s10.elements[1])
        val anim = s10.animations[1]!!
        assertEquals(AnimationInterval(IntervalKind.RANDOM, 3), anim.interval)
        assertEquals(PatternFrame("overlay", 101, 50, 50, 1, 2), anim.frames[0])
        assertEquals("Bust", s10.hitTest(5, 10))
    }
}
