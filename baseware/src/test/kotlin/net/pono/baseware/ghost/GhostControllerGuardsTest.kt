package net.pono.baseware.ghost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.HttpClient
import net.pono.baseware.net.HttpOutcome
import net.pono.baseware.net.HttpRequest
import net.pono.baseware.net.TlsInfo
import net.pono.baseware.net.WebSocketConnector
import net.pono.baseware.net.WebSocketMessage
import net.pono.baseware.net.WebSocketSession
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import net.pono.baseware.update.NetworkUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 悪意のあるゴースト（スクリプト）に対する歯止め */
class GhostControllerGuardsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun raiseChainIsCapped() = runBlocking {
        // \![raise] が自分を起こし続けるスクリプト
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\![raise,OnLoop]""", "OnLoop" to """\![raise,OnLoop]"""))
        val scope = testScope()
        try {
            testController(tmp.newTestGhost(), shiori, scope).start()
            shiori.awaitEvent("OnLoop")
            delay(1500)
            val n = shiori.count("OnLoop")
            assertTrue("chain stopped at $n", n in 1..(GhostController.MAX_RAISE_CHAIN + 1))
            delay(500)
            assertEquals(n, shiori.count("OnLoop"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentHttpGetsAreLimited() = runBlocking {
        val gate = CountDownLatch(1)
        val started = AtomicInteger()
        val fetcher = HttpFetcher { _, _, _ ->
            started.incrementAndGet()
            gate.await(10, TimeUnit.SECONDS)
            FetchResult.Ok
        }
        val requests = 8
        val script = (1..requests).joinToString("") { """\![execute,http-get,https://x.test/$it,--async=a$it]""" }
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to script))
        val scope = testScope()
        try {
            testController(tmp.newTestGhost(), shiori, scope, fetcher = fetcher).start()
            // 上限を超えた分はすぐに busy で断られ、通信は上限の数しか始まらない
            val refused = requests - GhostController.MAX_HTTP_GETS
            withTimeout(5000) { while (shiori.count("OnExecuteHTTPFailure") < refused) delay(10) }
            assertTrue(synchronized(shiori.events) { shiori.events.filter { it.first == "OnExecuteHTTPFailure" }.all { it.second[4] == "busy" } })
            // 断るのは通信が始まるより先のことがあるので、始まるのを待ってから、上限より多く始まらないことを確かめる
            withTimeout(5000) { while (started.get() < GhostController.MAX_HTTP_GETS) delay(10) }
            delay(200)
            assertEquals(GhostController.MAX_HTTP_GETS, started.get())
            gate.countDown()
            withTimeout(5000) { while (shiori.count("OnExecuteHTTPComplete") < GhostController.MAX_HTTP_GETS) delay(10) }
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun onlyWebUrlsAreOpened() = runBlocking {
        val shiori = ScriptedShiori(
            mapOf("OnFirstBoot" to """\![open,browser,tel:0123]\j[https://a.test/]\![open,browser,HTTPS://b.test/x]\j[javascript:alert(1)]\![open,browser,file:///etc/passwd]\e"""),
        )
        val platform = RecordingPlatform()
        val scope = testScope()
        try {
            testController(tmp.newTestGhost(), shiori, scope, platform).start()
            withTimeout(5000) { while (platform.urls.size < 2) delay(10) }
            delay(200)
            assertEquals(listOf("https://a.test/", "HTTPS://b.test/x"), platform.urls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun typedTextIsNotLogged() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\![open,passwordinput,OnPassword,0]\e""", "OnPassword" to """\0ok\e"""))
        val platform = RecordingPlatform()
        val scope = testScope()
        try {
            val controller = testController(tmp.newTestGhost(), shiori, scope, platform)
            controller.start()
            val box = withTimeout(5000) { controller.state.first { it.input != null } }.input!!
            controller.onInputSubmit(box.serial, "hunter2")
            assertEquals(listOf("hunter2"), shiori.awaitEvent("OnPassword"))
            delay(200)
            assertTrue(platform.logs.toString(), platform.logs.none { "hunter2" in it })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun brokenUpdateDoesNotCrashTheGhost() = runBlocking {
        // 1回目: 一覧に NUL 入りのパスしか無い → 無視されて「変更なし」。2回目: 通信で例外 → 失敗として伝える
        val ghost = tmp.newTestGhost(extraDescript = "homeurl,https://example.test/ghost/\r\n")
        val manifest = "ghost/master/a\u0000.dic\u0001${NetworkUpdater.md5(ByteArray(0))}\u0001\r\n"
        val calls = AtomicInteger()
        val fetcher = HttpFetcher { _, sink, _ ->
            if (calls.incrementAndGet() > 1) throw IllegalStateException("boom")
            sink.write(manifest.toByteArray())
            FetchResult.Ok
        }
        val shiori = ScriptedShiori(emptyMap())
        val scope = testScope()
        try {
            val controller = testController(ghost, shiori, scope, fetcher = fetcher)
            controller.start()
            shiori.awaitEvent("OnFirstBoot")
            controller.networkUpdate()
            assertEquals("none", shiori.awaitEvent("OnUpdateComplete")[0])
            controller.networkUpdate()
            assertEquals("parse", shiori.awaitEvent("OnUpdateFailure")[0])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun secondUpdateWhileRunningSendsExecutingAndVersionComesFirst() = runBlocking {
        val ghost = tmp.newTestGhost(extraDescript = "homeurl,https://example.test/ghost/\r\n")
        val release = CountDownLatch(1)
        val fetcher = HttpFetcher { _, _, _ ->
            release.await(5, TimeUnit.SECONDS)
            FetchResult.HttpError(404)
        }
        val shiori = ScriptedShiori(emptyMap())
        val scope = testScope()
        try {
            val controller = testController(ghost, shiori, scope, fetcher = fetcher)
            controller.start()
            shiori.awaitEvent("OnFirstBoot")
            // basewareversion は Reference0 が版、Reference1 が本体の名前
            assertEquals(GhostController.BASEWARE_VERSION, shiori.awaitEvent("basewareversion")[0])
            assertEquals("pono-droid", shiori.awaitEvent("basewareversion")[1])
            controller.networkUpdate()
            // メニューやアプリの「更新」は manual
            assertEquals("manual", shiori.awaitEvent("OnUpdateBegin")[4])
            controller.networkUpdate()
            assertEquals(listOf("executing", "", "", "ghost", "manual"), shiori.awaitEvent("OnUpdateFailure"))
            release.countDown()
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun websocketWithABadPortFailsInsteadOfCrashing() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\![execute,websocket,ws://127.0.0.1:99999/,--async=w]\e"""))
        val scope = testScope()
        try {
            testController(tmp.newTestGhost(), shiori, scope).start()
            // 直す前は例外でコルーチンが死に、Failure が来ない（アプリでは落ちる）
            shiori.awaitEvent("OnExecuteWebSocketFailure")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingRightAfterStartingDoesNotLeakTheSlot() = runBlocking<Unit> {
        val fetcher = HttpFetcher { _, sink, _ -> sink.write("ok".toByteArray()); FetchResult.Ok }
        val n = GhostController.MAX_HTTP_GETS
        val boot = (1..n).joinToString("") { """\![execute,http-get,https://x.test/$it,--async=a$it]\![cancel,http,https://x.test/$it]""" }
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to "$boot\\e", "OnGet" to """\![execute,http-get,https://x.test/z,--async=z]\e"""))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope, fetcher = fetcher)
            c.start()
            withTimeout(5000) { while (shiori.count("OnExecuteHTTPFailure") < n) delay(10) }
            delay(300)
            assertFalse("online mark is stuck", c.state.value.online)
            c.raise("OnGet", emptyList())
            withTimeout(5000) { while (shiori.count("OnExecuteHTTPComplete") + shiori.count("OnExecuteHTTPFailure") < n + 1) delay(10) }
            // 直す前は 5 回目が busy で断られる
            assertEquals(1, shiori.count("OnExecuteHTTPComplete"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun updateReadyIsAskedOnTheGhostThread() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost(extraDescript = "homeurl,https://example.test/ghost/\r\n")
        val manifest = "ghost/master/a.dic\u0001${NetworkUpdater.md5("x".toByteArray())}\u0001\r\n"
        val fetcher = HttpFetcher { url, sink, _ ->
            if (url.endsWith("updates2.dau")) { sink.write(manifest.toByteArray()); FetchResult.Ok } else FetchResult.HttpError(404)
        }
        val threads = Collections.synchronizedList(mutableListOf<String>())
        val platform = object : GhostPlatform by RecordingPlatform() {
            override fun log(message: String) { if (message.startsWith("OnUpdateReady ")) threads += Thread.currentThread().name }
        }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "ghost-thread") }
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            val shiori = ScriptedShiori(emptyMap())
            val c = testController(ghost, shiori, scope, platform, fetcher = fetcher)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            c.networkUpdate()
            withTimeout(5000) { while (threads.isEmpty()) delay(10) }
            // IO のスレッド（DefaultDispatcher-worker-N）ではなく、ゴーストのスレッドで聞く（デバッグ時は名前の後ろに @coroutine#N が付く）
            assertTrue(threads.toString(), threads.isNotEmpty() && threads.all { it.startsWith("ghost-thread") })
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }

    @Test
    fun deletedFolderIsReportedOnce() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        val dir = File(ghost.masterDir, "watched").apply { mkdirs() }
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\![execute,filewatch,watched,--async=w]\e"""))
        val scope = testScope()
        try {
            testController(ghost, shiori, scope).start()
            shiori.awaitEvent("OnFirstBoot")
            delay(500)
            dir.deleteRecursively()
            shiori.awaitEvent("OnExecuteFileWatchChange")
            delay(1500)
            // 直す前は 0.3 秒ごとに deleted が届き続ける
            assertEquals(1, shiori.count("OnExecuteFileWatchChange"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disposeStopsHttpDownloads() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val client = object : HttpClient {
            override fun execute(
                request: HttpRequest, sink: OutputStream, maxBytes: Long,
                onResponse: (Int, List<Pair<String, String>>) -> Unit, onProgress: (Long, Long) -> Unit,
            ): HttpOutcome {
                started.countDown()
                gate.await(10, TimeUnit.SECONDS)
                onResponse(200, emptyList())
                sink.write("late".toByteArray())
                return HttpOutcome.Response(200, emptyList(), null, 4)
            }
        }
        val ghost = tmp.newTestGhost()
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\![execute,http-get,https://e.test/late.txt,--async=a]\e"""))
        val scope = testScope()
        try {
            val c = testController(ghost, shiori, scope, httpClient = client)
            c.start()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { c.dispose().join() }
            gate.countDown()
            delay(500)
            // 直す前は終わった後のゴーストのフォルダに書く
            assertFalse(File(ghost.masterDir, "var/late.txt").exists())
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun twoDownloadsWithTheSameNameDoNotMix() = runBlocking<Unit> {
        val bOpened = CountDownLatch(1)
        val aWritten = CountDownLatch(1)
        val client = object : HttpClient {
            override fun execute(
                request: HttpRequest, sink: OutputStream, maxBytes: Long,
                onResponse: (Int, List<Pair<String, String>>) -> Unit, onProgress: (Long, Long) -> Unit,
            ): HttpOutcome {
                onResponse(200, emptyList())
                return if (request.url.startsWith("https://a.test/")) {
                    bOpened.await(5, TimeUnit.SECONDS) // b の .part も開いてから
                    sink.write("AAAA".toByteArray())
                    aWritten.countDown()
                    HttpOutcome.Response(200, emptyList(), null, 4)
                } else {
                    bOpened.countDown()
                    aWritten.await(5, TimeUnit.SECONDS)
                    sink.write("BB".toByteArray())
                    HttpOutcome.Response(200, emptyList(), null, 2)
                }
            }
        }
        val ghost = tmp.newTestGhost()
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\![execute,http-get,https://a.test/feed,--async=a]\![execute,http-get,https://b.test/feed,--async=b]\e"""))
        val scope = testScope()
        try {
            testController(ghost, shiori, scope, httpClient = client).start()
            withTimeout(5000) { while (shiori.count("OnExecuteHTTPComplete") < 2) delay(10) }
            val content = File(ghost.masterDir, "var/feed").readText()
            // 直す前は "BBAA"
            assertTrue(content, content == "AAAA" || content == "BB")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun closeDuringReconnectWaitIsHonoured() = runBlocking<Unit> {
        val opened = AtomicInteger()
        val connector = WebSocketConnector { _, _, _ ->
            val n = opened.incrementAndGet()
            object : WebSocketSession {
                private val done = CountDownLatch(1)
                override val tls: TlsInfo? = null
                override val closeCode: Int? = null
                override fun read(): WebSocketMessage? {
                    if (n == 1) throw IOException("reset")
                    done.await(10, TimeUnit.SECONDS)
                    return null
                }
                override fun sendText(text: String) {}
                override fun sendBinary(data: ByteArray) {}
                override fun close(code: Int) = done.countDown()
                override fun abort() = done.countDown()
            }
        }
        val ghost = tmp.newTestGhost()
        val shiori = ScriptedShiori(mapOf(
            "OnFirstBoot" to """\![execute,websocket,ws://e.test/,--async=w]\e""",
            "OnStop" to """\![close,websocket,ws://e.test/]\e""",
        ))
        val scope = testScope()
        try {
            val c = GhostController(
                ghost = ghost, shell = Shell(ghost.shellDir()!!), shioriFactory = { _, _ -> shiori }, scope = scope,
                platform = RecordingPlatform(), player = ScriptPlayer(charWaitMs = 0), webSocketConnector = connector,
            )
            c.start()
            shiori.awaitEvent("OnExecuteWebSocketOpen")
            c.raise("OnStop", emptyList()) // 1 秒のつなぎ直し待ちの間
            delay(2500)
            // 直す前は待ちが明けるとつなぎ直す
            assertEquals(0, shiori.count("OnExecuteWebSocketReconnect"))
            assertEquals(1, opened.get())
        } finally {
            scope.cancel()
        }
    }
}
