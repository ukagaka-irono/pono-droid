package net.pono.baseware.plugin

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.sakura.Choice
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.GhostHub
import net.pono.baseware.ghost.GhostRepository
import net.pono.baseware.ghost.GhostState
import net.pono.baseware.ghost.NarInstaller
import net.pono.baseware.ghost.RecordingPlatform
import net.pono.baseware.ghost.ScriptedShiori
import net.pono.baseware.ghost.newTestGhost
import net.pono.baseware.ghost.testScope
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** プラグイン（PLUGIN/2.0）。偽のエンジンで、読み込み・\![raiseplugin]・答えのイベントとスクリプト・メニュー・選択肢・プロパティ */
class PluginHostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 届いた要求（GET か NOTIFY・ID・Sender・Reference）を覚え、[replies] の答えを返す偽のプラグイン */
    private class FakePlugin(val replies: (id: String) -> Map<String, String> = { emptyMap() }) : Shiori {
        data class Request(val notify: Boolean, val id: String, val sender: String?, val references: List<String>, val charset: String)

        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

        override fun load(dir: File) = true

        override fun request(request: ByteArray): ByteArray {
            val head = String(request, Charsets.ISO_8859_1)
            val charset = Regex("""Charset: (\S+)""").find(head)?.groupValues?.get(1) ?: "Shift_JIS"
            val lines = String(request, charset(if (charset == "Shift_JIS") "windows-31j" else charset)).split("\r\n")
            val headers = lines.drop(1).mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val id = headers["ID"].orEmpty()
            requests += Request(
                lines[0].startsWith("NOTIFY"), id, headers["Sender"],
                generateSequence(0) { it + 1 }.map { headers["Reference$it"] }.takeWhile { it != null }.filterNotNull().toList(), charset,
            )
            val extra = (if (id == "version") mapOf("Value" to "1.0") else replies(id)).entries.joinToString("") { (k, v) -> "$k: $v\r\n" }
            val status = if (extra.isEmpty()) "204 No Content" else "200 OK"
            return "PLUGIN/2.0 $status\r\nCharset: UTF-8\r\n$extra\r\n".toByteArray()
        }

        override fun unload() {}

        fun count(id: String) = synchronized(requests) { requests.count { it.id == id } }

        suspend fun await(id: String): Request = withTimeout(5000) {
            while (count(id) == 0) delay(10)
            synchronized(requests) { requests.last { it.id == id } }
        }
    }

    private fun pluginDir(root: File, dir: String, name: String, extra: String = ""): File = File(root, dir).apply {
        mkdirs()
        File(this, "descript.txt").writeText("charset,UTF-8\r\nname,$name\r\nid,1234-ABCD-$dir\r\nfilename,yaya.dll\r\ntype,plugin\r\n$extra")
        File(this, "yaya.dll").writeText("")
    }

    private class Setup(val c: GhostController, val shiori: ScriptedShiori, val plugin: FakePlugin, val host: PluginHost, val hub: GhostHub)

    private fun run(
        ghostReplies: Map<String, String>,
        pluginReplies: (String) -> Map<String, String> = { emptyMap() },
        descriptExtra: String = "",
        block: suspend Setup.() -> Unit,
    ) = runBlocking<Unit> {
        val scope = testScope()
        try {
            val repository = GhostRepository(File(tmp.newFolder("data"), "ghosts"))
            val pluginRoot = File(repository.root.parentFile, "plugin")
            pluginDir(pluginRoot, "testplugin", "テストプラグイン", descriptExtra)
            val fake = FakePlugin(pluginReplies)
            val hub = GhostHub()
            val host = PluginHost(PluginRepository(pluginRoot), hub, { name, _ -> if (name == "yaya.dll") fake else null }, scope, repository)
            host.start().join()
            val ghost = tmp.newTestGhost()
            val shiori = ScriptedShiori(ghostReplies)
            val c = GhostController(
                ghost = ghost, shell = Shell(ghost.shellDir()!!), shioriFactory = { _, _ -> shiori }, scope = scope,
                platform = RecordingPlatform(), player = ScriptPlayer(charWaitMs = 0), hub = hub, plugins = host,
            )
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            Setup(c, shiori, fake, host, hub).block()
        } finally {
            scope.cancel()
        }
    }

    private fun GhostState.text(scope: Int) = characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    private suspend fun GhostController.awaitText(text: String) = withTimeout(5000) { state.first { text in it.text(0) } }

    private suspend fun Setup.awaitChoice(id: String) = withTimeout(5000) {
        c.state.first { s -> s.characters.values.any { ch -> ch.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == id } == true } }
    }

    @Test
    fun descriptAndProtocol() {
        val dir = pluginDir(tmp.root, "p", "ダミー", "otherghosttalk,before\r\nsecondchangeinterval,0\r\ncraftmanurl,javascript:x\r\n")
        val p = Plugin(dir)
        assertTrue(p.isValid)
        assertTrue(p.matches("ダミー") && p.matches("1234-abcd-p") && p.matches(dir.absolutePath + "/"))
        assertEquals(Plugin.OtherGhostTalk.BEFORE, p.otherGhostTalk)
        assertEquals(0, p.secondChangeInterval)
        assertEquals(null, p.craftmanUrl)
        // id の無いものは使わない
        File(dir, "descript.txt").writeText("name,x\r\nfilename,yaya.dll\r\n")
        assertFalse(Plugin(dir).isValid)

        val req = String(PluginProtocol.request(true, "OnTest", listOf("a\r\nb"), Charsets.UTF_8, "ゴースト"), Charsets.UTF_8)
        assertEquals("NOTIFY PLUGIN/2.0\r\nID: OnTest\r\nCharset: UTF-8\r\nSender: ゴースト\r\nReference0: a\u0001b\r\n\r\n", req)
        val r = PluginProtocol.parse("PLUGIN/2.0 200 OK\r\nCharset: UTF-8\r\nTarget: __SYSTEM_ALL_GHOST__\r\nEvent: OnX\r\nEventOption: notify\r\nReference0: 1\r\nScript: \\0やあ\r\nScriptOption: nobreak,notranslate\r\n\r\n".toByteArray(), Charsets.UTF_8)!!
        assertEquals(PluginHost.ALL_GHOSTS, r.target)
        assertTrue(r.eventNotify)
        assertEquals(listOf("1"), r.references)
        assertEquals(setOf("nobreak", "notranslate"), r.scriptOptions)
        assertEquals("\\0やあ", r.script)
    }

    @Test
    fun loadsWithVersionAndInstallInfo() = run(emptyMap()) {
        // 読み込みの直後に version（Shift_JIS）、答えの Charset: UTF-8 で以後は UTF-8
        val version = plugin.await("version")
        assertFalse(version.notify)
        assertEquals("Shift_JIS", version.charset)
        val installed = plugin.await("installedplugin")
        assertTrue(installed.notify)
        assertEquals("UTF-8", installed.charset)
        assertEquals(listOf("テストプラグイン\u00011234-ABCD-testplugin"), installed.references)
        plugin.await("pluginpathlist")
        // ゴーストが起動したら OnGhostBoot、ゴーストには installedplugin
        val boot = plugin.await("OnGhostBoot")
        assertEquals("テスト", boot.references[1])
        assertEquals(listOf("テストプラグイン\u00011234-ABCD-testplugin"), shiori.awaitEvent("installedplugin"))
        // OnSecondChange が毎秒届く
        plugin.await("OnSecondChange")
    }

    @Test
    fun raisePluginPlaysTheDefaultScript() = run(
        mapOf("OnTest" to "\\![raiseplugin,テストプラグイン,OnHello,a,b]\\e"),
        { id -> if (id == "OnHello") mapOf("Script" to "\\0プラグインです\\e") else emptyMap() },
    ) {
        c.raise("OnTest", emptyList())
        val r = plugin.await("OnHello")
        assertEquals("テスト", r.sender)
        assertEquals(listOf("a", "b"), r.references)
        c.awaitText("プラグインです")
    }

    @Test
    fun eventGoesToTheGhostBeforeTheScript() = run(
        mapOf("OnTest" to "\\![raiseplugin,1234-ABCD-testplugin,OnHello]\\e", "OnFromPlugin" to "\\0ゴーストの答え\\e"),
        { id -> if (id == "OnHello") mapOf("Event" to "OnFromPlugin", "Reference0" to "x", "Script" to "\\0使わない\\e") else emptyMap() },
    ) {
        c.raise("OnTest", emptyList())
        assertEquals(listOf("x"), shiori.awaitEvent("OnFromPlugin"))
        c.awaitText("ゴーストの答え")
        assertFalse("使わない" in c.state.value.text(0))
    }

    @Test
    fun missingPluginRaisesFailure() = run(mapOf("OnTest" to "\\![raiseplugin,ない,OnHello,r]\\![notifyplugin,テストプラグイン,OnQuiet]\\e")) {
        c.raise("OnTest", emptyList())
        assertEquals(listOf("notfound", "ない", "OnHello", "r"), shiori.awaitEvent("OnRaisePluginFailure"))
        assertTrue(plugin.await("OnQuiet").notify)
    }

    @Test
    fun menuExecAndChoiceGoBackToThePlugin() = run(
        emptyMap(),
        { id ->
            when (id) {
                "OnMenuExec" -> mapOf("Script" to "\\0どうする？\\n\\q[はい,yes]\\e")
                "OnChoiceSelectEx" -> mapOf("Script" to "\\0はいですね\\e")
                else -> emptyMap()
            }
        },
    ) {
        // 長押しのメニューの「プラグイン」から選ぶ
        c.onMouseLongPress(0, 0, 0, null)
        awaitChoice("pono:plugins")
        c.onChoice(Choice("プラグイン", "pono:plugins", listOf("0")))
        awaitChoice("pono:plugin")
        c.onChoice(Choice("テストプラグイン", "pono:plugin", listOf("testplugin")))
        assertEquals("テスト", plugin.await("OnMenuExec").references[1])
        val s = withTimeout(5000) { c.state.first { st -> st.characters[0]?.balloon.orEmpty().any { it is BalloonItem.ChoiceItem } } }
        c.onChoice(s.characters[0]!!.balloon!!.filterIsInstance<BalloonItem.ChoiceItem>().single().choice)
        assertEquals(listOf("はい", "yes"), plugin.await("OnChoiceSelectEx").references)
        c.awaitText("はいですね")
        // 同じ選択肢をゴーストにも
        shiori.awaitEvent("OnChoiceSelectEx")
    }

    @Test
    fun pluginListProperties() = run(emptyMap(), { id -> if (id == "property.get") mapOf("Value" to "42") else emptyMap() }) {
        assertEquals("1", c.property("pluginlist.count"))
        assertEquals("テストプラグイン", c.property("pluginlist.index(0).name"))
        assertEquals("1234-ABCD-testplugin", c.property("pluginlist(テストプラグイン).id"))
        assertTrue(c.setProperty("pluginlist(テストプラグイン).ext.volume", "5"))
        assertEquals(listOf("volume", "5"), plugin.await("property.set").references)
    }

    @Test
    fun installsPluginNar() {
        val root = tmp.newFolder("ghosts")
        val nar = File(tmp.root, "p.nar")
        ZipOutputStream(FileOutputStream(nar)).use { z ->
            for ((name, text) in listOf("install.txt" to "type,plugin\r\ndirectory,myplug\r\nname,マイプラグ\r\n", "descript.txt" to "name,マイプラグ\r\nid,X-1\r\nfilename,yaya.dll\r\n", "system\\" to "", "system\\a.dic" to "dic")) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        val r = NarInstaller(root, File(root.parentFile, "balloon")).install(nar)
        assertEquals(NarInstaller.Type.PLUGIN, r.type)
        assertEquals(File(root.parentFile, "plugin/myplug").canonicalFile, r.dir.canonicalFile)
        assertTrue(Plugin(r.dir).isValid)
        // \\ で終わるフォルダの項目（古いアーカイバ）はフォルダとして扱い、その中のファイルも入る
        assertEquals("dic", File(r.dir, "system/a.dic").readText())
    }
}
