package net.pono.baseware.ghost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.SstpResponse
import net.pono.baseware.net.SstpServer
import net.pono.baseware.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SHIORI が内蔵 SAORI（SAORI over DirectSSTP）を呼ぶ道と、ソケットの SSTP では受けないこと */
class InternalSaoriBridgeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** OnCall で、本物の SHIORI と同じく request の途中に内蔵 SAORI を呼ぶ */
    private class CallingShiori(val inner: ScriptedShiori) : Shiori by inner {
        var handler: ((ByteArray, String?) -> ByteArray)? = null
        val answers = mutableListOf<String>()

        override fun setSaoriHandler(handler: (request: ByteArray, module: String?) -> ByteArray) {
            this.handler = handler
        }

        override fun request(request: ByteArray): ByteArray {
            if ("ID: OnCall\r\n" in String(request)) {
                val h = handler!!
                // YAYA は Module ヘッダを付けず、読んだ名前で。里々は Module ヘッダを付ける
                answers += String(h("EXECUTE SAORI/1.0\r\nCharset: UTF-8\r\nArgument0: Digest\r\nArgument1: sha256\r\nArgument2: abc\r\n\r\n".toByteArray(), "internal_crypto"))
                answers += String(
                    h(
                        "EXECUTE SAORI/1.0\r\nModule: internal_crypto\r\nCharset: UTF-8\r\nArgument0: Digest\r\nArgument1: sha256\r\nArgument2: abc\r\nArgument3: --async=OnHashDone\r\n\r\n".toByteArray(),
                        null,
                    ),
                )
            }
            return inner.request(request)
        }
    }

    @Test
    fun shioriCallsInternalModules() = runBlocking<Unit> {
        val shiori = CallingShiori(ScriptedShiori(emptyMap()))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            c.raise("OnCall", emptyList())
            val refs = shiori.inner.awaitEvent("OnHashDone")
            assertTrue(shiori.answers[0], shiori.answers[0].startsWith("SAORI/1.0 200 OK\r\n"))
            assertTrue(shiori.answers[0], "Result: ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\r\n" in shiori.answers[0])
            assertTrue(shiori.answers[1], shiori.answers[1].startsWith("SAORI/1.0 204 No Content\r\n"))
            assertEquals(listOf("OnHashDone", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "internal_crypto", "Digest"), refs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun socketSstpRefusesSaori() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = SstpServer(scope, { SstpResponse(200) }, port = 0)
        try {
            assertTrue(server.start())
            val reply = SstpServer.send(
                server.localPort,
                "EXECUTE SAORI/1.0\r\nModule: internal_crypto\r\nCharset: UTF-8\r\nArgument0: Digest\r\nArgument1: sha256\r\nArgument2: abc\r\n\r\n",
            )
            assertTrue(reply, reply.startsWith("SAORI/1.0 400 Bad Request\r\n"))
            assertTrue(reply, "ErrorDescription: direct sstp only\r\n" in reply)
        } finally {
            server.stop()
            scope.cancel()
        }
    }
}
