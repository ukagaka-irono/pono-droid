package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** \![execute,install,url,URL,種別] の ical（予定表の購読）と ssf（トーク） */
class InstallUrlTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun server(files: Map<String, ByteArray>) = HttpFetcher { url, sink, _ ->
        val b = files[url] ?: return@HttpFetcher FetchResult.HttpError(404)
        sink.write(b)
        FetchResult.Ok
    }

    private fun run(replies: Map<String, String>, files: Map<String, ByteArray>, block: suspend (GhostController, ScriptedShiori, Ghost) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            val c = testController(ghost, shiori, scope, fetcher = server(files))
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori, ghost)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun icalIsSubscribedAndJoinsTodaysSchedule() {
        val today = java.time.LocalDate.now()
        val day = "%04d%02d%02d".format(today.year, today.monthValue, today.dayOfMonth)
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:a1\r\nDTSTART;VALUE=DATE:$day\r\nSUMMARY:購読の予定\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        run(mapOf("OnTest" to """\![execute,install,url,https://cal.test/feed.ics,ical]\e"""), mapOf("https://cal.test/feed.ics" to ics.toByteArray())) { c, shiori, ghost ->
            c.raise("OnTest", emptyList())
            withTimeout(5000) {
                while (shiori.all("OnScheduleTodayNotify").none { refs -> refs.any { "購読の予定" in it } }) delay(10)
            }
            assertTrue(File(ghost.dir, "pono/ical/urls.txt").readText().contains("https://cal.test/feed.ics"))
        }
    }

    @Test
    fun ssfIsPlayedAsAnOutsideScript() {
        run(
            mapOf("OnTest" to """\![execute,install,url,https://talk.test/a.ssf]\e"""),
            mapOf("https://talk.test/a.ssf" to """\0ssfのトーク\e""".toByteArray()),
        ) { c, _, _ ->
            c.raise("OnTest", emptyList())
            withTimeout(5000) {
                c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.Text && "ssfのトーク" in it.text } == true }
            }
        }
    }

    private fun ScriptedShiori.all(id: String) = synchronized(events) { events.filter { it.first == id }.map { it.second } }
}
