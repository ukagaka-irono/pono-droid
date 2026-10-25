package net.pono.baseware.ghost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.SstpRequest
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.shiori.Shiori
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 複数のゴーストを同時に動かす（GhostHub）。ゴーストどうしのイベントと \![raiseother]・\![call,ghost] */
class MultiGhostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hub = GhostHub()
    private val scopes: MutableList<CoroutineScope> = Collections.synchronizedList(mutableListOf())
    private lateinit var repository: GhostRepository

    /** \![call,ghost] を記録し、[onCall] に任せる */
    private class HubPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val calls: MutableList<Pair<String, PreviousGhost>> = Collections.synchronizedList(mutableListOf())
        var onCall: (String, PreviousGhost) -> Boolean = { _, _ -> false }

        override fun callGhost(dirName: String, caller: PreviousGhost): Boolean {
            calls += dirName to caller
            return onCall(dirName, caller)
        }
    }

    private class Running(val c: GhostController, val shiori: ScriptedShiori)

    /** 一度起動したことのあるゴーストとして入れる（OnFirstBoot ではなく OnBoot や OnGhostCalled が届く） */
    private fun install(dir: String, name: String, sakura: String) {
        val g = File(repository.root, dir)
        File(g, "ghost/master").mkdirs()
        File(g, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,$name\r\nsakura.name,$sakura\r\nshiori,yaya.dll\r\n")
        File(g, "shell/master").mkdirs()
        File(g, "shell/master/descript.txt").writeText("charset,UTF-8\r\nname,master\r\n")
        val ghost = Ghost(g)
        ghost.saveProfile(ghost.loadProfile().apply { setProperty("booted", "1") })
    }

    /** @param wrap SHIORI に手を加える（答えを待たせるなど） */
    private suspend fun boot(
        dir: String,
        replies: Map<String, String>,
        calledBy: PreviousGhost? = null,
        platform: GhostPlatform = HubPlatform(),
        wrap: (ScriptedShiori) -> Shiori = { it },
    ): Running {
        val ghost = repository.find(dir)!!
        val shiori = ScriptedShiori(replies)
        val scope = testScope().also { scopes += it }
        val c = GhostController(
            ghost = ghost,
            shell = Shell(ghost.shellDir()!!),
            shioriFactory = { _, _ -> wrap(shiori) },
            scope = scope,
            platform = platform,
            repository = repository,
            player = ScriptPlayer(charWaitMs = 0),
            updateFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
            hub = hub,
            calledBy = calledBy,
        )
        c.start()
        // 起動のスクリプトを話し終えて、ほかのゴーストに伝え終えるまで
        withTimeout(5000) { c.state.first { it.booted } }
        return Running(c, shiori)
    }

    private fun run(block: suspend () -> Unit) = runBlocking<Unit> {
        repository = GhostRepository(tmp.newFolder("ghost"))
        install("a", "ゴーストA", "あい")
        install("b", "ゴーストB", "びい")
        install("c", "ゴーストC", "しい")
        try {
            block()
        } finally {
            scopes.toList().forEach { it.cancel() }
        }
    }

    @Test
    fun othersHearWhenAGhostBootsAndCloses() = run {
        val a = boot("a", mapOf("OnBoot" to """\0あいです\e"""))
        val b = boot("b", mapOf("OnBoot" to """\0びいです\e"""))
        assertEquals(listOf("びい", """\0びいです\e""", "ゴーストB", "", "", "", "", "master"), a.shiori.awaitEvent("OnOtherGhostBooted"))
        assertEquals(0, b.shiori.count("OnOtherGhostBooted"))
        assertEquals("2", a.c.property("activeghostlist.count"))
        assertEquals("ゴーストB", a.c.property("activeghostlist.index(1).name"))
        assertEquals("ゴーストA", a.c.property("activeghostlist.current.name"))
        assertEquals("びい", a.c.property("activeghostlist(ゴーストB).sakuraname"))
        // メニューの「ゴーストを終了」は、ほかのゴーストを残すので OnClose だけ
        b.c.close("user", all = false)
        assertEquals(listOf("びい", """\0びいです\e""", "ゴーストB"), a.shiori.awaitEvent("OnOtherGhostClosed").take(3))
        assertEquals(1, b.shiori.count("OnClose"))
        assertEquals(0, b.shiori.count("OnCloseAll"))
        assertEquals(listOf(a.c), hub.running)
    }

    @Test
    fun closingAllTogetherSendsNoOtherGhostClosed() = run {
        val a = boot("a", mapOf("OnCloseAll" to """\0またね\e"""))
        // B の SHIORI は OnCloseAll の答えを待たせる。その間 B は終わりかけで、A が終わったときに届く OnOtherGhostClosed は受けない
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val b = boot("b", emptyMap()) { inner ->
            object : Shiori by inner {
                override fun request(request: ByteArray): ByteArray {
                    if ("\r\nID: OnCloseAll\r\n" in request.toString(Charsets.UTF_8)) {
                        asked.countDown()
                        answer.await(5, TimeUnit.SECONDS)
                    }
                    return inner.request(request)
                }
            }
        }
        b.c.close()
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        a.c.close()
        withTimeout(5000) { while (a.c in hub.running) delay(10) }
        delay(200)
        answer.countDown()
        withTimeout(5000) { while (b.c in hub.running) delay(10) }
        assertEquals(0, a.shiori.count("OnOtherGhostClosed"))
        assertEquals(0, b.shiori.count("OnOtherGhostClosed"))
    }

    @Test
    fun raiseOtherReachesTheNamedGhostOrFails() = run {
        val a = boot(
            "a",
            mapOf("OnSend" to """\![raiseother,ゴーストB,OnHello,x,y]\![notifyother,だれか,OnHi,z]\![raiseother,__SYSTEM_ALL_GHOST__,OnAll]\e"""),
        )
        val b = boot("b", emptyMap())
        a.c.raise("OnSend", emptyList())
        assertEquals(listOf("x", "y"), b.shiori.awaitEvent("OnHello"))
        assertEquals(listOf("notfound", "だれか", "OnHi", "z"), a.shiori.awaitEvent("OnNotifyOtherFailure"))
        b.shiori.awaitEvent("OnAll")
        // __SYSTEM_ALL_GHOST__ でも自分には送らない
        assertEquals(0, a.shiori.count("OnAll"))
    }

    @Test
    fun otherGhostTalkAndSurfaceChange() = run {
        val a = boot("a", mapOf("OnTalk" to """\0\s[3]こんにちは\e"""))
        val b = boot("b", mapOf("OnBoot" to """\![set,otherghosttalk,true]\![set,othersurfacechange,true]\e""", "OnSecondTalk" to """\0はい\e"""))
        a.c.raise("OnTalk", listOf("r0", "r1"))
        assertEquals(listOf("ゴーストA", "あい", "0", "3"), b.shiori.awaitEvent("OnOtherSurfaceChange").take(4))
        assertEquals(
            listOf("ゴーストA", "あい", "", "OnTalk", """\0\s[3]こんにちは\e""", "r0\u0001r1"),
            b.shiori.awaitEvent("OnOtherGhostTalk"),
        )
        // 入れていない A には届かない
        b.c.raise("OnSecondTalk", emptyList())
        withTimeout(5000) { while (b.shiori.count("OnSecondTalk") == 0) delay(10) }
        withTimeout(5000) { b.c.state.first { !it.talking } }
        delay(100)
        assertEquals(0, a.shiori.count("OnOtherGhostTalk"))
    }

    @Test
    fun askShioriOfAGhostThatRuns() = run {
        val a = boot(
            "a",
            mapOf(
                "OnAsk" to """\![get,property,OnAnswer,ghostlist(ゴーストB).shiori.hp,currentghost.shiori.mp,ghostlist(ゴーストC).shiori.hp,system.year]\e""",
                "mp" to "7",
            ),
        )
        boot("b", mapOf("hp" to "42"))
        a.c.raise("OnAsk", emptyList())
        val answer = a.shiori.awaitEvent("OnAnswer")
        // C は動いていないので分からない
        assertEquals(listOf("42", "7", ""), answer.take(3))
        assertTrue(answer[3].toInt() > 2000)
        assertEquals(null, a.c.property("ghostlist(ゴーストB).shiori.hp"))
    }

    @Test
    fun sstpGoesToTheNamedGhost() = run {
        val a = boot("a", emptyMap())
        val b = boot("b", emptyMap())
        fun req(vararg headers: Pair<String, String>) = SstpRequest("SEND", "1.4", headers.toList(), Charsets.UTF_8)
        assertEquals(a.c, hub.sstpTarget(req("Sender" to "x")))
        assertEquals(b.c, hub.sstpTarget(req("ReceiverGhostName" to "びい")))
        assertEquals(null, hub.sstpTarget(req("ReceiverGhostName" to "いない")))
        assertEquals(b.c, hub.sstpTarget(req("IfGhost" to "だれか", "Script" to "x", "IfGhost" to "びい", "Script" to "y")))
        assertEquals(b.c, hub.sstpTarget(req("ID" to b.c.uniqueId)))
    }

    @Test
    fun overlapsAcrossGhostsAndQuietDispose() = run {
        val a = boot("a", emptyMap())
        val b = boot("b", emptyMap())
        a.c.onOtherWindowLayout(listOf("びい/0" to "あい/0"), listOf("びい/1"))
        assertEquals(listOf("あい/0-びい/0", ""), a.shiori.awaitEvent("OnOtherOverlap"))
        assertEquals(listOf("びい/1", ""), a.shiori.awaitEvent("OnOtherOffscreen"))
        // 同じなら送らない
        a.c.onOtherWindowLayout(listOf("あい/0" to "びい/0"), listOf("びい/1"))
        delay(100)
        assertEquals(1, a.shiori.count("OnOtherOverlap"))
        // dispose は何も送らずに抜ける
        withTimeout(5000) { b.c.dispose().join() }
        assertEquals(listOf(a.c), hub.running)
        delay(100)
        assertEquals(0, a.shiori.count("OnOtherGhostClosed"))
        assertEquals(0, b.shiori.count("OnClose"))
    }

    @Test
    fun callGhostBootsItBesideTheCaller() = run {
        val platform = HubPlatform()
        val a = boot("a", mapOf("OnCall" to """\![call,ghost,ゴーストC,--option=raise-event]\e""", "OnGhostCalling" to """\0呼ぶね\e"""), platform = platform)
        val b = boot("b", emptyMap())
        val called = CompletableDeferred<Running>()
        platform.onCall = { dir, caller ->
            testScope().also { scopes += it }.launch { called.complete(boot(dir, emptyMap(), calledBy = caller)) }
            true
        }
        // 一緒に動いている B は呼べない
        assertFalse(a.c.callGhost("ゴーストB"))
        a.c.raise("OnCall", emptyList())
        assertEquals(listOf("しい", "automatic", "ゴーストC"), a.shiori.awaitEvent("OnGhostCalling").take(3))
        val c = withTimeout(5000) { called.await() }
        assertEquals(listOf("あい", """\0呼ぶね\e""", "ゴーストA"), c.shiori.awaitEvent("OnGhostCalled").take(3))
        assertEquals(listOf("しい", "", "ゴーストC"), a.shiori.awaitEvent("OnGhostCallComplete").take(3))
        assertEquals("ゴーストC", b.shiori.awaitEvent("OnOtherGhostBooted")[2])
        // 呼んだ A には OnGhostCallComplete だけ（A の OnOtherGhostBooted は、先に起動した B の分だけ）
        assertEquals(listOf("ゴーストB"), synchronized(a.shiori.events) { a.shiori.events.filter { it.first == "OnOtherGhostBooted" }.map { it.second[2] } })
        assertEquals(listOf("c"), platform.calls.map { it.first })
        assertTrue(hub.isRunning("c"))
    }
}
