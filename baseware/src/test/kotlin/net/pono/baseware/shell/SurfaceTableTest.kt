package net.pono.baseware.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun bareNumbersAfterSurfaceContinueTheList() {
        // SSP と同じく "surface.append0,6,7" は 0・6・7、"surface1,3-4" は 1・3・4。
        // surface.append は、すでにある（画像がある・先に書いた）サーフェスにだけ足す（6 は画像が無いので足さない）
        val table = SurfaceTable.parse(
            """
            surface1,3-4,!4 { element0,base,a.png,0,0 }
            surface.append0,6,7,3{
            animation1.interval,never
            }
            """.trimIndent(),
            existing = setOf(0, 7),
        )
        assertEquals(setOf(0, 1, 3, 7), table.definitions.keys)
        assertEquals(IntervalKind.NEVER, table[7]!!.animations[1]!!.interval.kind)
        assertEquals(IntervalKind.NEVER, table[3]!!.animations[1]!!.interval.kind)
    }

    @Test
    fun shellReadsEverySurfacesFile() {
        val dir = kotlin.io.path.createTempDirectory("shell").toFile()
        try {
            java.io.File(dir, "surfaces.txt").writeText("charset,UTF-8\r\nsurface0{\r\nelement0,base,a.png,0,0\r\n}\r\n")
            // 分けて書かれた定義（BOM 付き UTF-8 と Shift_JIS）
            java.io.File(dir, "surfaces_clock.txt").writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "charset,UTF-8\r\nsurface2000{\r\nelement0,base,add_clock/hand.png,0,0\r\n}\r\n".toByteArray())
            java.io.File(dir, "surfaces_misc.txt").writeBytes("charset,Shift_JIS\r\n// 時計\r\nsurface.append0{\r\nanimation5.interval,never\r\nanimation5.pattern0,overlay,2000,0,10,20\r\n}\r\n".toByteArray(net.pono.baseware.text.TextCharsets.CP932))
            val shell = Shell(dir)
            assertEquals(setOf(0, 2000), shell.surfaces.definitions.keys)
            assertEquals(2000, shell.surfaces[0]!!.animations[5]!!.frames.single().surface)
            assertEquals("add_clock/hand.png", shell.surfaces[2000]!!.elements[0]!!.file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun survivesHostileNumbersAndRanges() {
        val hostile = """
            surface99999999999 { element0,overlay,a.png,0,0 }
            surface0-2000000000 { collision0,0,0,1,1,X }
            surface5 {
            element99999999999,overlay,a.png,0,0
            animation99999999999.interval,always
            1pattern99999999999,0,0,overlay,0,0
            animation1.pattern0,alternativestart,[99999999999.2]
            animation1.pattern99999999999,overlay,0,0,0,0
            }
        """.trimIndent()
        val table = SurfaceTable.parse(hostile)
        // 桁あふれの番号は飛ばし、巨大な範囲は無視する（落ちない・メモリを食いつぶさない）
        assertEquals(setOf(5), table.definitions.keys)
        val s5 = table[5]!!
        assertEquals(emptySet<Int>(), s5.elements.keys)
        assertEquals(listOf(1), s5.animations.keys.toList())
        assertEquals(listOf(2), s5.animations[1]!!.patterns[0]!!.targets)

        // 1つのサーフェスの部品の数にも上限がある
        val many = "surface0 {\n" + (0 until SurfaceDefinition.MAX_PARTS + 100).joinToString("\n") { "element$it,overlay,a.png,0,0" } + "\n}"
        assertEquals(SurfaceDefinition.MAX_PARTS, SurfaceTable.parse(many)[0]!!.elements.size)
    }

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

    @Test
    fun resolvesSurfaceAliasesAndNames() {
        val table = SurfaceTable.parse(
            """
            sakura.surface.alias
            {
            素,[0]
            照れ,[1,101,201]
            35,[55]
            }
            kero.surface.alias
            {
            300,[600]
            }
            char2.surface.alias { 笑顔,[205] }

            surface0 { element0,base,a.png,0,0 }
            surface77
            {
            name,ないしょ
            element0,base,b.png,0,0
            }
            """.trimIndent(),
        )
        assertEquals(0, table.resolve(0, "素"))
        // 候補が複数なら、その中から選ぶ
        repeat(20) { assertTrue(table.resolve(0, "照れ") in setOf(1, 101, 201)) }
        // 番号にも別名を付けられる（Emily/Phase4.5 の 35 → 55）
        assertEquals(55, table.resolve(0, "35"))
        // 別名はキャラクターごと
        assertEquals(35, table.resolve(1, "35"))
        assertEquals(600, table.resolve(1, "300"))
        assertEquals(205, table.resolve(2, "笑顔"))
        // surfaces.txt の name でも呼べる
        assertEquals(77, table.resolve(0, "ないしょ"))
        assertEquals(-1, table.resolve(0, "-1"))
        assertNull(table.resolve(0, "知らない"))
        // 別名のブロックはサーフェスとして読まない
        assertEquals(setOf(0, 77), table.definitions.keys)
    }

    @Test
    fun aliasAcceptsDotSeparators() {
        val table = SurfaceTable.parse("sakura.surface.alias\n{\n驚き,[2.12]\n}\n")
        assertTrue(table.resolve(0, "驚き") in setOf(2, 12))
    }

    @Test
    fun shellReadsAliasTxt() {
        val dir = kotlin.io.path.createTempDirectory("shell").toFile()
        try {
            java.io.File(dir, "descript.txt").writeText("charset,UTF-8\nname,master\n")
            java.io.File(dir, "surfaces.txt").writeText("charset,UTF-8\nsurface0 { element0,base,a.png,0,0 }\n")
            // 古いシェルは別名を alias.txt に書く
            java.io.File(dir, "alias.txt").writeText("charset,UTF-8\nsakura.surface.alias\n{\n照れ,[1]\n}\n")
            assertEquals(1, Shell(dir).surfaces.resolve(0, "照れ"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun talkAnimationsPlayEveryNCharacters() {
        val def = SurfaceTable.parse(
            """
            surface0 {
            animation1.interval,talk,4
            animation1.pattern0,overlay,10,50,0,0
            animation2.interval,talk,1
            animation2.pattern0,overlay,20,50,0,0
            animation3.interval,yen-e
            animation3.pattern0,overlay,30,50,0,0
            animation4.interval,sometimes
            animation4.pattern0,overlay,40,50,0,0
            }
            """.trimIndent(),
        )[0]!!
        // 1 文字ごとのものは毎回、4 文字ごとのものは 4 の倍数をまたいだときだけ
        assertEquals(listOf(2), def.talkAnimations(0, 1).map { it.id })
        assertEquals(listOf(1, 2), def.talkAnimations(3, 4).map { it.id })
        assertEquals(listOf(2), def.talkAnimations(4, 7).map { it.id })
        // \_q などで一度にたくさん出ても1回だけ
        assertEquals(listOf(1, 2), def.talkAnimations(0, 20).map { it.id })
        // 増えていなければ何も動かさない
        assertEquals(emptyList<Int>(), def.talkAnimations(5, 5).map { it.id })
        assertEquals(listOf(3), def.yenEAnimations.map { it.id })
    }

    @Test
    fun insertPutsAnotherDressUpBetweenParts() {
        // 10 の「後ろ髪 → (11 を差し込む) → 前髪」。11 は 10 の中に出し、自分の番号の位置には出さない
        val table = SurfaceTable.parse(
            """
            surface0 {
            animation10.interval,bind
            animation10.pattern0,overlay,100,0,0,0
            animation10.pattern1,insert,11
            animation10.pattern2,overlay,102,0,0,0
            animation11.interval,bind
            animation11.pattern0,overlay,110,0,0,0
            animation12.interval,bind
            animation12.pattern0,overlay,120,0,0,0
            animation12.pattern1,parallelstart,[1.2]
            }
            """.trimIndent(),
        )
        val def = table[0]!!
        assertEquals(listOf(100, 110, 102, 120), def.bindFrames(setOf(10, 11, 12)).map { it.surface })
        // 差し込む方を身に着けていなければ、その場所には何も出さない
        assertEquals(listOf(100, 102), def.bindFrames(setOf(10)).map { it.surface })
        // 差し込み先を外していれば、差し込まれる方も出ない（SSP と同じ）
        assertEquals(listOf<Int>(), def.bindFrames(setOf(11)).map { it.surface })
        assertEquals(listOf(1, 2), def.animations[12]!!.frames[1].targets)
    }
}
