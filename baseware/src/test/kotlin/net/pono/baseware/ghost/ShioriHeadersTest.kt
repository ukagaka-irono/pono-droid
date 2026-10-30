package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.SstpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SHIORI/3.0 のヘッダ（SenderType・Status・BaseID と、答えの ValueNotify・ErrorLevel・SecurityLevel・PassThru など） */
class ShioriHeadersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(
        replies: Map<String, String>,
        headers: Map<String, Map<String, String>> = emptyMap(),
        block: suspend (GhostController, ScriptedShiori, RecordingPlatform) -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies, headers)
        val platform = RecordingPlatform()
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope, platform)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            block(c, shiori, platform)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requestsSayWhereTheyCameFrom() = run(
        mapOf("OnTest" to """\0やあ\![raise,OnRaised]\![embed,OnEmbedded]\![get,property,OnProp,system.year]\e"""),
    ) { c, shiori, _ ->
        // 初めての起動は OnBoot の仲間
        assertEquals("OnBoot", shiori.requestHeaders["OnFirstBoot"]?.get("BaseID"))
        assertEquals("internal", shiori.requestHeaders["OnFirstBoot"]?.get("SenderType"))
        c.raise("OnTest", emptyList())
        shiori.awaitEvent("OnProp")
        shiori.awaitEvent("OnRaised")
        assertEquals("internal,raise", shiori.requestHeaders["OnRaised"]?.get("SenderType"))
        assertEquals("internal,embed", shiori.requestHeaders["OnEmbedded"]?.get("SenderType"))
        assertEquals("internal,property", shiori.requestHeaders["OnProp"]?.get("SenderType"))
        // 話している最中に送ったものは、Status に talking とバルーンが入る
        assertTrue(shiori.requestHeaders["OnEmbedded"]?.get("Status").orEmpty().split(',').containsAll(listOf("talking", "balloon(0=0)")))
    }

    @Test
    fun valueNotifyErrorsAndExternalScripts() = run(
        mapOf("OnTest" to """\0外から\![open,browser,https://example.test/]\e"""),
        mapOf(
            "OnSecondChange" to mapOf("ValueNotify" to """\0無視\![raise,OnFromNotify,1]\![open,browser,https://example.test/x]"""),
            "OnTest" to mapOf("SecurityLevel" to "external", "ErrorLevel" to "warning\u0001error", "ErrorDescription" to "一つ目\u0001二つ目"),
        ),
    ) { c, shiori, platform ->
        // ValueNotify の決まったタグ（\![raise]）だけが動く
        assertEquals(listOf("1"), shiori.awaitEvent("OnFromNotify", timeoutMs = 5000))
        c.raise("OnTest", emptyList())
        withTimeout(5000) { while (c.developerLog.entries("error").size < 2) delay(10) }
        assertEquals(listOf("Warning", "Error"), c.developerLog.entries("error").map { it.type }.takeLast(2).sorted().reversed())
        withTimeout(5000) { c.state.first { !it.talking } }
        // SecurityLevel: external のスクリプトはブラウザを開けない
        assertTrue(platform.urls.isEmpty())
    }

    @Test
    fun sstpPassThruBothWaysAndFmo() = run(
        mapOf("OnHello" to """\0こんにちは\e"""),
        mapOf("OnHello" to mapOf("X-SSTP-PassThru-Answer" to "42")),
    ) { c, shiori, _ ->
        fun req(text: String) = SstpServer.parse(text.replace("\n", "\r\n").toByteArray())!!
        val r = c.handleSstp(req("SEND SSTP/1.4\nSender: app\nEvent: OnHello\nX-SSTP-PassThru-Question: q\nCharset: UTF-8\n\n"))
        assertEquals(200, r.code)
        assertTrue(r.headers.contains("X-SSTP-PassThru-Answer" to "42"))
        val sent = shiori.requestHeaders["OnHello"].orEmpty()
        assertEquals("q", sent["X-SSTP-PassThru-Question"])
        assertEquals("external,sstp", sent["SenderType"])
        assertEquals("null", sent["SecurityOrigin"])
        val fmo = c.handleSstp(req("EXECUTE SSTP/1.1\nSender: app\nCommand: GetFMO\nCharset: UTF-8\n\n"))
        // FMO には Owned SSTP の uniqueid ではなく、宛先を選ぶためだけの識別 ID を出す
        assertTrue(fmo.data.orEmpty().contains("${c.fmoId}.ghostpath\u0001"))
        assertFalse(fmo.data.orEmpty().contains(c.uniqueId))
    }
}
