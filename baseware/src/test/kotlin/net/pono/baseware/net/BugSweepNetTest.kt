package net.pono.baseware.net

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BugSweepNetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 捕まえられなかった例外を [errors] に集めるサーバー（アプリでは lifecycleScope なので、これがアプリを落とす） */
    private fun withServer(block: (port: Int, errors: List<Throwable>) -> Unit) {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> errors += e })
        val server = SstpServer(scope, { SstpResponse(200, data = "ok") }, port = 0)
        try {
            assertTrue(server.start())
            block(server.localPort, errors)
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun negativeContentLengthDoesNotKillTheServer() = withServer { port, errors ->
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("POST /api/sstp/v1 HTTP/1.1\r\nContent-Length: -1\r\nConnection: close\r\n\r\n".toByteArray())
            runCatching { s.getInputStream().readBytes() }
        }
        Thread.sleep(300)
        assertEquals(emptyList<Throwable>(), errors.toList())
        assertTrue(SstpServer.send(port, "NOTIFY SSTP/1.1\r\nSender: t\r\nEvent: OnTest\r\n\r\n").startsWith("SSTP/1.1 200"))
    }

    @Test
    fun resetConnectionDoesNotKillTheServer() = withServer { port, errors ->
        val s = Socket(InetAddress.getLoopbackAddress(), port)
        s.setSoLinger(true, 0)
        s.getOutputStream().write("NOTIFY SSTP/1.1\r\n".toByteArray())
        s.getOutputStream().flush()
        Thread.sleep(200)
        // SO_LINGER 0 で閉じると RST になる（サーバーの read は Connection reset）
        s.close()
        Thread.sleep(500)
        assertEquals(emptyList<Throwable>(), errors.toList())
    }

    @Test
    fun outOfRangePortIsAnIOException() {
        try {
            SocketWebSocketConnector("test").open("ws://127.0.0.1:99999/", emptyList(), null)
            fail("つながってしまった")
        } catch (e: IOException) {
            // 約束どおり IOException（今は IllegalArgumentException がそのまま出る）
        }
    }

    @Test
    fun cp932NamesAreExtractedWithTheirJapaneseNames() {
        val zip = File(tmp.root, "sjis.zip")
        // windows-31j で書くと UTF-8 の印（bit 11）は付かない（日本の Windows で作った ZIP と同じ）
        ZipOutputStream(zip.outputStream(), charset("windows-31j")).use { z ->
            z.putNextEntry(ZipEntry("表示.txt"))
            z.write("x".toByteArray())
            z.closeEntry()
        }
        val dest = tmp.newFolder("out")
        NetTools.extract(zip, dest)
        assertTrue(dest.walkTopDown().joinToString { it.name }, File(dest, "表示.txt").isFile)
    }
}
