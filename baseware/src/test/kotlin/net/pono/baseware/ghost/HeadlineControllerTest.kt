package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.FeedParser
import net.pono.baseware.net.FetcherHttpClient
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HeadlineControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val feed = """<rss><channel><title>Site</title><link>https://example.test/</link><item><title>One</title><link>https://example.test/1</link></item><item><title>Two</title></item></channel></rss>""".toByteArray()

    @Test fun updatesNamedHeadlineAndReportsItsType() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        val repo = GhostRepository(tmp.newFolder("repository", "ghost"))
        val h = repo.headlines.addFeed("https://example.test/feed.xml", FeedParser.parseDocument(feed)!!)
        File(h.dir, "descript.txt").appendText("homeurl,https://example.test/update/\n")
        val fetcher = HttpFetcher { url, sink, _ ->
            when (url) {
                "https://example.test/update/updates2.dau" -> { sink.write("new.txt\u000149f68a5c8493ec2c0bf489821c21fc3b\u0001size=2\r\n".toByteArray()); FetchResult.Ok }
                "https://example.test/update/new.txt" -> { sink.write("hi".toByteArray()); FetchResult.Ok }
                else -> FetchResult.HttpError(404)
            }
        }
        val shiori = ScriptedShiori(mapOf("OnUpdateFeed" to "\\![updateother,--headline=Site]\\e"))
        val scope = testScope()
        val c = GhostController(ghost, Shell(ghost.shellDir()!!), { _, _ -> shiori }, scope, RecordingPlatform(), repository = repo,
            player = ScriptPlayer(charWaitMs = 0), updateFetcher = fetcher)
        try {
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            c.raise("OnUpdateFeed", emptyList())
            assertEquals(listOf("Site\u0001headline\u0001OK\u00011"), shiori.awaitEvent("OnUpdateResultEx"))
            assertEquals("hi", File(h.dir, "new.txt").readText())
            assertEquals("headline", shiori.awaitEvent("OnUpdateOtherBegin")[3])
        } finally { c.dispose().join(); scope.cancel() }
    }

    @Test fun rssEventsPropertiesAndUnchangedFallback() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        val repo = GhostRepository(tmp.newFolder("repository", "ghost"))
        val h = repo.headlines.addFeed("https://example.test/feed.xml", FeedParser.parseDocument(feed)!!)
        val shiori = ScriptedShiori(mapOf("OnRSSComplete" to "\\e"))
        val scope = testScope()
        val c = GhostController(ghost, Shell(ghost.shellDir()!!), { _, _ -> shiori }, scope, RecordingPlatform(), repository = repo,
            player = ScriptPlayer(charWaitMs = 0), httpClient = FetcherHttpClient { _, out, _ -> out.write(feed); FetchResult.Ok })
        try {
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            assertEquals("1", c.property("headlinelist.count"))
            assertEquals("Site", c.property("headlinelist.index(0).name"))
            c.headlineInstalled(listOf(NarInstaller.Installed(NarInstaller.Type.HEADLINE, h.name, h.dir)))
            assertTrue(c.executeHeadline("lastinstalled"))
            val complete = shiori.awaitEvent("OnRSSComplete")
            assertEquals(listOf("Site", "https://example.test/"), complete.take(2))
            assertEquals("One", complete[2].substringBefore('\u0001'))
            assertEquals(listOf("Site", h.url), shiori.awaitEvent("OnHeadlinesenseBegin"))
            assertEquals("1", c.property("history.headline.count"))
            assertEquals("Site", c.property("history.headline.index(0).name"))
            assertTrue(c.executeHeadline("random"))
            withTimeout(5000) { while (shiori.count("OnRSSComplete") < 2) delay(10) }
            assertEquals(listOf("no update"), shiori.awaitEvent("OnRSSComplete"))
            assertEquals(0, shiori.count("OnHeadlinesense.OnFind"))
        } finally { c.dispose().join(); scope.cancel(); repo.headlines.uninstall(h) }
    }

    @Test fun rssWithoutHandlersUsesHeadlinePhasesAndDoesNotExecuteFeedText() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        val repo = GhostRepository(tmp.newFolder("repository", "ghost"))
        val bytes = feed.toString(Charsets.UTF_8).replace("One", "\\![raise,OnInjected]%username").toByteArray()
        val h = repo.headlines.addFeed("https://example.test/feed.xml", FeedParser.parseDocument(bytes)!!)
        val shiori = ScriptedShiori(emptyMap())
        val scope = testScope()
        val c = GhostController(ghost, Shell(ghost.shellDir()!!), { _, _ -> shiori }, scope, RecordingPlatform(), repository = repo,
            player = ScriptPlayer(charWaitMs = 0), httpClient = FetcherHttpClient { _, out, _ -> out.write(bytes); FetchResult.Ok })
        try {
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            assertTrue(c.executeHeadline(h.name))
            withTimeout(5000) { while (shiori.count("OnHeadlinesense.OnFind") < 2) delay(10) }
            val finds = synchronized(shiori.events) { shiori.events.filter { it.first == "OnHeadlinesense.OnFind" }.map { it.second } }
            assertEquals(listOf("First", "Last"), finds.map { it[2] })
            assertTrue(finds.first()[3].contains("\\\\![raise,OnInjected]\\%username"))
            withTimeout(5000) { c.state.first { it.characters.values.any { ch -> ch.balloon?.isNotEmpty() == true } && !it.talking } }
            assertEquals(0, shiori.count("OnInjected"))
            val link = c.state.value.characters.values.flatMap { it.balloon.orEmpty() }.filterIsInstance<BalloonItem.ChoiceItem>().first()
            assertEquals("\\![raise,OnInjected]%username", link.choice.title)
            assertEquals("https://example.test/1", link.choice.id)
            assertTrue(c.executeHeadline(h.name))
            assertEquals(listOf("no update"), shiori.awaitEvent("OnHeadlinesenseComplete"))
        } finally { c.dispose().join(); scope.cancel(); repo.headlines.uninstall(h) }
    }
}
