package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

/** ロック・画面の電源・スクリーンセーバー・ダークモード・言語・フォント・更新・負荷・祝日・通信の遅れ */
class DeviceStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(
        replies: Map<String, String> = emptyMap(),
        platform: RecordingPlatform = RecordingPlatform(),
        fetcher: HttpFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
        block: suspend (GhostController, ScriptedShiori) -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(extraDescript = "homeurl,https://e.test/\r\n"), shiori, scope, platform, fetcher)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori)
        } finally {
            scope.cancel()
        }
    }

    private fun ScriptedShiori.all(id: String) = synchronized(events) { events.filter { it.first == id }.map { it.second } }

    private suspend fun ScriptedShiori.awaitCount(id: String, n: Int) = withTimeout(5000) { while (count(id) < n) delay(10) }

    @Test
    fun japaneseHolidays() {
        assertEquals("元日", JapaneseHolidays.name(LocalDate.of(2026, 1, 1)))
        assertEquals("成人の日", JapaneseHolidays.name(LocalDate.of(2026, 1, 12)))
        assertEquals("春分の日", JapaneseHolidays.name(LocalDate.of(2026, 3, 20)))
        assertEquals("振替休日", JapaneseHolidays.name(LocalDate.of(2026, 5, 6)))
        assertEquals("敬老の日", JapaneseHolidays.name(LocalDate.of(2026, 9, 21)))
        // 敬老の日と秋分の日にはさまれた日
        assertEquals("国民の休日", JapaneseHolidays.name(LocalDate.of(2026, 9, 22)))
        assertEquals("秋分の日", JapaneseHolidays.name(LocalDate.of(2026, 9, 23)))
        assertEquals("スポーツの日", JapaneseHolidays.name(LocalDate.of(2026, 10, 12)))
        assertEquals("スポーツの日", JapaneseHolidays.name(LocalDate.of(2021, 7, 23)))
        assertEquals("天皇の即位の日", JapaneseHolidays.name(LocalDate.of(2019, 5, 1)))
        assertEquals("国民の休日", JapaneseHolidays.name(LocalDate.of(2019, 4, 30)))
        assertNull(JapaneseHolidays.name(LocalDate.of(2026, 10, 24)))
        assertNull(JapaneseHolidays.name(LocalDate.of(2026, 2, 24)))
    }

    @Test
    fun sessionDisplayAndScreenSaver() = run { c, shiori ->
        c.onSessionLock()
        c.onSessionLock()
        c.onDisplayPower(false)
        c.onSessionUnlock()
        c.onSessionUnlock()
        c.onDisplayPower(true)
        c.onScreenSaver(true)
        c.onScreenSaver(false)
        shiori.awaitEvent("OnScreenSaverEnd")
        shiori.awaitCount("OnDisplayPowerStatus", 2)
        // ロックと解除は1回ずつ
        assertEquals(1, shiori.count("OnSessionLock"))
        assertEquals(1, shiori.count("OnSessionUnlock"))
        assertEquals(listOf(listOf("0"), listOf("1")), shiori.all("OnDisplayPowerStatus"))
        assertEquals("Android", shiori.awaitEvent("OnScreenSaverStart")[0])
    }

    @Test
    fun systemInfoAtBootAndOnChange() {
        val platform = RecordingPlatform().apply {
            system = SystemInfo(
                darkTheme = false, languageName = "Japanese", languageId = 1041, fonts = listOf("NotoSansCJK-Regular", "Roboto-Regular"),
                osUpdated = 0L, osUpdateTitle = "Android 16", country = "XX",
            )
        }
        run(platform = platform) { c, shiori ->
            assertEquals(listOf("0", "0"), shiori.awaitEvent("OnDarkTheme"))
            assertEquals(listOf("Japanese", "1041", "", ""), shiori.awaitEvent("OnLanguageChange"))
            assertEquals(listOf("NotoSansCJK-Regular", "Roboto-Regular"), shiori.awaitEvent("OnNotifyFontInfo"))
            val update = shiori.awaitEvent("OnOSUpdateInfo")
            assertEquals(3, update.size)
            assertEquals("success", update[2].split('\u0001')[0])
            // 祝日は日本のときだけ
            assertEquals(0, shiori.count("OnNotifyHolidayInfo"))
            c.onSystemInfoChanged(platform.system!!.copy(darkTheme = true))
            shiori.awaitCount("OnDarkTheme", 2)
            assertEquals(listOf("1", "1"), shiori.all("OnDarkTheme").last())
            // 変わっていないものは送らない
            assertEquals(1, shiori.count("OnLanguageChange"))
        }
    }

    @Test
    fun loadHighAfterAWhileAndLowBelowSixty() = run { c, shiori ->
        c.onLoad(90, 50)
        c.onLoad(95, 50)
        delay(100)
        assertEquals(0, shiori.count("OnCPULoadHigh"))
        c.onLoad(85, 50)
        assertEquals(listOf("85"), shiori.awaitEvent("OnCPULoadHigh"))
        c.onLoad(70, 50)
        delay(100)
        assertEquals(0, shiori.count("OnCPULoadLow"))
        c.onLoad(40, null)
        assertEquals(listOf("40"), shiori.awaitEvent("OnCPULoadLow"))
        // CPU が分からなくても、メモリは見る
        repeat(3) { c.onLoad(null, 88) }
        assertEquals(listOf("88"), shiori.awaitEvent("OnMemoryLoadHigh"))
    }

    @Test
    fun networkHeavyWhenTheServerIsSlow() = runBlocking<Unit> {
        val shiori = ScriptedShiori(emptyMap())
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost(extraDescript = "homeurl,https://e.test/\r\n")
            // 最初の通信だけ、OnNetworkHeavy が出るまで答えない
            val slow = java.util.concurrent.atomic.AtomicBoolean(true)
            val c = GhostController(
                ghost = ghost,
                shell = Shell(ghost.shellDir()!!),
                shioriFactory = { _, _ -> shiori },
                scope = scope,
                platform = RecordingPlatform(),
                player = ScriptPlayer(charWaitMs = 0),
                updateFetcher = HttpFetcher { _, _, _ ->
                    if (slow.getAndSet(false)) Thread.sleep(GhostController.NETWORK_HEAVY_MS + 500)
                    FetchResult.HttpError(404)
                },
            )
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            c.networkUpdate()
            val refs = shiori.awaitEvent("OnNetworkHeavy", timeoutMs = GhostController.NETWORK_HEAVY_MS + 5000)
            assertEquals((GhostController.NETWORK_TIMEOUT_MS / 1000).toString(), refs[0])
            assertEquals((GhostController.NETWORK_HEAVY_MS / 1000).toString(), refs[1])
            shiori.awaitEvent("OnUpdateFailure", timeoutMs = 10_000)
            assertEquals(1, shiori.count("OnNetworkHeavy"))
        } finally {
            scope.cancel()
        }
    }
}
