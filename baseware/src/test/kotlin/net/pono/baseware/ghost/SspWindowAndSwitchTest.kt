package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.MoveCommand
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** SSP 互換の続き: \_s・\_b・\![set,scaling/alpha/position]・\![move]・ゴーストの切り替え */
class SspWindowAndSwitchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun GhostController.awaitIdle() = withTimeout(5000) { state.first { !it.talking } }

    /** スクリプトが [done] の状態まで進み、話し終わるまで待つ */
    private suspend fun GhostController.await(done: (GhostState) -> Boolean) = withTimeout(5000) { state.first { done(it) && !it.talking } }

    private suspend fun GhostController.awaitChoices(id: String) = withTimeout(5000) {
        state.first { s -> s.waitingChoice && s.characters[0]?.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == id } == true }
    }

    private fun GhostController.text(scope: Int) =
        state.value.characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    /** 切り替えを頼まれたら覚えておくプラットフォーム */
    private class SwitchPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val switches: MutableList<Pair<String, PreviousGhost>> = Collections.synchronizedList(mutableListOf())

        override fun switchGhost(dirName: String, previous: PreviousGhost) {
            switches += dirName to previous
        }
    }

    private fun ghostIn(root: File, dir: String, name: String): Ghost {
        val g = File(root, dir)
        File(g, "ghost/master").mkdirs()
        File(g, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,$name\r\nsakura.name,${name}さくら\r\nshiori,yaya.dll\r\n")
        File(g, "shell/master").mkdirs()
        File(g, "shell/master/descript.txt").writeText("charset,UTF-8\r\nname,master\r\n")
        return Ghost(g)
    }

    private fun controller(
        ghost: Ghost,
        shiori: ScriptedShiori,
        scope: kotlinx.coroutines.CoroutineScope,
        platform: GhostPlatform,
        repository: GhostRepository? = null,
        previous: PreviousGhost? = null,
    ) = GhostController(
        ghost = ghost,
        shell = Shell(ghost.shellDir()!!),
        shioriFactory = { _, _ -> shiori },
        scope = scope,
        platform = platform,
        repository = repository,
        player = ScriptPlayer(charWaitMs = 0),
        updateFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
        previous = previous,
    )

    @Test
    fun syncedTextGoesToBothCharacters() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\_s一緒\_s\1あと"""))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            c.await { it.characters[1]?.balloon?.any { i -> i is BalloonItem.Text && "あと" in i.text } == true }
            assertEquals("一緒", c.text(0))
            assertEquals("一緒あと", c.text(1))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun balloonImagesArePlacedOrInline() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\_b[img/a.png,10,20]\_b[img/a.png,inline,opaque]文\_b[../../../outside.png,inline]"""))
        val platform = RecordingPlatform()
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            File(ghost.masterDir, "img").mkdirs()
            File(ghost.masterDir, "img/a.png").writeBytes(byteArrayOf(1))
            File(ghost.dir.parentFile, "outside.png").writeBytes(byteArrayOf(1))
            val c = testController(ghost, shiori, scope, platform)
            c.start()
            c.await { it.characters[0]?.balloon?.any { i -> i is BalloonItem.Text && i.text == "文" } == true }
            val images = c.state.value.characters[0]!!.balloon!!.filterIsInstance<BalloonItem.Image>()
            val path = File(ghost.masterDir, "img/a.png").path
            // ゴーストのフォルダの外の画像は出さない
            assertEquals(listOf(BalloonItem.Image(path, 10, 20, false), BalloonItem.Image(path, null, null, true)), images)
            val items = c.state.value.characters[0]!!.balloon!!
            assertEquals(listOf(BalloonLayout.PlacedImage(path, 10, 20, false)), BalloonLayout.placedImages(items))
            assertEquals(
                listOf(BalloonLayout.Run.Image(path, true), BalloonLayout.Run.Text("文")),
                BalloonLayout.lines(items).single().columns.single(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scalingAlphaAndPosition() = runBlocking<Unit> {
        val shiori = ScriptedShiori(
            mapOf(
                "OnFirstBoot" to """\0\![set,scaling,-50,200]\![set,alpha,30]\1\![set,scaling,1000]\![set,alpha,-1]\![set,position,5,6]\p[2]\![set,position,7,8,0]""",
                "OnBoot" to """\0\![reset,position]""",
            ),
        )
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            c.await { it.characters[0]?.pinned != null }
            val s = c.state.value.characters
            assertEquals(-0.5f, s[0]!!.scaleX)
            assertEquals(2f, s[0]!!.scaleY)
            assertEquals(0.3f, s[0]!!.alpha, 0.0001f)
            // 大きすぎる倍率は抑え、負の不透明度は「そのまま」
            assertEquals(4f, s[1]!!.scaleX)
            assertEquals(1f, s[1]!!.alpha)
            assertEquals(5 to 6, s[1]!!.pinned)
            // スコープを指定すれば、話しているキャラクターとは別のキャラクターを固定できる
            assertEquals(7 to 8, s[0]!!.pinned)

            c.raise("OnBoot", emptyList())
            withTimeout(5000) { c.state.first { st -> st.characters.values.all { it.pinned == null } } }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun moveTagsParseBothSyntaxes() = runBlocking {
        val shiori = ScriptedShiori(
            mapOf("OnFirstBoot" to """\0\![moveasync,--X=10,--y=fix,--Time=99999,--base=1,--move-offset=right.bottom]\1\![move,5,-6,0,me,center.center]"""),
        )
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            c.await { it.characters[1]?.move != null }
            val s = c.state.value.characters
            assertEquals(
                MoveCommand(10, null, MoveCommand.MAX_TIME_MS, MoveCommand.Base.Scope(1), moveOffset = MoveCommand.Anchor(MoveCommand.Edge.END, MoveCommand.Edge.END)),
                s[0]!!.move!!.second,
            )
            assertEquals(
                MoveCommand(5, -6, 0, MoveCommand.Base.Me, baseOffset = MoveCommand.Anchor(MoveCommand.Edge.CENTER, MoveCommand.Edge.CENTER)),
                s[1]!!.move!!.second,
            )
            // 後から出したものほど番号が大きい（表示する側はまだ動かしていない番号だけ動かす）
            assertTrue(s[1]!!.move!!.first > s[0]!!.move!!.first)
            // 一緒に動いているほかのゴーストの \p[ID] を基準にする（本体側の名前/ID。名前の / は最後のもので分ける）
            assertEquals(MoveCommand.Base.Ghost("さくら", 0), MoveCommand.parse(listOf("--X=1", "--base=さくら/0"))!!.base)
            assertEquals(MoveCommand.Base.Ghost("A/B", 1), MoveCommand.parse(listOf("1", "2", "0", "A/B/1"))!!.base)
            assertNull(MoveCommand.parse(listOf("--X=1", "--base=さくら/x")))
            assertNull(MoveCommand.parse(listOf("--X=1", "--base=/0")))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scriptChangesGhostAfterFinishingTalk() = runBlocking {
        val root = tmp.newFolder("ghost")
        val a = ghostIn(root, "a", "ゴーストA")
        ghostIn(root, "b", "ゴーストB")
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0さようなら\![change,ghost,ゴーストB]"""))
        val platform = SwitchPlatform()
        val scope = testScope()
        try {
            val c = controller(a, shiori, scope, platform, GhostRepository(root))
            c.start()
            withTimeout(5000) { while (platform.switches.isEmpty()) delay(10) }
            val (dir, previous) = platform.switches.single()
            assertEquals("b", dir)
            assertEquals("ゴーストAさくら", previous.sakuraName)
            assertEquals("ゴーストA", previous.name)
            assertEquals(a.dir.absolutePath + "/", previous.path)
            assertTrue(previous.script, "さようなら" in previous.script)
            // スクリプトからの切り替えでは OnGhostChanging は送らない（--option=raise-event が無いので）
            assertEquals(0, shiori.count("OnGhostChanging"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun menuChangesGhostWithFarewell() = runBlocking {
        val root = tmp.newFolder("ghost")
        val a = ghostIn(root, "a", "ゴーストA")
        val b = ghostIn(root, "b", "ゴーストB")
        val shiori = ScriptedShiori(mapOf("OnGhostChanging" to """\0またね"""))
        val platform = SwitchPlatform()
        val scope = testScope()
        try {
            val c = controller(a, shiori, scope, platform, GhostRepository(root))
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            c.onMouseLongPress(0, 0, 0, null)
            c.awaitChoices("pono:ghosts")
            c.onChoice(Choice("ゴースト切り替え", "pono:ghosts", listOf("0")))
            c.awaitChoices("pono:ghost")
            val menu = c.state.value.characters[0]!!.balloon!!.filterIsInstance<BalloonItem.ChoiceItem>().map { it.choice.title to it.choice.id }
            // 自分は並べない
            assertTrue(menu.toString(), ("ゴーストB" to "pono:ghost") in menu)
            assertFalse(menu.toString(), menu.any { it.first == "ゴーストA" })

            c.onChoice(Choice("ゴーストB", "pono:ghost", listOf("b")))
            assertEquals(listOf("ゴーストBさくら", "manual", "ゴーストB", b.dir.absolutePath + "/"), shiori.awaitEvent("OnGhostChanging"))
            withTimeout(5000) { while (platform.switches.isEmpty()) delay(10) }
            assertEquals("b" to """\0またね""", platform.switches.single().let { it.first to it.second.script })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun switchedGhostBootsWithOnGhostChanged() = runBlocking {
        val root = tmp.newFolder("ghost")
        val b = ghostIn(root, "b", "ゴーストB")
        b.saveProfile(b.loadProfile().apply { setProperty("booted", "1") })
        val shiori = ScriptedShiori(mapOf("OnGhostChanged" to """\0いらっしゃい"""))
        val scope = testScope()
        try {
            val previous = PreviousGhost("ゴーストAさくら", """\0またね""", "ゴーストA", "/x/a/")
            val c = controller(b, shiori, scope, SwitchPlatform(), GhostRepository(root), previous)
            c.start()
            assertEquals(listOf("ゴーストAさくら", """\0またね""", "ゴーストA", "/x/a/", "", "", "", "master"), shiori.awaitEvent("OnGhostChanged"))
            withTimeout(5000) { c.state.first { it.characters[0]?.balloon?.isNotEmpty() == true } }
            c.awaitIdle()
            assertEquals("いらっしゃい", c.text(0))
            // OnGhostChanged に答えたので OnBoot は送らない
            assertEquals(0, shiori.count("OnBoot"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun balloonImageOptionsAndDataUri() = runBlocking {
        val shiori = ScriptedShiori(
            mapOf("OnFirstBoot" to """\0\_b[img/a.png,10,20,--option=fixed,--scaling=50% 50%]\_b["data:image/png;base64,iVBORw0KGgo=",inline]文"""),
        )
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            File(ghost.masterDir, "img").mkdirs()
            File(ghost.masterDir, "img/a.png").writeBytes(byteArrayOf(1))
            val c = testController(ghost, shiori, scope)
            c.start()
            c.await { it.characters[0]?.balloon?.any { i -> i is BalloonItem.Text && i.text == "文" } == true }
            val images = c.state.value.characters[0]!!.balloon!!.filterIsInstance<BalloonItem.Image>()
            assertEquals(true, images[0].options.fixed)
            assertEquals(net.pono.baseware.sakura.BalloonImageOptions.Scaling.Size(50f, true, 50f, true), images[0].options.scaling)
            // data: の画像は pono/cache に書き出したファイルにする
            val data = File(images[1].path)
            assertEquals("cache", data.parentFile.name)
            assertEquals(listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), data.readBytes().map { it.toInt() and 0xff })
        } finally {
            scope.cancel()
        }
    }

}
