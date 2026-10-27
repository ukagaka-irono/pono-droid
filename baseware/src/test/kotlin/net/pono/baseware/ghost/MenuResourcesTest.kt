package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import net.pono.baseware.update.NetworkUpdater
import net.pono.baseware.update.UpdateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

/** メニューのリソース（項目名・popupmenu.*・色）、AI グラフ、使用率、選択肢のツールチップ、更新の途中のイベント */
class MenuResourcesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Server(val files: Map<String, ByteArray>) : HttpFetcher {
        override fun fetch(url: String, sink: OutputStream, maxBytes: Long): FetchResult {
            sink.write(files[url] ?: return FetchResult.HttpError(404))
            return FetchResult.Ok
        }
    }

    private fun manifest(vararg files: Pair<String, ByteArray>) =
        files.joinToString("") { (path, bytes) -> "$path\u0001${NetworkUpdater.md5(bytes)}\u0001size=${bytes.size}\u0001\r\n" }.toByteArray()

    private fun run(
        replies: Map<String, String>,
        fetcher: HttpFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
        prepare: (File) -> Unit = {},
        block: suspend (GhostController, ScriptedShiori) -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val root = tmp.newFolder("ghosts")
            for ((dir, name) in listOf("me" to "わたし", "next" to "つぎ")) {
                val g = File(root, dir)
                File(g, "ghost/master").mkdirs()
                File(g, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,$name\r\nshiori,yaya.dll\r\nhomeurl,https://up.test/\r\n")
                File(g, "shell/master").mkdirs()
                File(g, "shell/master/descript.txt").writeText("charset,UTF-8\r\nname,master\r\n")
            }
            prepare(File(root, "me"))
            val ghost = Ghost(File(root, "me"))
            val c = GhostController(
                ghost = ghost,
                shell = Shell(ghost.shellDir()!!),
                shioriFactory = { _, _ -> shiori },
                scope = scope,
                platform = RecordingPlatform(),
                repository = GhostRepository(root),
                player = ScriptPlayer(charWaitMs = 0),
                updateFetcher = fetcher,
            )
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GhostController.awaitChoice(id: String) = withTimeout(5000) {
        state.first { s -> s.characters.values.any { ch -> ch.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == id } == true } }
    }

    private fun GhostController.choices(): List<BalloonItem.ChoiceItem> =
        state.value.characters.values.flatMap { it.balloon.orEmpty().filterIsInstance<BalloonItem.ChoiceItem>() }

    private fun GhostController.text(scope: Int) =
        state.value.characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    @Test
    fun captionsAndColorsComeFromTheGhost() = run(
        mapOf(
            "ghostrootbutton.caption" to "おうち替え", "hidebutton.caption" to "かくれる", "updatebuttoncaption" to "こうしん",
            "updatebutton.caption" to "あたらしく", "menu.foreground.font.color.r" to "255", "menu.foreground.font.color.g" to "0",
            "menu.foreground.font.color.b" to "0", "menu.background.font.color.r" to "10",
        ),
    ) { c, _ ->
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:close")
        val titles = c.choices().associate { it.choice.id to it.choice.title }
        assertEquals("おうち替え", titles["pono:ghosts"])
        assertEquals("かくれる", titles["pono:hide"])
        // 新しい名前（updatebutton.caption）が古い名前より先
        assertEquals("あたらしく", titles["pono:update"])
        assertEquals("ゴーストを終了", titles["pono:close"])
        val looks = c.choices().first().looks
        assertNotNull(looks.cursor.font)
        // 背景の色は3つそろっていないので使わない
        assertNull(looks.cursorNotSelect.font)
    }

    @Test
    fun shortcutsVisibilityAndNewItems() = run(
        mapOf("updatebutton.caption" to "こうしん(&U)", "helpbutton.caption" to "A&&B", "sntpbutton.visible" to "0"),
    ) { c, _ ->
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:close")
        val titles = c.choices().associate { it.choice.id to it.choice.title }
        // & はショートカットキーの印なので消す（&& は & のまま）
        assertEquals("こうしん(U)", titles["pono:update"])
        assertEquals("A&B", titles["pono:help"])
        // *.visible が 0 なら出さない
        assertFalse("pono:sntp" in titles)
        assertEquals("○ サウンドを鳴らさない", titles["pono:quiet"])
        assertTrue(listOf("pono:scale", "pono:reloadshiori", "pono:settings", "pono:speech", "pono:dictation").all { it in titles })
        // 入り切りすると、印が替わったメニューを出し直す
        c.onChoice(Choice("サウンドを鳴らさない", "pono:quiet", listOf("0")))
        withTimeout(5000) { c.state.first { _ -> c.choices().any { it.choice.title == "● サウンドを鳴らさない" } } }
        // 大きさの下位メニュー（今の大きさに印）
        c.onChoice(Choice("キャラクターの大きさ", "pono:scale", listOf("0")))
        // 選択肢は順に足されるので、「その他」まで出るのを待つ
        withTimeout(5000) {
            c.state.first { _ -> c.choices().any { it.choice.title == "● 100%" } && c.choices().any { it.choice.id == "pono:scaleother" } }
        }
    }

    @Test
    fun leavePassiveModeFromTheMenu() = run(mapOf("OnPassive" to """\0\![enter,passivemode]\e""")) { c, _ ->
        withTimeout(5000) { c.state.first { it.booted } }
        c.raise("OnPassive", emptyList())
        withTimeout(5000) { c.state.first { it.passive } }
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:leavepassive")
        c.onChoice(Choice("制限モードを解除", "pono:leavepassive", emptyList()))
        withTimeout(5000) { c.state.first { !it.passive } }
    }

    @Test
    fun shortMenuAndHiddenMenu() = run(mapOf("kero.popupmenu.type" to "1", "sakura.popupmenu.visible" to "0")) { c, _ ->
        c.onMouseLongPress(1, 0, 0, null)
        c.awaitChoice("pono:close")
        val ids = c.choices().map { it.choice.id }
        assertTrue("pono:hide" in ids)
        assertFalse("pono:update" in ids)
        assertFalse("pono:middle" in ids)
        c.onChoice(Choice("閉じる", "pono:cancel", emptyList()))
        withTimeout(5000) { c.state.first { s -> s.characters.values.all { it.balloon.isNullOrEmpty() } } }
        c.onMouseLongPress(0, 0, 0, null)
        delay(300)
        assertTrue(c.choices().isEmpty())
    }

    @Test
    fun aiGraphShowsLabelsAndValues() = run(mapOf("getaistate" to "10,20\u0001好感度,元気\u00011,1\u0001100,50")) { c, _ ->
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:aistate")
        c.onChoice(Choice("AI グラフ", "pono:aistate", listOf("0")))
        c.awaitChoice("pono:menu")
        assertEquals("【AI グラフ】好感度: 10 / 100元気: 20 / 50", c.text(0))
    }

    @Test
    fun rateOfUseIsListed() = run(emptyMap()) { c, _ ->
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:rateofuse")
        c.onChoice(Choice("使用率", "pono:rateofuse", listOf("0")))
        c.awaitChoice("pono:menu")
        assertTrue(c.text(0).startsWith("【使用率】わたし: "))
    }

    @Test
    fun termsInTheMenu() = run(mapOf("termsbutton.caption" to "約束"), prepare = { File(it, "ghost/master/terms.txt").writeText("守ること") }) { c, _ ->
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:terms-open")
        assertEquals("約束", c.choices().first { it.choice.id == "pono:terms-open" }.choice.title)
    }

    @Test
    fun choiceTooltip() = run(mapOf("OnFirstBoot" to """\0\q[はい,yes,x]\e""", "tooltip" to "そうする")) { c, shiori ->
        c.awaitChoice("yes")
        c.onChoiceHover(Choice("はい", "yes", listOf("x")))
        assertEquals(listOf("はい", "yes", "x"), shiori.awaitEvent("tooltip"))
        withTimeout(5000) { c.state.first { it.choiceTooltip == (0 to "そうする") } }
        c.onChoiceHover(null)
        withTimeout(5000) { c.state.first { it.choiceTooltip == null } }
        // 指を離したあとに届いた答えは出さない
        c.onChoiceHover(Choice("はい", "yes", listOf("x")))
        c.onChoiceHover(null)
        delay(300)
        assertEquals(null, c.state.value.choiceTooltip)
    }

    @Test
    fun updateProgressEventsCountFromOneWithUseorigin1() {
        val a = "new a".toByteArray()
        val b = "new b".toByteArray()
        val server = Server(mapOf("https://up.test/updates2.dau" to manifest("ghost/master/a.txt" to a, "ghost/master/b.txt" to b), "https://up.test/ghost/master/a.txt" to a, "https://up.test/ghost/master/b.txt" to "wrong".toByteArray()))
        run(mapOf("useorigin1" to "1"), fetcher = server) { c, shiori ->
            c.networkUpdate()
            assertEquals("2", shiori.awaitEvent("OnUpdateReady")[0])
            shiori.awaitEvent("OnUpdateFailure")
            val downloads = synchronized(shiori.events) { shiori.events.filter { it.first == "OnUpdate.OnDownloadBegin" }.map { it.second.take(3) } }
            assertEquals(listOf(listOf("ghost/master/a.txt", "1", "2"), listOf("ghost/master/b.txt", "2", "2")), downloads)
            assertEquals(1, shiori.count("OnUpdate.OnMD5CompareComplete"))
            assertEquals("ghost/master/b.txt", shiori.awaitEvent("OnUpdate.OnMD5CompareFailure")[0])
            assertEquals(2, shiori.count("OnUpdate.OnMD5CompareBegin"))
        }
    }

    @Test
    fun updaterReportsProgress() = runTest {
        val dir = tmp.newFolder("g")
        val a = "a".toByteArray()
        val server = Server(mapOf("https://up.test/updates2.dau" to manifest("x.txt" to a), "https://up.test/x.txt" to a))
        val seen = mutableListOf<UpdateProgress>()
        NetworkUpdater(dir, server).update("https://up.test/", onProgress = { seen += it })
        assertEquals(listOf(UpdateProgress.Download("x.txt", 0, 1), UpdateProgress.Md5("x.txt", NetworkUpdater.md5(a), NetworkUpdater.md5(a))), seen)
    }

    @Test
    fun passiveAndInductionModesRestrictTheUser() = run(
        mapOf(
            "OnPassive" to """\0\![enter,passivemode]\e""",
            "OnInduction" to """\0\![leave,passivemode]\![enter,inductionmode]\e""",
        ),
    ) { c, shiori ->
        withTimeout(5000) { c.state.first { it.booted } }
        c.raise("OnPassive", emptyList())
        withTimeout(5000) { c.state.first { it.passive } }
        // メニューは強制解除と閉じるだけ
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:cancel")
        val ids = c.choices().map { it.choice.id }
        assertEquals(listOf("pono:leavepassive", "pono:cancel"), ids)
        c.onChoice(Choice("閉じる", "pono:cancel", emptyList()))
        // 隠す・終了・ファイルを渡すのは効かない
        c.minimize("user")
        c.close()
        c.onFileDrop(listOf("/x.txt"), listOf("text/plain"))
        delay(300)
        assertEquals(false, c.state.value.window.minimized)
        assertEquals(0, shiori.count("OnClose") + shiori.count("OnCloseAll") + shiori.count("OnFileDrop2"))
        // 誘導モードは隠す・終了を出す
        c.raise("OnInduction", emptyList())
        withTimeout(5000) { c.state.first { it.induction && !it.passive } }
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:cancel")
        assertEquals(listOf("pono:leavepassive", "pono:hide", "pono:close", "pono:cancel"), c.choices().map { it.choice.id })
        c.minimize("user")
        withTimeout(5000) { c.state.first { it.window.minimized } }
    }

}
