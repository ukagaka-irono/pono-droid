package net.pono.baseware.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** surfacetable.txt と、それで分けたサーフェステストの一覧 */
class SurfaceNamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val table = """
        charset,UTF-8
        version,1
        option,DisableNoDefineSurfaces

        //==========パーツ==========
        group,__disabled
        {
        	19,__parts
        }
        group,クー
        {
        	scope,1
        	10,
        	11,刮目した
        }
        group,[空]平日
        {
        	scope,0
        	0,素
        	1,__parts
        	5,笑
        }
    """.trimIndent()

    private fun shell(withTable: Boolean): Shell {
        val dir = tmp.newFolder()
        for (id in listOf(0, 1, 3, 5, 10, 11, 19, 50)) File(dir, "surface$id.png").writeBytes(byteArrayOf())
        File(dir, "descript.txt").writeText("charset,UTF-8\r\nname,てすと\r\n")
        // surface3 だけ surfaces.txt に定義がある
        File(dir, "surfaces.txt").writeText("charset,UTF-8\r\nsurface3\r\n{\r\ncollision0,0,0,10,10,Head\r\n}\r\n")
        if (withTable) File(dir, "surfacetable.txt").writeText(table)
        return Shell(dir)
    }

    @Test
    fun parsesGroupsScopesAndOptions() {
        val t = SurfaceNameTable.parse(table)
        assertEquals(1, t.version)
        assertTrue(t.disableNoDefineSurfaces)
        assertEquals(listOf("__disabled", "クー", "[空]平日"), t.groups.map { it.name })
        assertTrue(t.groups[0].disabled)
        assertEquals(listOf(null, 1, 0), t.groups.map { it.scope })
        assertEquals(listOf(SurfaceNameTable.Entry(10, null), SurfaceNameTable.Entry(11, "刮目した")), t.groups[1].entries)
    }

    @Test
    fun groupsTheSurfaceTestAndPutsTheChosenCharacterFirst() {
        val groups = shell(withTable = true).surfaceTestGroups(0)
        assertEquals(listOf("[空]平日", "クー", "そのほか"), groups.map { it.name })
        // __parts と名前を省いたものは名前なし。グループに無いものは標準の名前
        assertEquals(listOf(SurfaceTestItem(0, "素"), SurfaceTestItem(1, null), SurfaceTestItem(5, "笑")), groups[0].items)
        assertEquals(listOf(SurfaceTestItem(10, null), SurfaceTestItem(11, "刮目した")), groups[1].items)
        // __disabled の 19 と、定義も名前も無い 50（DisableNoDefineSurfaces）は出さない。3 は surfaces.txt に定義がある
        assertEquals(listOf(SurfaceTestItem(3, "不安")), groups[2].items)
        assertEquals(listOf("クー", "[空]平日", "そのほか"), shell(withTable = true).surfaceTestGroups(1).map { it.name })
    }

    @Test
    fun groupsWithoutANameStillGetAHeading() {
        val dir = tmp.newFolder()
        for (id in listOf(0, 10)) File(dir, "surface$id.png").writeBytes(byteArrayOf())
        File(dir, "surfacetable.txt").writeText("0,素\ngroup,\n{\n10,相方\n}\n")
        assertEquals(listOf("（グループなし）", "（グループなし）"), Shell(dir).surfaceTestGroups(0).map { it.name })
    }

    @Test
    fun withoutTheTableEverySurfaceIsListedWithStandardNames() {
        val s = shell(withTable = false)
        assertNull(s.surfaceNames)
        val groups = s.surfaceTestGroups(0)
        assertEquals(1, groups.size)
        assertNull(groups[0].name)
        assertEquals(listOf(0, 1, 3, 5, 10, 11, 19, 50), groups[0].items.map { it.id })
        assertEquals("刮目", groups[0].items.first { it.id == 11 }.name)
        assertNull(groups[0].items.first { it.id == 50 }.name)
    }
}
