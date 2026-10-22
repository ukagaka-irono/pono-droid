package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import net.pono.baseware.update.NetworkUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
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
            assertTrue(shiori.events.filter { it.first == "OnExecuteHTTPFailure" }.all { it.second[3] == "busy" })
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
            assertEquals("unknown", shiori.awaitEvent("OnUpdateFailure")[0])
        } finally {
            scope.cancel()
        }
    }
}
