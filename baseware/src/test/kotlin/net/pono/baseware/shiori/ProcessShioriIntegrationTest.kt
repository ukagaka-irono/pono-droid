package net.pono.baseware.shiori

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Windows 上で実際の DLL ホストと往復する。CI の Windows ジョブでは両方のビット数を必ず実行する。 */
class ProcessShioriIntegrationTest {
    @Test fun x86() = check("X86", WindowsModule.Architecture.X86)
    @Test fun x64() = check("X64", WindowsModule.Architecture.X64)

    private fun check(suffix: String, architecture: WindowsModule.Architecture) = runBlocking<Unit> {
        val hostPath = System.getenv("PONO_WINDOWS_HOST_$suffix")
        val dllPath = System.getenv("PONO_WINDOWS_DLL_$suffix")
        if (System.getenv("PONO_REQUIRE_WINDOWS_HOST") == "1") {
            check(hostPath != null && dllPath != null) { "Required Windows host fixture $suffix is not configured" }
        }
        assumeTrue("Windows host fixture is not configured", hostPath != null && dllPath != null)
        val module = requireNotNull(WindowsModule.inspect(File(dllPath!!)))
        assertEquals(architecture, module.architecture)
        val host = File(hostPath!!)
        assertTrue(host.isFile)
        val shiori = ProcessShiori(module, listOf(host.absolutePath))
        val crashed = CountDownLatch(1)
        shiori.setOnCrash { crashed.countDown() }
        try {
            assertTrue(shiori.load(module.file.parentFile))
            val request = byteArrayOf(0, 0x82.toByte(), 0xa0.toByte(), 13, 10, 0xff.toByte())
            assertTrue(request.contentEquals(shiori.request(request)))
            assertNull(shiori.request("null".toByteArray()))
            shiori.unload()
            assertFalse(crashed.await(100, TimeUnit.MILLISECONDS))
        } finally { shiori.abort() }

        val hung = ProcessShiori(module, listOf(host.absolutePath))
        val aborted = CountDownLatch(1)
        hung.setOnCrash { aborted.countDown() }
        val session = ShioriSession(hung, Charsets.UTF_8, requestTimeoutMs = 100, loadTimeoutMs = 5000)
        try {
            assertTrue(session.load(module.file.parentFile))
            val failure = runCatching { session.get("OnHang") }.exceptionOrNull()
            assertTrue("hung DLL must time out, got $failure", failure is ShioriTimeoutException)
            assertTrue(aborted.await(5, TimeUnit.SECONDS))
        } finally { session.unload() }
    }
}
