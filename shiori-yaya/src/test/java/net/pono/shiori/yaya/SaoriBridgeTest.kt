package net.pono.shiori.yaya

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SaoriBridgeTest {
    @Test fun capturedHandlerRemainsBoundAfterRequestReturns() {
        val context = SaoriBridge.with({ _, module -> "first:$module".toByteArray() }) { SaoriBridge.capture() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            SaoriBridge.with({ _, _ -> "second".toByteArray() }) {
                val reply = executor.submit<String> {
                    SaoriBridge.setContext(context)
                    try { SaoriBridge.request(byteArrayOf(), "internal_test")!!.toString(Charsets.UTF_8) }
                    finally { SaoriBridge.setContext(null) }
                }
                assertEquals("first:internal_test", reply.get(5, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
        assertNull(SaoriBridge.request(byteArrayOf(), null))
    }

    @Test fun capturedMissingHandlerDoesNotUseAnotherGhost() {
        val context = SaoriBridge.capture()
        SaoriBridge.with({ _, _ -> "second".toByteArray() }) {
            assertNull(context.request(byteArrayOf(), null))
        }
    }
}
