package net.pono.baseware.net

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.GhostState
import net.pono.baseware.ghost.RecordingPlatform
import net.pono.baseware.ghost.ScriptedShiori
import net.pono.baseware.ghost.newTestGhost
import net.pono.baseware.ghost.testController
import net.pono.baseware.ghost.testScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** \![execute,http-*] のオプションと結果のイベント、rss / ical、ストリーミング */
class HttpExecuteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var ghosts = 0

    /** 頼まれた通信を記録し、決めておいた応答を返す */
    private class FakeClient(
        val code: Int = 200,
        val body: ByteArray = "ok".toByteArray(),
        val headers: List<Pair<String, String>> = listOf("Set-Cookie" to "sid=abc; Path=/", "Content-Type" to "text/plain"),
        val tls: TlsInfo? = null,
        val gate: CountDownLatch? = null,
    ) : HttpClient {
        val requests: MutableList<HttpRequest> = Collections.synchronizedList(mutableListOf())

        override fun execute(
            request: HttpRequest,
            sink: OutputStream,
            maxBytes: Long,
            onResponse: (code: Int, headers: List<Pair<String, String>>) -> Unit,
            onProgress: (bytes: Long, total: Long) -> Unit,
        ): HttpOutcome {
            requests += request
            gate?.await(10, TimeUnit.SECONDS)
            onResponse(code, headers)
            sink.write(body)
            onProgress(body.size.toLong(), body.size.toLong())
            return HttpOutcome.Response(code, headers, tls, body.size.toLong())
        }
    }

    private fun run(client: HttpClient, replies: Map<String, String>, block: suspend (GhostController, ScriptedShiori, File) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost(name = "ghost${ghosts++}")
            val c = testController(ghost, shiori, scope, RecordingPlatform(), httpClient = client)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori, ghost.masterDir)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GhostController.awaitState(predicate: (GhostState) -> Boolean) = withTimeout(5000) { state.first(predicate) }

    private fun GhostState.text() = characters[0]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    @Test
    fun parsesMethodsOptionsAndBodies() {
        val get = HttpCommand.parse("http-get", listOf("https://e.test/a?x=1", "--param=q=ねこ", "--header=X-Test: 1", "--header=Bad\r\nInjected: 1", "--timeout=999", "--no-cache"))!!
        assertEquals("https://e.test/a?x=1&q=ねこ", get.requestUrl())
        assertEquals(listOf("X-Test" to "1", "Cache-Control" to "no-cache"), get.headers)
        assertEquals(HttpCommand.MAX_TIMEOUT_SEC, get.timeoutSec)
        assertEquals(false, get.async)
        // 古い書き方: --param を付けないパラメータ
        val post = HttpCommand.parse("http-post", listOf("https://e.test/", "a=1", "--param=b=2", "--async=OnSent"))!!
        assertEquals("a=1&b=2" to "application/x-www-form-urlencoded", post.body(null)!!.let { String(it.first) to it.second })
        assertEquals("OnSent", post.event(""))
        assertEquals("OnSentFailure", post.event("Failure"))
        val multi = HttpCommand.parse("http-put", listOf("https://e.test/", "--content-type=multipart/form-data", "--param=msg=hi"))!!
        val (bytes, type) = multi.body(null)!!
        assertTrue(type.startsWith("multipart/form-data; boundary="))
        assertTrue(String(bytes).contains("name=\"msg\"\r\n\r\nhi\r\n"))
        assertEquals("OnExecuteRSSComplete", HttpCommand.parse("rss-get", listOf("https://e.test/f", "--async=feed"))!!.event(""))
        assertEquals(null, HttpCommand.parse("rss-put", listOf("https://e.test/")))
        assertEquals(null, HttpCommand.parse("http-get", listOf("file:///etc/passwd")))
        val ical = HttpCommand.parse("ical-get", listOf("https://e.test/c.ics", "--from=20261001", "--expand=7", "--limit=3"))!!
        assertEquals(LocalDate.of(2026, 10, 1), ical.from)
        assertEquals(7, ical.expandDays)
    }

    @Test
    fun syncRequestWaitsAndReportsCookiesAndHeaders() {
        val client = FakeClient(body = "一行目\n二行目".toByteArray())
        run(client, mapOf("OnTest" to """\0\![execute,http-get,https://e.test/x,--nofile,--sync=OnGot]あと\e""")) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            val refs = shiori.awaitEvent("OnGot")
            assertEquals(listOf("get", "OnGot", "https://e.test/x", "一行目\u0001二行目", "200", "sid=abc"), refs.take(6))
            assertTrue(refs[6].contains("Content-Type: text/plain"))
            c.awaitState { it.text() == "あと" }
        }
    }

    @Test
    fun savesToVarAndUsesTheFullPath() {
        val client = FakeClient(body = "<html/>".toByteArray())
        run(client, mapOf("OnTest" to """\![execute,http-post,https://e.test/page.html,--param=a=1,--async=id1]\e""")) { c, shiori, master ->
            c.raise("OnTest", emptyList())
            val refs = shiori.awaitEvent("OnExecuteHTTPComplete")
            val file = File(master, "var/page.html")
            assertEquals(listOf("post", "id1", "https://e.test/page.html", file.absolutePath, "200"), refs.take(5))
            assertEquals("<html/>", file.readText())
            assertEquals("POST", client.requests.single().method)
            assertEquals("a=1", String(client.requests.single().body!!))
        }
    }

    @Test
    fun errorCodesAndDuplicatesFail() {
        val gate = CountDownLatch(1)
        val client = FakeClient(code = 404, gate = gate)
        run(
            client,
            mapOf("OnTest" to """\![execute,http-get,https://e.test/x,--async=a]\![execute,http-get,https://e.test/x,--async=b]\e"""),
        ) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            // 同じ URL を取っている途中なら downloading で断る
            withTimeout(5000) { while (shiori.count("OnExecuteHTTPFailure") < 1) delay(10) }
            assertEquals("downloading", shiori.events.first { it.first == "OnExecuteHTTPFailure" }.second[4])
            gate.countDown()
            withTimeout(5000) { while (shiori.count("OnExecuteHTTPFailure") < 2) delay(10) }
            assertEquals("404", shiori.events.last { it.first == "OnExecuteHTTPFailure" }.second[4])
        }
    }

    @Test
    fun cancelStopsTheRequest() {
        val gate = CountDownLatch(1)
        val client = FakeClient(gate = gate)
        run(client, mapOf("OnTest" to """\![execute,http-get,https://e.test/slow,--async=s]\e""", "OnStop" to """\![cancel,http,https://e.test/slow]\e""")) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            withTimeout(5000) { while (client.requests.isEmpty()) delay(10) }
            c.raise("OnStop", emptyList())
            assertEquals("userbreak", shiori.awaitEvent("OnExecuteHTTPFailure")[4])
            gate.countDown()
        }
    }

    @Test
    fun sslInfoIsSentWithItsOwnName() {
        val tls = TlsInfo("TLSv1/SSLv3", "TLS_AES_128_GCM_SHA256", "/C=JP/O=Pono/CN=e.test", "2026,1,1,0,0,0", "2027,1,1,0,0,0", "/C=JP/O=CA")
        run(FakeClient(tls = tls), mapOf("OnTest" to """\![execute,http-get,https://e.test/s,--async=OnSecure,--nofile]\e""")) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            // On で始まる ID でも、SSL の様子はいつもの名前で届く
            assertEquals(
                listOf("OnSecure", "https://e.test/s", "200", "TLSv1/SSLv3", "TLS_AES_128_GCM_SHA256", "/C=JP/O=Pono/CN=e.test", "2026,1,1,0,0,0", "2027,1,1,0,0,0", "/C=JP/O=CA"),
                shiori.awaitEvent("OnExecuteHTTPSSLInfo"),
            )
            assertEquals("ok", shiori.awaitEvent("OnSecure")[3])
        }
    }

    @Test
    fun streamingLinesAndServerSentEvents() {
        val lines = mutableListOf<String>()
        HttpStreamer("text") { lines += it }.apply {
            feed("a\nb\r\n".toByteArray(), 0, 5)
            feed("c".toByteArray(), 0, 1)
            finish()
        }
        assertEquals(listOf("a", "b", "c"), lines)
        val events = mutableListOf<String>()
        HttpStreamer("auto") { events += it }.apply {
            contentType("text/event-stream; charset=utf-8")
            val s = ": comment\nevent: x\ndata: one\ndata: two\n\ndata: three\n\n"
            feed(s.toByteArray(), 0, s.toByteArray().size)
            finish()
        }
        assertEquals(listOf("one\u0001two", "three"), events)
    }

    @Test
    fun rssItemsAreFilteredNewestFirst() {
        val rss = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel><title>t</title>
            <item><title>古い</title><link>https://e.test/1</link><pubDate>Mon, 05 Oct 2026 09:00:00 +0900</pubDate><description>a</description></item>
            <item><title>新しい</title><link>https://e.test/2</link><pubDate>Fri, 23 Oct 2026 09:00:00 +0900</pubDate><author>ぽの</author><description>b</description></item>
            </channel></rss>
        """.trimIndent()
        val items = FeedParser.parse(rss.toByteArray())!!
        assertEquals(listOf("古い", "新しい"), items.map { it.title })
        val filtered = FeedParser.filter(items, LocalDate.of(2026, 10, 10), null, null)
        assertEquals(listOf("新しい"), filtered.map { it.title })
        assertEquals(listOf("新しい", "古い"), FeedParser.filter(items, null, null, 5).map { it.title })
        assertTrue(items[1].reference().startsWith("新しい\u0001https://e.test/2\u0001"))
        // Atom も読む
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>A</title><link href="https://e.test/a"/><updated>2026-10-01T00:00:00Z</updated></entry></feed>"""
        assertEquals("https://e.test/a", FeedParser.parse(atom.toByteArray())!!.single().link)
        // 外の実体は読まない
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title></item></channel></rss>"""
        assertTrue(FeedParser.parse(xxe.toByteArray())?.none { it.title.contains("root") } ?: true)
    }

    @Test
    fun icalExpandsRepeatsInTheRange() {
        val ics = """
            BEGIN:VCALENDAR
            X-WR-CALNAME:テスト
            BEGIN:VEVENT
            UID:weekly
            DTSTART:20261005T090000
            DTEND:20261005T100000
            SUMMARY:週1
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=5
            EXDATE:20261007T090000
            END:VEVENT
            BEGIN:VEVENT
            UID:trip
            DTSTART;VALUE=DATE:20261010
            DTEND;VALUE=DATE:20261013
            SUMMARY:旅行
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val cal = ICal.parse(ics)!!
        assertEquals("テスト", cal.name)
        val expanded = ICal.references(cal, ICal.range(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, expand = true, limit = null))
        // 月・水の5回から除外日を引いた4回と、旅行の3日
        val days = expanded.drop(1).map { it.split('\u0001').let { f -> f[0] to f[11] } }
        assertEquals(
            listOf(
                "weekly" to "2026,10,5,,,", "trip" to "2026,10,10,,,", "trip" to "2026,10,11,,,", "trip" to "2026,10,12,,,",
                "weekly" to "2026,10,12,,,", "weekly" to "2026,10,14,,,", "weekly" to "2026,10,19,,,",
            ),
            days,
        )
        assertEquals("7", expanded[0].split('\u0001')[3])
        // 展開しなければ予定ごとに1件（最初の日）
        val once = ICal.references(cal, ICal.range(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 31), null, expand = false, limit = null))
        assertEquals(listOf("trip" to "2026,10,11,,,", "weekly" to "2026,10,12,,,"), once.drop(1).map { it.split('\u0001').let { f -> f[0] to f[11] } })
        // 終日の予定の終わりは最終日
        assertEquals("2026,10,12,,,", once[1].split('\u0001')[2])
    }

    @Test
    fun rssEventsFromTheController() {
        val rss = "<rss><channel><item><title>T</title><link>https://e.test/i</link></item></channel></rss>"
        run(FakeClient(body = rss.toByteArray()), mapOf("OnTest" to """\![execute,rss-get,https://e.test/feed,--async=OnFeed]\e""")) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            assertEquals("T\u0001https://e.test/i\u0001\u0001\u0001", shiori.awaitEvent("OnFeed").single())
        }
        run(FakeClient(body = "not xml".toByteArray()), mapOf("OnTest" to """\![execute,rss-get,https://e.test/feed,--async=x]\e""")) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            assertEquals("parse", shiori.awaitEvent("OnExecuteRSSFailure")[4])
        }
    }

    @Test
    fun onelineCertificateNames() {
        assertEquals("/C=JP/O=Pono, Inc./CN=e.test", UrlConnectionHttpClient.oneline("CN=e.test,O=Pono\\, Inc.,C=JP"))
    }

    @Test
    fun logWritesTheExchange() = run(
        FakeClient(body = "こんにちは".toByteArray()),
        mapOf("OnGet" to """\![execute,http-post,https://example.com/api,--param=a=1,--nofile=UTF-8,--log=http.log,--async=OnDone]\e"""),
    ) { c, shiori, master ->
        c.raise("OnGet", emptyList())
        shiori.awaitEvent("OnDone")
        val log = File(master, "var/http.log").readText()
        assertTrue(log, "> POST https://example.com/api" in log)
        assertTrue(log, "a=1" in log)
        assertTrue(log, "< 200" in log)
        assertTrue(log, "< Content-Type: text/plain" in log)
        assertTrue(log, "こんにちは" in log)
    }


    @Test
    fun hugeDurationInAnIcalIsAFailureNotACrash() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nDTSTART:20261001T090000\r\nDURATION:P1000000000000D\r\nSUMMARY:s\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        run(FakeClient(body = ics.toByteArray()), mapOf("OnTest" to """\![execute,ical-get,https://e.test/c.ics,--async=OnCal]\e""")) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            // 直す前は例外で通信のコルーチンが死に、どちらも届かない（アプリでは落ちる）
            withTimeout(5000) { while (shiori.count("OnCal") + shiori.count("OnCalFailure") == 0) delay(10) }
        }
    }
}
