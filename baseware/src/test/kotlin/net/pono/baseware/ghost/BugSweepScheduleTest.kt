package net.pono.baseware.ghost

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

class BugSweepScheduleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun g1_scriptWithBackslashNSurvivesTheFile() {
        val s = GhostSchedule(File(tmp.root, "s.ics"), "x")
        val script = "\\h\\s[0]あさ\\nごはん\\e"
        s.add(GhostSchedule.Entry("a", "朝", "20261010T100000", script = script))
        assertEquals(script, s.extras()["a"]?.script)
    }

    @Test
    fun g2_eventCrossingMidnightIsNotNotifiedAgainTheNextDay() {
        val s = GhostSchedule(File(tmp.root, "n.ics"), "x")
        s.add(GhostSchedule.Entry("n", "夜", "20261010T230000", "20261011T010000"))
        assertEquals(1, s.dueNotifications(LocalDateTime.of(2026, 10, 10, 22, 55)).size)
        assertEquals(0, s.dueNotifications(LocalDateTime.of(2026, 10, 11, 22, 55)).size)
    }

    @Test
    fun g3_notifyMoreThanADayAheadFires() {
        val s = GhostSchedule(File(tmp.root, "d.ics"), "x")
        s.add(GhostSchedule.Entry("d", "遠い", "20261012T100000", notifyMinutes = 2 * 24 * 60))
        assertEquals(1, s.dueNotifications(LocalDateTime.of(2026, 10, 10, 10, 0)).size)
    }

    @Test
    fun g6_brokenProfileDoesNotStopTheGhost() {
        val dir = tmp.newFolder("g")
        File(dir, "ghost/master").mkdirs()
        File(dir, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,g\r\n")
        File(dir, "shell/master").mkdirs()
        File(dir, "pono").mkdirs()
        File(dir, "pono/profile.properties").writeText("shell=\\u30")
        assertEquals("master", Ghost(dir).shellDir()?.name)
    }

    @Test
    fun g7_hugeCharCountFromAStartRemovesTheRest() {
        val out = BalloonEdit.removeChars(listOf(BalloonItem.Text("abc")), Int.MAX_VALUE, 1)
        assertEquals(listOf<BalloonItem>(BalloonItem.Text("a")), out)
    }
}
