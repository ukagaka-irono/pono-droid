package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** タグのオプション（カテゴリごとの \![bind]、\![bind-noevent]、\![set,alignmentondesktop]） */
class TagOptionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun bindByCategoryNoEventAndOldAlignment() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        File(ghost.dir, "shell/master/descript.txt").writeText(
            "charset,UTF-8\r\nname,master\r\nsakura.bindgroup2.name,帽子,麦わら\r\nsakura.bindgroup2.default,1\r\nsakura.bindgroup3.name,帽子,ベレー\r\n",
        )
        val shiori = ScriptedShiori(
            mapOf(
                // パーツ名が空なら、付けるときは既定のパーツ
                "OnA" to """\0\![bind,帽子,,1]\e""",
                "OnB" to """\0\![bind-noevent,帽子,ベレー,1]\e""",
                // 外すときはカテゴリのパーツを全部
                "OnC" to """\0\![bind,帽子,,0]\![set,alignmentondesktop,bottom]\e""",
            ),
        )
        val scope = testScope()
        try {
            val c = testController(ghost, shiori, scope)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            c.raise("OnA", emptyList())
            assertEquals(listOf("0", "麦わら", "1", "帽子", "script"), shiori.awaitEvent("OnDressupChanged"))
            c.raise("OnB", emptyList())
            withTimeout(5000) { c.state.first { it.characters[0]?.binds == setOf(3) } }
            assertEquals(1, shiori.count("OnDressupChanged"))
            c.raise("OnC", emptyList())
            withTimeout(5000) { while (shiori.count("OnDressupChanged") < 2) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("0", "ベレー", "0", "帽子", "script"), shiori.awaitEvent("OnDressupChanged"))
            withTimeout(5000) { c.state.first { it.characters[0]?.binds.isNullOrEmpty() && it.characters[0]?.alignment == "bottom" } }
        } finally {
            scope.cancel()
        }
    }
}
