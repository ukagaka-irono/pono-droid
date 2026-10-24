package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 端末の休止と復帰（OnSysSuspend / OnSysResume）とネットワークの切り替わり（OnNetworkStatusChange） */
class SystemEventsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun awaitCount(shiori: ScriptedShiori, id: String, n: Int) =
        withTimeout(5000) { while (shiori.count(id) < n) delay(10) }

    private val wifi =NetworkStatus(online = true, addresses = listOf("192.168.1.5", "fe80::1"), type = "wifi", maxMbps = 12.5, effectiveMbps = 12.5, metering = "unrestricted")

    @Test
    fun networkStatusIsNotifiedAtBootAndSentWhenItChanges() = runBlocking<Unit> {
        val shiori = ScriptedShiori(emptyMap())
        val platform = RecordingPlatform().apply { network = wifi }
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope, platform)
            c.start()
            // 起動直後は NOTIFY（SSP と同じ並び。アドレスは 0x01 区切り、速さは MBps）
            assertEquals(listOf("online", "192.168.1.5\u0001fe80::1", "wifi", "12.5", "12.5", "unrestricted"), shiori.awaitEvent("OnNetworkStatusChange"))
            shiori.awaitEvent("OnFirstBoot")

            // 同じ状態や、速さが揺れただけなら送らない
            c.onNetworkChanged(wifi)
            c.onNetworkChanged(wifi.copy(maxMbps = 3.0, effectiveMbps = 3.0))
            delay(300)
            assertEquals(1, shiori.count("OnNetworkStatusChange"))

            // モバイル回線に替わった（SHIORI のスレッドで届くので、数が増えるのを待ってから見る）
            c.onNetworkChanged(NetworkStatus(online = true, addresses = listOf("10.0.0.2"), type = "cellular", metering = "fixed"))
            awaitCount(shiori, "OnNetworkStatusChange", 2)
            assertEquals(listOf("online", "10.0.0.2", "cellular", "0", "0", "fixed"), shiori.awaitEvent("OnNetworkStatusChange"))

            // 切れた
            c.onNetworkChanged(NetworkStatus(online = false, type = "none"))
            awaitCount(shiori, "OnNetworkStatusChange", 3)
            assertEquals(listOf("offline", "", "none", "0", "0", "unknown"), shiori.awaitEvent("OnNetworkStatusChange"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun suspendAndResumeAreSentOncePerSleep() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnSysResume" to """\0おかえり"""))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            shiori.awaitEvent("OnFirstBoot")

            // 休んでいないのに戻ってきても送らない
            c.onSystemResume()
            delay(200)
            assertEquals(0, shiori.count("OnSysResume"))

            // 画面が消えたら1回だけ OnSysSuspend
            c.onSystemSuspend()
            c.onSystemSuspend()
            shiori.awaitEvent("OnSysSuspend")
            delay(200)
            assertEquals(1, shiori.count("OnSysSuspend"))

            // 戻ってきたら1回だけ OnSysResume（Reference0 は normal）
            c.onSystemResume()
            c.onSystemResume()
            assertEquals(listOf("normal"), shiori.awaitEvent("OnSysResume"))
            delay(200)
            assertEquals(1, shiori.count("OnSysResume"))
        } finally {
            scope.cancel()
        }
    }
}
