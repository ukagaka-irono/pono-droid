package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** ゴースト用の予定表（schedule-add / delete / get）、今日の予定、知らせる時刻 */
class ScheduleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val today: LocalDate = LocalDate.now()
    private fun ymd(d: LocalDate) = "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)

    @Test
    fun storeKeepsEntriesAndNotifyMinutes() {
        val s = GhostSchedule(File(tmp.root, "pono/schedule.ics"), "テスト")
        s.add(GhostSchedule.Entry("a", "打ち合わせ, 大事", "20261001T100000", "20261001T110000", "FREQ=WEEKLY;BYDAY=TH", notifyMinutes = 10))
        s.add(GhostSchedule.Entry("b", "記念日", "20261224", rrule = "FREQ=YEARLY"))
        s.add(GhostSchedule.Entry("a", "打ち合わせ（変更）", "20261001T100000"))
        val events = s.calendar().events
        assertEquals(listOf("a" to "打ち合わせ（変更）", "b" to "記念日"), events.map { it.uid to it.summary }.sortedBy { it.first })
        assertEquals(emptyMap<String, Int>(), s.notifyMinutes())
        assertTrue(s.delete("b"))
        assertTrue(!s.delete("b"))
        assertEquals("テスト", s.calendar().name)
    }

    @Test
    fun dueNotificationsHonourTheNotifyMinutes() {
        val s = GhostSchedule(File(tmp.root, "s.ics"), "x")
        s.add(GhostSchedule.Entry("w", "週の会", "20261001T100000", rrule = "FREQ=WEEKLY;BYDAY=TH", notifyMinutes = 10))
        s.add(GhostSchedule.Entry("d", "5分前", "20261008T120000"))
        assertEquals(listOf("週の会"), s.dueNotifications(LocalDateTime.of(2026, 10, 8, 9, 50)).map { it.first.summary })
        assertEquals(listOf("5分前"), s.dueNotifications(LocalDateTime.of(2026, 10, 8, 11, 55)).map { it.first.summary })
        assertTrue(s.dueNotifications(LocalDateTime.of(2026, 10, 8, 11, 56)).isEmpty())
    }

    @Test
    fun dateFormatsAndUids() {
        assertEquals("20261224", GhostSchedule.normalizeDate("20261224"))
        assertEquals("20261001T100000", GhostSchedule.normalizeDate("20261001T1000"))
        assertEquals(null, GhostSchedule.normalizeDate("2026-10-01"))
        assertEquals(null, GhostSchedule.normalizeDate("20261341"))
        assertTrue(GhostSchedule.validUid("myghost-meeting_1"))
        assertTrue(!GhostSchedule.validUid("bad\r\nUID"))
    }

    private class CalendarPlatform(val inner: RecordingPlatform = RecordingPlatform(), val events: List<DeviceEvent>) : GhostPlatform by inner {
        override fun deviceSchedule(day: LocalDate) = events.filter { it.start.toLocalDate() == day }
    }

    private fun run(script: String, events: List<DeviceEvent> = emptyList(), block: suspend (GhostController, ScriptedShiori) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to script))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope, CalendarPlatform(events = events))
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun addDeleteGetAndTodayNotify() = run(
        """\0\![execute,schedule-add,--uid=t1,--summary=今日の会,--start=${ymd(today)}T2300,--end=${ymd(today)}T2330,--event=OnAdded]""" +
            """\![execute,schedule-add,--summary=日付なし]\![execute,schedule-delete,nope]""" +
            """\![execute,schedule-get,--expand=2,--event=OnGot]\e""",
        events = listOf(DeviceEvent("歯医者", "", today.atTime(9, 0), today.atTime(9, 30), false)),
    ) { _, shiori ->
        assertEquals(listOf("add", "t1"), shiori.awaitEvent("OnAdded"))
        withTimeout(5000) { while (shiori.count("OnExecuteScheduleFailure") < 2) delay(10) }
        val failures = synchronized(shiori.events) { shiori.events.filter { it.first == "OnExecuteScheduleFailure" }.map { it.second } }
        assertEquals(setOf(listOf("add", "invalid", ""), listOf("delete", "notfound", "nope")), failures.toSet())
        val got = shiori.awaitEvent("OnGot")
        assertEquals(2, got.size)
        assertTrue(got[1].startsWith("t1\u0001"))
        // 起動時の NOTIFY は端末の予定だけ、足したあとは両方（ゴーストのものが先）
        withTimeout(5000) { while (shiori.count("OnScheduleTodayNotify") < 2) delay(10) }
        val list = shiori.awaitEvent("OnScheduleTodayNotify")
        assertEquals(2, list.size)
        val y = today.year
        val m = today.monthValue
        val d = today.dayOfMonth
        assertEquals("ghost\u0001今日の会\u0001\u0001\u0001$y,$m,$d,23,0,0\u0001$y,$m,$d,23,30,0\u0001", list[0])
        assertEquals("user\u0001歯医者\u0001\u0001\u0001$y,$m,$d,9,0,0\u0001$y,$m,$d,9,30,0\u0001", list[1])
    }
}
