package net.pono.baseware.ghost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.SstpRequest
import net.pono.baseware.net.SstpResponse
import net.pono.baseware.net.SstpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Socket

/** SSTP サーバー（この端末の中から）と、ゴーストの答え方 */
class SstpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun req(text: String) = SstpServer.parse(text.replace("\n", "\r\n").toByteArray())!!

    @Test
    fun serverAnswersAndKeepsAlive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val seen = mutableListOf<SstpRequest>()
        val server = SstpServer(scope, { r -> seen += r; SstpResponse(200, data = "ふたり,あいかた") }, port = 0)
        try {
            assertTrue(server.start())
            val port = server.localPort
            withContext(Dispatchers.IO) {
                // keep-alive なら同じ接続で2回
                Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                    s.soTimeout = 5000
                    val out = s.getOutputStream()
                    val input = s.getInputStream()
                    repeat(2) {
                        out.write("EXECUTE SSTP/1.4\r\nCharset: UTF-8\r\nSender: テスト\r\nCommand: GetName\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                        out.flush()
                        // ヘッダは空行まで、追加データは Content-Length のバイト数
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { require(it >= 0) }.toChar())
                        val lines = head.split("\r\n")
                        assertEquals("SSTP/1.4 200 OK", lines[0])
                        assertTrue(lines.contains("Connection: keep-alive"))
                        val length = lines.first { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) read += input.read(body, read, length - read).also { require(it > 0) }
                        assertEquals("ふたり,あいかた", body.decodeToString())
                    }
                }
                // SSTP over HTTP
                val body = "NOTIFY SSTP/1.1\r\nCharset: UTF-8\r\nSender: web\r\nEvent: OnTest\r\nReference0: a\r\n\r\n".toByteArray()
                val http = Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                    s.getOutputStream().write("POST /api/sstp/v1 HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                    s.getInputStream().readBytes().decodeToString()
                }
                assertTrue(http, http.startsWith("HTTP/1.1 200 OK"))
                assertTrue(http, "SSTP/1.1 200 OK" in http)
                // ほかのサイトのページからは受けない
                val foreign = Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                    s.getOutputStream().write("POST /api/sstp/v1 HTTP/1.1\r\nOrigin: https://evil.example\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
                    s.getInputStream().readBytes().decodeToString()
                }
                assertTrue(foreign, foreign.startsWith("HTTP/1.1 403"))
                // 壊れた要求
                assertTrue(SstpServer.send(port, "HELLO\r\n\r\n").startsWith("SSTP/1.4 400"))
                assertTrue(SstpServer.send(port, "SEND SSTP/3.5\r\nSender: x\r\n\r\n").startsWith("SSTP/3.5 505"))
            }
            assertEquals(listOf("EXECUTE", "EXECUTE", "NOTIFY"), seen.map { it.method })
            assertEquals(listOf("a"), seen[2].references)
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun charsetHeaderDecidesTheEncoding() {
        val bytes = "SEND SSTP/1.4\r\nCharset: Shift_JIS\r\nSender: 送り手\r\nScript: \\0こんにちは\r\n\r\n".toByteArray(charset("windows-31j"))
        val r = SstpServer.parse(bytes)!!
        assertEquals("送り手", r["Sender"])
        assertEquals("\\0こんにちは", r["Script"])
    }

    private fun run(replies: Map<String, String> = emptyMap(), block: suspend (GhostController, ScriptedShiori, RecordingPlatform) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val platform = RecordingPlatform()
            val c = testController(tmp.newTestGhost(), shiori, scope, platform)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori, platform)
        } finally {
            scope.cancel()
        }
    }

    private fun GhostController.text(scope: Int) =
        state.value.characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    @Test
    fun uniqueIdIsNotifiedAtBoot() = run { c, shiori, _ ->
        assertEquals(listOf(c.uniqueId), shiori.awaitEvent("uniqueid"))
        assertEquals(32, c.uniqueId.length)
    }

    @Test
    fun sendPicksTheScriptForThisGhost() = run { c, _, _ ->
        val r = c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: app\nScript: \\0みんなへ\\e\nIfGhost: ほか\nScript: \\0ほかへ\\e\nIfGhost: テスト\nScript: \\0あなたへ\\e\n\n"))
        assertEquals(200, r.code)
        assertEquals(listOf("Script" to "\\0あなたへ\\e"), r.headers)
        withTimeout(5000) { c.state.first { "あなたへ" in c.text(0) } }
        // 宛先が違えば 404、Sender が無ければ 400
        assertEquals(404, c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: app\nReceiverGhostName: ほか\nScript: x\n\n")).code)
        assertEquals(400, c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nScript: x\n\n")).code)
        assertEquals(204, c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: app\nIfGhost: ほか\nScript: x\n\n")).code)
    }

    @Test
    fun notifyUsesTheEventAndFallsBack() = run(mapOf("OnMusicPlay" to "\\0いい曲\\e")) { c, shiori, _ ->
        val r = c.handleSstp(req("NOTIFY SSTP/1.1\nCharset: UTF-8\nSender: player\nEvent: OnMusicPlay\nReference0: 曲\nReference1: 歌手\nScript: \\0保険\\e\n\n"))
        assertEquals("\\0いい曲\\e", r.headers.single().second)
        assertEquals(listOf("曲", "歌手"), shiori.awaitEvent("OnMusicPlay"))
        val fallback = c.handleSstp(req("NOTIFY SSTP/1.1\nCharset: UTF-8\nSender: player\nEvent: OnUnknown\nScript: \\0保険\\e\n\n"))
        assertEquals("\\0保険\\e", fallback.headers.single().second)
        c.handleSstp(req("COMMUNICATE SSTP/1.1\nCharset: UTF-8\nSender: User\nSentence: おはよう\nReference0: x\n\n"))
        assertEquals(listOf("User", "おはよう", "x"), shiori.awaitEvent("OnCommunicate"))
        c.handleSstp(req("GIVE SSTP/1.1\nCharset: UTF-8\nSender: x\nSong: 春の歌\n\n"))
        withTimeout(5000) { while (shiori.count("OnMusicPlay") < 2) delay(10) }
        assertEquals(listOf("春の歌"), shiori.awaitEvent("OnMusicPlay"))
    }

    @Test
    fun sstpScriptsCannotDoDangerousThings() = run { c, _, platform ->
        val script = "\\0やあ\\![open,browser,https://e.test/]\\![execute,http-get,https://e.test/]\\![vanishbymyself]\\![raise,OnSafe]\\j[https://e.test/]\\-\\e"
        c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: app\nScript: $script\n\n"))
        withTimeout(5000) { c.state.first { "やあ" in c.text(0) && !it.talking } }
        assertTrue(platform.urls.isEmpty())
        assertEquals(5, platform.logs.count { it.startsWith("SSTP のスクリプトでは使えないタグ") })
        // ゴースト自身（uniqueid を付けたもの）なら制限しない
        c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: me\nID: ${c.uniqueId}\nScript: \\0\\![open,browser,https://e.test/]\\e\n\n"))
        withTimeout(5000) { while (platform.urls.isEmpty()) delay(10) }
        assertEquals(listOf("https://e.test/"), platform.urls.toList())
    }

    @Test
    fun executeCommands() = run { c, shiori, _ ->
        suspend fun exec(cmd: String, extra: String = "", sender: String = "app") = c.handleSstp(req("EXECUTE SSTP/1.1\nCharset: UTF-8\nSender: $sender\nCommand: $cmd\n$extra\n"))
        assertEquals("テスト,", exec("GetName").data)
        assertEquals("テスト", exec("GetGhostName").data)
        assertEquals("master", exec("GetShellName").data)
        assertEquals("master\r\n", exec("GetShellNameList").data)
        assertEquals(204, exec("GetPluginNameList").code)
        assertEquals("pono-droid", exec("GetProperty[baseware.name]").data)
        assertEquals(204, exec("SetCookie[k,v]").code)
        assertEquals("v", exec("GetCookie[k]").data)
        // クッキーは送り手ごと
        assertEquals(204, exec("GetCookie[k]", sender = "other").code)
        assertEquals(420, exec("SetProperty", "Reference0: currentghost.seriko.zorder\nReference1: s0,b0\n").code)
        assertEquals(501, exec("Explode").code)
        // Quiet の間は OnSecondChange の「話せるか」が 0
        assertEquals(204, exec("Quiet").code)
        val before = shiori.count("OnSecondChange")
        withTimeout(5000) { while (shiori.count("OnSecondChange") <= before) delay(10) }
        assertEquals("0", shiori.awaitEvent("OnSecondChange")[3])
        assertEquals(204, exec("Restore").code)
    }

    @Test
    fun theLastSenderCanBeRefusedFromTheMenu() = run { c, shiori, _ ->
        assertEquals(200, c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: うるさいアプリ\nScript: \\0やあ\\e\n\n")).code)
        withTimeout(5000) { c.state.first { !it.talking } }
        c.onMouseLongPress(0, 0, 0, null)
        withTimeout(5000) { c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == "pono:sstp-block" } == true } }
        c.onChoice(net.pono.baseware.sakura.Choice("「うるさいアプリ」の SSTP を受けない", "pono:sstp-block", emptyList()))
        assertEquals(listOf("127.0.0.1"), shiori.awaitEvent("OnSSTPBlacklisting"))
        assertEquals(420, c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: うるさいアプリ\nScript: \\0また\\e\n\n")).code)
        assertEquals(200, c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: べつのアプリ\nScript: \\0どうも\\e\n\n")).code)
    }

    @Test
    fun breakingAnSstpScriptSendsOnSSTPBreak() = run { c, shiori, _ ->
        c.handleSstp(req("SEND SSTP/1.4\nCharset: UTF-8\nSender: app\nScript: \\0ながい\\_w[3000]はなし\\e\n\n"))
        withTimeout(5000) { c.state.first { it.talking && "ながい" in c.text(0) } }
        c.onBalloonClick(0)
        assertEquals("0", shiori.awaitEvent("OnSSTPBreak")[1])
        assertFalse(shiori.count("OnBalloonBreak") > 0)
    }
}
