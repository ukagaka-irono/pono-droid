package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.SstpServer
import net.pono.baseware.shell.BindOption
import net.pono.baseware.shell.DressupMenuItem
import net.pono.baseware.shell.SelfAlpha
import net.pono.baseware.shell.Shell
import net.pono.baseware.shiori.ShioriMethod
import net.pono.baseware.shiori.ShioriRequest
import net.pono.baseware.shiori.ShioriResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 着せ替えのオプション・メニューの並び、シェルの絵の決まり、SHIORI の文字コード、SSTP を受けるかどうか、既定のシェル */
class DescriptMoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var runs = 0

    private fun run(
        ghostDescript: String,
        shellDescript: String,
        replies: Map<String, String> = emptyMap(),
        block: suspend (GhostController, ScriptedShiori) -> Unit,
    ) = runBlocking<Unit> {
        val ghost = tmp.newTestGhost("ghost${runs++}", extraDescript = ghostDescript.replace("\n", "\r\n") + "\r\n")
        File(ghost.shellDir()!!, "descript.txt").writeText("charset,UTF-8\r\nname,master\r\n" + shellDescript.replace("\n", "\r\n") + "\r\n")
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val c = testController(ghost, shiori, scope)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            block(c, shiori)
        } finally {
            scope.cancel()
        }
    }

    private val dress = """
        sakura.bindgroup1.name,帽子,ベレー,beret.png
        sakura.bindgroup2.name,帽子,麦わら
        sakura.bindgroup3.name,腕,時計
        sakura.bindgroup4.name,腕,腕輪
        sakura.bindgroup5.name,服,制服
        sakura.bindgroup5.addid,6
        sakura.bindgroup6.name,服,リボン
        sakura.bindoption0.group,帽子,mustselect
        sakura.bindoption1.group,腕,multiple
        sakura.menuitem0,5
        sakura.menuitem1,-
        sakura.menuitemex2,小物,3
        sakura.menuitemex3,小物,4
    """.trimIndent()

    @Test
    fun shellReadsDressupDescript() {
        val dir = tmp.newFolder("shell")
        File(dir, "descript.txt").writeText("charset,UTF-8\r\n" + dress.replace("\n", "\r\n"))
        val shell = Shell(dir)
        val groups = shell.bindGroups(0).associateBy { it.id }
        assertEquals("beret.png", groups[1]!!.thumbnail)
        assertEquals(setOf(6), groups[5]!!.addIds)
        assertEquals(mapOf("帽子" to BindOption(mustSelect = true), "腕" to BindOption(multiple = true)), shell.bindOptions(0))
        assertEquals(
            listOf(DressupMenuItem(5), DressupMenuItem(null), DressupMenuItem(3, "小物"), DressupMenuItem(4, "小物")),
            shell.dressupMenuItems(0),
        )
    }

    @Test
    fun dressupOptionsAndAddIds() = run(
        "", dress,
        mapOf(
            "OnWatch" to """\0\![bind,腕,時計,1]\![bind,腕,腕輪,1]\e""",
            "OnHatOff" to """\0\![bind,帽子,ベレー,0]\e""",
            "OnDress" to """\0\![bind,服,制服,1]\e""",
            "OnUndress" to """\0\![bind,服,制服,0]\e""",
        ),
    ) { c, _ ->
        // mustselect の帽子は、default が無くても最初のものを付けておく
        assertEquals(setOf(1), c.state.value.characters[0]!!.binds)
        c.raise("OnWatch", emptyList())
        withTimeout(5000) { c.state.first { it.characters[0]!!.binds.containsAll(setOf(3, 4)) } }
        // 最後の1つの帽子は外せない
        c.raise("OnHatOff", emptyList())
        c.raise("OnDress", emptyList())
        withTimeout(5000) { c.state.first { 5 in it.characters[0]!!.binds } }
        assertEquals(setOf(1, 3, 4, 5, 6), c.state.value.characters[0]!!.binds)
        // addid のリボンは、制服を外すと一緒に外れる
        c.raise("OnUndress", emptyList())
        withTimeout(5000) { c.state.first { 5 !in it.characters[0]!!.binds } }
        assertEquals(setOf(1, 3, 4), c.state.value.characters[0]!!.binds)
    }

    @Test
    fun shellImageSettings() {
        fun shell(text: String): Shell {
            val dir = tmp.newFolder("s${runs++}")
            File(dir, "descript.txt").writeText("charset,UTF-8\r\n$text")
            return Shell(dir)
        }
        assertEquals(SelfAlpha.OFF, shell("").selfAlpha)
        assertEquals(SelfAlpha.ON, shell("seriko.use_self_alpha,true").selfAlpha)
        assertEquals(SelfAlpha.ON, shell("seriko.use_self_alpha,1").selfAlpha)
        assertEquals(SelfAlpha.FULL, shell("seriko.use_self_alpha,full").selfAlpha)
        assertEquals(null, shell("").paintTransparentBlack)
        assertEquals(false, shell("seriko.paint_transparent_region_black,0").paintTransparentBlack)
        assertEquals(0.5f, shell("seriko.dpi,192").dpiFactor)
        assertEquals(1f, shell("").dpiFactor)
    }

    @Test
    fun escapeUnknownCharacters() {
        val req = ShioriRequest(ShioriMethod.GET, "OnTest", listOf("😀と漢字"))
        val text = String(req.encode(net.pono.baseware.text.TextCharsets.CP932, "pono-droid", escapeUnknown = true), net.pono.baseware.text.TextCharsets.CP932)
        assertTrue(text.contains("Reference0: ?escape!unicode[0x1F600]と漢字"))
        val res = ShioriResponse.parse("SHIORI/3.0 200 OK\r\nValue: ?escape!unicode[0x1F600]\r\n\r\n".toByteArray(), Charsets.UTF_8, unescape = true)
        assertEquals("😀", res!!.value)
        // forceencoding なら Charset ヘッダを見ない
        val forced = ShioriResponse.parse("SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\nValue: 漢字\r\n\r\n".toByteArray(net.pono.baseware.text.TextCharsets.CP932), net.pono.baseware.text.TextCharsets.CP932, force = true)
        assertEquals("漢字", forced!!.value)
    }

    @Test
    fun sstpAllowSettings() = run("sstp.allowunspecifiedsend,0\nsstp.allowcommunicate,0", "", mapOf("OnHello" to """\0やあ\e""")) { c, _ ->
        fun req(text: String) = SstpServer.parse(text.replace("\n", "\r\n").toByteArray())!!
        assertEquals(420, c.handleSstp(req("SEND SSTP/1.4\nSender: app\nEvent: OnHello\nCharset: UTF-8\n\n")).code)
        assertEquals(200, c.handleSstp(req("SEND SSTP/1.4\nSender: app\nEvent: OnHello\nReceiverGhostName: テスト\nCharset: UTF-8\n\n")).code)
        assertEquals(420, c.handleSstp(req("COMMUNICATE SSTP/1.1\nSender: app\nReceiverGhostName: テスト\nSentence: こんにちは\nCharset: UTF-8\n\n")).code)
    }

    @Test
    fun defaultShellDirectory() {
        val ghost = tmp.newTestGhost("shells", extraDescript = "seriko.defaultsurfacedirectoryname,second\r\n")
        File(ghost.dir, "shell/second").mkdirs()
        assertEquals("second", ghost.defaultShellDir()!!.name)
    }

    @Test
    fun thumbnailsOfGhostShellAndBalloon() {
        val ghost = tmp.newTestGhost("thumbs")
        assertNull(ghost.thumbnailFile())
        // ゴーストのフォルダの直下より ghost/master を先に。大文字小文字は問わない
        File(ghost.dir, "thumbnail.png").writeBytes(byteArrayOf(1))
        assertEquals(File(ghost.dir, "thumbnail.png"), ghost.thumbnailFile())
        File(ghost.masterDir, "Thumbnail.PNG").writeBytes(byteArrayOf(1))
        assertEquals(File(ghost.masterDir, "Thumbnail.PNG"), ghost.thumbnailFile())

        val shellDir = File(ghost.dir, "shell/master")
        assertNull(net.pono.baseware.shell.Shell.thumbnailOf(shellDir))
        File(shellDir, "THUMBNAIL.png").writeBytes(byteArrayOf(1))
        assertEquals(File(shellDir, "THUMBNAIL.png"), net.pono.baseware.shell.Shell.thumbnailOf(shellDir))

        val balloonDir = tmp.newFolder("balloon")
        File(balloonDir, "descript.txt").writeText("charset,UTF-8\r\nname,b\r\n")
        File(balloonDir, "thumbnail.png").writeBytes(byteArrayOf(1))
        assertEquals(File(balloonDir, "thumbnail.png"), net.pono.baseware.balloon.Balloon(balloonDir).thumbnailFile())
    }

    @Test
    fun scriptTagsAndVariablesAreDroppedFromNames() {
        assertEquals("さくら", Ghost.plainText("\\0\\s[10]さくら%username"))
        assertEquals("a\\b 100%", Ghost.plainText("a\\\\b 100\\%"))
        assertEquals("名前です", Ghost.plainText("名前\\_w[100]%property[x]です\\![raise,OnX]"))
        assertEquals("nice", Ghost.plainText("\\nnice"))
        val dir = tmp.newFolder("g")
        File(dir, "ghost/master").mkdirs()
        File(dir, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,\\![raise,OnX]ぽの\r\nsakura.name,%usernameさん\r\ncraftman,pono\r\n")
        val ghost = Ghost(dir)
        assertEquals("ぽの", ghost.name)
        assertEquals("さん", ghost.sakuraName)
        assertEquals("pono", ghost.craftman)
    }

}
