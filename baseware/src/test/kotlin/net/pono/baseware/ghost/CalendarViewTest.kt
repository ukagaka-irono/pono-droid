package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class CalendarViewTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parsesAllDayAndTimedEventsWithoutEvaluatingScript() {
        val row = CalendarEntry.parse("event\u0001Title\u0001Detail\u0001\\e\u00012030,1,2,,,\u0001\u0001")!!
        assertEquals(LocalDate.of(2030, 1, 2), row.start.date)
        assertNull(row.start.time)
        assertNull(row.end)
        assertEquals(listOf("event", "Title", "Detail", "\\e"), row.references)
        assertNull(CalendarEntry.parse("event\u0001Title\u0001Detail\u0001\u00012030,2,30,,,\u0001\u0001"))
        assertNull(CalendarEntry.parse("incomplete"))
    }

    @Test fun opensCalendarReadsRecurringAndDeviceEntriesAndSendsReadEvent() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        val selected = LocalDate.of(2030, 1, 2)
        GhostSchedule(File(ghost.dir, "pono/schedule.ics"), ghost.name).add(GhostSchedule.Entry(
            uid = "daily", summary = "Daily", start = "20300101", rrule = "FREQ=DAILY", type = "event", description = "Details",
            script = "\\![raise,OnUnwanted]",
        ))
        val opened = AtomicInteger()
        val platform = object : GhostPlatform by RecordingPlatform() {
            override fun openCalendar(): Boolean { opened.incrementAndGet(); return true }
            override fun deviceSchedule(day: LocalDate): List<DeviceEvent> = if (day == selected) {
                listOf(DeviceEvent("Device", "Appointment", day.atTime(10, 0), day.atTime(11, 0), false))
            } else emptyList()
        }
        val shiori = ScriptedShiori(mapOf("OnCalendar" to "\\![open,calendar]\\e", "OnScheduleRead" to "\\e"))
        val scope = testScope()
        val c = testController(ghost, shiori, scope, platform)
        try {
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            c.raise("OnCalendar", emptyList())
            withTimeout(5000) { while (opened.get() == 0) delay(10) }
            val entries = c.calendarEntries(selected)
            assertEquals(listOf("Daily", "Device"), entries.map { it.caption })
            assertEquals(selected, entries.first().start.date)
            assertEquals(11, entries.last().end?.time?.hour)
            c.onScheduleRead(entries.first())
            assertEquals(listOf("event", "Daily", "Details", "\\![raise,OnUnwanted]"), shiori.awaitEvent("OnScheduleRead"))
            assertEquals(0, shiori.count("OnUnwanted"))
            assertEquals(listOf("Daily"), c.calendarEntries(selected.plusDays(1)).map { it.caption })
        } finally { c.dispose().join(); scope.cancel() }
    }
}
