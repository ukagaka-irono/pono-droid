package net.pono.baseware.net

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.RecordingPlatform
import net.pono.baseware.ghost.ScriptedShiori
import net.pono.baseware.ghost.newTestGhost
import net.pono.baseware.ghost.testScope
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** WebSocket・ping・nslookup・ファイルの見張り・ZIP・SNTP・更新のオプション */
class NetToolsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var ghosts = 0

    /** 決めておいたメッセージを順に返し、送られたものを覚える WebSocket */
    private class FakeSocket(private val incoming: LinkedBlockingQueue<WebSocketMessage>) : WebSocketSession {
        val sent = LinkedBlockingQueue<String>()
        override val tls: TlsInfo? = null
        override var closeCode: Int? = null

        override fun read(): WebSocketMessage? {
            val m = incoming.poll(10, TimeUnit.SECONDS)
            if (m == null || m === END) {
                if (closeCode == null) closeCode = 1000
                return null
            }
            return m
        }

        override fun sendText(text: String) {
            sent += text
        }

        override fun sendBinary(data: ByteArray) {
            sent += "bin:" + Base64.getEncoder().encodeToString(data)
        }

        override fun close(code: Int) {
            closeCode = code
            incoming += END
        }

        override fun abort() {
            incoming += END
        }
    }

    private companion object {
        /** つながりが終わった印（キューに null は入れられない） */
        val END = WebSocketMessage(false, ByteArray(0))
    }

    private fun run(
        replies: Map<String, String>,
        connector: WebSocketConnector = WebSocketConnector { _, _, _ -> throw IOException("no") },
        fetcher: HttpFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
        extraDescript: String = "",
        block: suspend (GhostController, ScriptedShiori, File) -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost(name = "ghost${ghosts++}", extraDescript = extraDescript)
            val c = GhostController(
                ghost = ghost,
                shell = Shell(ghost.shellDir()!!),
                shioriFactory = { _, _ -> shiori },
                scope = scope,
                platform = RecordingPlatform(),
                player = ScriptPlayer(charWaitMs = 0),
                updateFetcher = fetcher,
                httpClient = FetcherHttpClient(fetcher),
                webSocketConnector = connector,
            )
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori, ghost.masterDir)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun ScriptedShiori.awaitCount(id: String, n: Int) = withTimeout(10_000) { while (count(id) < n) delay(10) }

    @Test
    fun webSocketOpenReceiveSendAndClose() {
        val incoming = LinkedBlockingQueue<WebSocketMessage>()
        val socket = FakeSocket(incoming)
        run(
            mapOf(
                "OnTest" to """\![execute,websocket,wss://e.test/chat,--async=OnChat]\e""",
                "OnSend" to """\![send,websocket,wss://e.test/chat,こんにちは,またね]\![send,websocket-binary,wss://e.test/chat,AAE=]\e""",
                "OnBye" to """\![close,websocket,wss://e.test/chat]\e""",
            ),
            connector = { _, _, _ -> socket },
        ) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            assertEquals(listOf("OnChat", "wss://e.test/chat", "200"), shiori.awaitEvent("OnChatOpen"))
            incoming += WebSocketMessage(false, "一行\n二行".toByteArray())
            assertEquals(listOf("OnChat", "wss://e.test/chat", "1", "一行\u0001二行"), shiori.awaitEvent("OnChat"))
            c.raise("OnSend", emptyList())
            assertEquals("こんにちは\r\nまたね", socket.sent.poll(5, TimeUnit.SECONDS))
            assertEquals("bin:AAE=", socket.sent.poll(5, TimeUnit.SECONDS))
            c.raise("OnBye", emptyList())
            assertEquals(listOf("OnChat", "wss://e.test/chat", "1000"), shiori.awaitEvent("OnChatClose"))
        }
    }

    @Test
    fun webSocketFailureAndCancel() {
        run(mapOf("OnTest" to """\![execute,websocket,ws://e.test/x]\e""")) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            assertEquals(listOf("", "ws://e.test/x", "no"), shiori.awaitEvent("OnExecuteWebSocketFailure"))
        }
        val incoming = LinkedBlockingQueue<WebSocketMessage>()
        run(
            mapOf("OnTest" to """\![execute,websocket,ws://e.test/y,--async=s]\e""", "OnStop" to """\![cancel,websocket,ws://e.test/y]\e"""),
            connector = { _, _, _ -> FakeSocket(incoming) },
        ) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            shiori.awaitEvent("OnExecuteWebSocketOpen")
            c.raise("OnStop", emptyList())
            assertEquals(listOf("s", "ws://e.test/y", "userbreak"), shiori.awaitEvent("OnExecuteWebSocketClose"))
        }
    }

    @Test
    fun realWebSocketHandshakeAndFrames() {
        // 1回だけ受けて、届いた文字を大文字にして返すサーバー
        val server = ServerSocket(0)
        val serverThread = thread {
            server.accept().use { s ->
                val input = s.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                val key = Regex("Sec-WebSocket-Key: (.+)\r\n").find(head)!!.groupValues[1].trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                val out = s.getOutputStream()
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                // クライアントからのフレーム（マスク付き）を読む
                val b0 = input.read()
                val len = input.read() and 0x7F
                val mask = ByteArray(4).also { input.read(it) }
                val payload = ByteArray(len).also { input.read(it) }
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                assertEquals(0x81, b0)
                val reply = String(payload).uppercase().toByteArray()
                out.write(byteArrayOf(0x81.toByte(), reply.size.toByte()) + reply)
                // 閉じる
                out.write(byteArrayOf(0x88.toByte(), 2, 0x03, 0xE8.toByte()))
                out.flush()
                runCatching { input.read() }
            }
        }
        val session = SocketWebSocketConnector("test").open("ws://127.0.0.1:${server.localPort}/echo", emptyList(), null)
        session.sendText("hello")
        assertEquals("HELLO", String(session.read()!!.data))
        assertEquals(null, session.read())
        assertEquals(1000, session.closeCode)
        serverThread.join(5000)
        server.close()
    }

    @Test
    fun nslookupAndPingReport() {
        run(mapOf("OnTest" to """\![execute,nslookup,--host=localhost,--event=OnLook]\![execute,ping,--host=127.0.0.1,--count=2,--timeout=300,--event=p]\e""")) { c, shiori, _ ->
            c.raise("OnTest", emptyList())
            val look = shiori.awaitEvent("OnLook")
            assertEquals(listOf("OnLook", "localhost", "lookup"), look.take(3))
            assertTrue(look[3].contains("127.0.0.1") || look[3].contains(":"))
            val ping = shiori.awaitEvent("OnPingComplete", timeoutMs = 10_000)
            assertEquals("p", ping[0])
            // 送った数・成功・失敗
            val (_, sent, ok, failed) = ping[1].split('\u0001')
            assertEquals("2", sent)
            assertEquals(2, ok.toInt() + failed.toInt())
            // Reference2 から1回ずつ
            assertEquals(4, ping.size)
            assertEquals(1, shiori.count("OnPingProgress"))
        }
    }

    @Test
    fun fileWatchReportsCreateAndModify() {
        run(mapOf("OnTest" to """\![execute,filewatch,watched.txt,--async=OnWatch,--debounce=100]\e""", "OnOut" to """\![execute,filewatch,../../../x.txt]\e""")) { c, shiori, master ->
            c.raise("OnTest", emptyList())
            delay(500)
            val file = File(master, "watched.txt")
            file.writeText("a")
            assertEquals(listOf("OnWatch", "watched.txt", "created", file.absolutePath), shiori.awaitEvent("OnWatch"))
            delay(300)
            file.writeText("abc")
            shiori.awaitCount("OnWatch", 2)
            assertEquals("modified", shiori.events.last { it.first == "OnWatch" }.second[2])
            // ゴーストのフォルダの外は見張らない
            c.raise("OnOut", emptyList())
            assertEquals("notauthorized", shiori.awaitEvent("OnExecuteFileWatchFailure")[2])
        }
    }

    @Test
    fun archivesStayInsideTheGhost() {
        run(
            mapOf(
                "OnZip" to """\![execute,compressarchive,out/a.zip,data]\0圧縮した\e""",
                "OnUnzip" to """\![execute,extractarchive,out/a.zip,restored,--event=OnDone]\e""",
                "OnSlip" to """\![execute,extractarchive,slip.zip,restored,--event=bad]\e""",
            ),
        ) { c, shiori, master ->
            File(master, "data/sub").mkdirs()
            File(master, "data/a.txt").writeText("A")
            File(master, "data/sub/b.txt").writeText("BB")
            c.raise("OnZip", emptyList())
            // --event が無ければ、終わるまで待ってから続きを話す
            val done = shiori.awaitEvent("OnCompressArchiveComplete")
            assertEquals(listOf("", "2"), done.take(2))
            c.raise("OnUnzip", emptyList())
            assertEquals("2", shiori.awaitEvent("OnDone")[1])
            assertEquals("BB", File(master, "restored/sub/b.txt").readText())
            // ../ で外に出ようとする ZIP は展開しない
            ZipOutputStream(File(master, "slip.zip").outputStream()).use { z ->
                z.putNextEntry(ZipEntry("../../evil.txt"))
                z.write("x".toByteArray())
                z.closeEntry()
            }
            c.raise("OnSlip", emptyList())
            assertEquals("invalid path", shiori.awaitEvent("OnExtractArchiveFailure")[1])
            assertTrue(!File(master.parentFile.parentFile, "evil.txt").exists())
        }
    }

    @Test
    fun sntpMeasuresTheOffset() {
        // 端末より 2 秒進んだ時刻を返すサーバー
        val socket = DatagramSocket(0)
        val t = thread {
            val buf = ByteArray(48)
            val p = DatagramPacket(buf, 48)
            socket.receive(p)
            val reply = ByteArray(48)
            reply[0] = 0x1C
            val now = System.currentTimeMillis() + 2000
            val seconds = now / 1000 + 2_208_988_800L
            val fraction = (now % 1000) * 0x1_0000_0000L / 1000
            for (off in listOf(32, 40)) {
                for (i in 0..3) reply[off + i] = (seconds shr (24 - 8 * i)).toByte()
                for (i in 0..3) reply[off + 4 + i] = (fraction shr (24 - 8 * i)).toByte()
            }
            socket.send(DatagramPacket(reply, 48, p.address, p.port))
        }
        val r = NetTools.sntp("127.0.0.1", 3000, socket.localPort)
        t.join(3000)
        socket.close()
        assertTrue("offset ${r.offsetMs}", r.offsetMs in 1500..2500)
    }

    @Test
    fun checkOnlyAndShellUpdates() {
        // 一覧に1つだけファイルがある更新サーバー
        val list = "a.txt\u0001d41d8cd98f00b204e9800998ecf8427e\u0001size=0\r\n"
        val fetcher = HttpFetcher { url, sink, _ ->
            if (url.endsWith("updates2.dau")) {
                sink.write(list.toByteArray())
                FetchResult.Ok
            } else {
                FetchResult.HttpError(404)
            }
        }
        run(
            mapOf("OnCheck" to """\![updatebymyself,checkonly]\e""", "OnShell" to """\![update,shell]\e"""),
            fetcher = fetcher,
            extraDescript = "homeurl,https://e.test/ghost/\r\n",
        ) { c, shiori, _ ->
            c.raise("OnCheck", emptyList())
            assertEquals(listOf("1", "a.txt", "", "ghost", "script"), shiori.awaitEvent("OnUpdateCheckComplete"))
            assertEquals(listOf("ghost\u0001OK\u00011"), shiori.awaitEvent("OnUpdateCheckResult"))
            // シェルには homeurl が無い
            c.raise("OnShell", emptyList())
            assertEquals("none", shiori.awaitEvent("OnUpdateOtherFailure")[0])
            assertEquals(listOf("shell\u0001NG\u0001none"), shiori.awaitEvent("OnUpdateResult"))
        }
    }
}
