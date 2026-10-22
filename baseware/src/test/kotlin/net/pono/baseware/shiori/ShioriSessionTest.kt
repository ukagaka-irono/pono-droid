package net.pono.baseware.shiori

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShioriSessionTest {
    /** request が [release] まで戻ってこない SHIORI（無限ループする辞書の代わり） */
    private class HangingShiori : Shiori {
        val release = CountDownLatch(1)
        @Volatile var aborted = false

        override fun load(dir: File) = true

        override fun request(request: ByteArray): ByteArray {
            release.await(10, TimeUnit.SECONDS)
            return "SHIORI/3.0 200 OK\r\nValue: late\r\n\r\n".toByteArray()
        }

        override fun unload() {}

        override fun abort() {
            aborted = true
            release.countDown()
        }
    }

    @Test
    fun abortsWhenTheShioriDoesNotRespond() = runBlocking {
        val shiori = HangingShiori()
        val session = ShioriSession(shiori, Charsets.UTF_8, requestTimeoutMs = 200)
        try {
            assertTrue(session.load(File(".")))
            val error = runCatching { session.get("OnBoot") }.exceptionOrNull()
            assertTrue("$error", error is ShioriTimeoutException)
            assertTrue(shiori.aborted)
        } finally {
            session.unload()
        }
    }

    @Test
    fun answersWithinTheTimeLimitComeBackNormally() = runBlocking {
        val shiori = object : Shiori {
            override fun load(dir: File) = true
            override fun request(request: ByteArray) = "SHIORI/3.0 200 OK\r\nValue: hi\r\n\r\n".toByteArray()
            override fun unload() {}
        }
        val session = ShioriSession(shiori, Charsets.UTF_8, requestTimeoutMs = 5000)
        try {
            assertTrue(session.load(File(".")))
            assertEquals("hi", session.get("OnBoot")?.value)
        } finally {
            session.unload()
        }
    }
}
