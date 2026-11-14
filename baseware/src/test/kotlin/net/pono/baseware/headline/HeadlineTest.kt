package net.pono.baseware.headline

import kotlinx.coroutines.runBlocking
import net.pono.baseware.ghost.NarInstaller
import net.pono.baseware.net.FeedDocument
import net.pono.baseware.net.FeedParser
import net.pono.baseware.net.FetcherHttpClient
import net.pono.baseware.update.FetchResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HeadlineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val url = "https://example.test/feed.xml"
    private fun rss(title: String = "First") = """<rss version="2.0"><channel><title>Example</title><link>https://example.test/</link><item><title>$title</title><link>https://example.test/article</link></item></channel></rss>""".toByteArray()

    @Test fun parsesSiteMetadataAndAlternateAtomLinks() {
        val feed = FeedParser.parseDocument(rss(), url)!!
        assertEquals("Example", feed.title)
        assertEquals("https://example.test/", feed.link)
        val atom = FeedParser.parseDocument("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title><link rel="self" href="feed.xml"/><link rel="alternate" href="/blog"/><entry><title>Entry</title><link rel="self" href="entry.xml"/><link href="/post"/></entry></feed>""".toByteArray(), url)!!
        assertEquals("https://example.test/blog", atom.link)
        assertEquals("https://example.test/post", atom.items.single().link)
        assertNull(FeedParser.parse("<html><item><title>Not RSS</title></item></html>".toByteArray()))
        assertNull(FeedParser.parse("""<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///nonexistent">]><rss><channel><title>&x;</title></channel></rss>""".toByteArray()))
    }

    @Test fun registrationPreservesLiteralTitlesAndDoesNotOverwritePackages() {
        val repo = HeadlineRepository(tmp.newFolder("headlines"))
        val title = "100% \\![raise,OnUnexpected]"
        val h = repo.addFeed(url, FeedDocument(title, "https://example.test/", emptyList()))
        assertEquals(title, h.name)
        assertTrue(h.isValid)
        assertEquals(h.dir, repo.addFeed(url, FeedDocument("New name", "", emptyList())).dir)
        assertEquals(h.dir, repo.find(h.name)?.dir)
        assertNull(Headline.webUrl("file:///etc/passwd"))
        assertNull(Headline.webUrl("https://example.test/\nname,Injected"))
        val outside = tmp.newFolder("outside")
        assertFalse(repo.uninstall(Headline(outside)))
        assertTrue(repo.uninstall(h))
        assertTrue(outside.exists())
    }

    @Test fun readsOnlyNewItemsAndKeepsLastGoodCacheAfterFailure() = runBlocking {
        var bytes = rss()
        var fail = false
        val client = FetcherHttpClient { _, sink, _ ->
            if (fail) FetchResult.HttpError(503) else { sink.write(bytes); FetchResult.Ok }
        }
        val repo = HeadlineRepository(tmp.newFolder("headlines"))
        val h = repo.addFeed(url, FeedParser.parseDocument(bytes)!!)
        val reader = HeadlineReader(client, tmp.newFolder("cache"))
        assertEquals("First", (reader.read(h) as HeadlineResult.Updated).feed!!.items.single().title)
        assertEquals(HeadlineResult.Unchanged, reader.read(h))
        bytes = "invalid".toByteArray()
        assertEquals(HeadlineResult.Failure("can't analyze"), reader.read(h))
        fail = true
        assertEquals(HeadlineResult.Failure("can't download"), reader.read(h))
        fail = false
        bytes = rss()
        assertEquals(HeadlineResult.Unchanged, reader.read(h))
        bytes = rss("Second")
        assertEquals("Second", (reader.read(h) as HeadlineResult.Updated).items.single().title)
        File(h.dir, "descript.txt").appendText("alwaysdisplay,1\n")
        assertTrue(reader.read(Headline(h.dir)) is HeadlineResult.Updated)
    }

    @Test fun installsHeadlineArchiveAndReadsItsReadme() {
        val root = tmp.newFolder("installed")
        val nar = tmp.newFile("headline.nar")
        ZipOutputStream(nar.outputStream()).use { zip ->
            for ((name, text) in mapOf(
                "install.txt" to "charset,UTF-8\ntype,headline\nname,Sample\ndirectory,sample\n",
                "descript.txt" to "charset,UTF-8\nname,Sample\ndllname,module.dll\nurl,$url\nopenurl,https://example.test/\nreadme.charset,UTF-8\n",
                "readme.txt" to "説明", "module.dll" to "fixture",
            )) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        val result = NarInstaller(File(root, "ghost"), File(root, "balloon")).install(nar)
        assertEquals(NarInstaller.Type.HEADLINE, result.type)
        assertEquals(File(root, "headline/sample"), result.dir)
        val h = HeadlineRepository(File(root, "headline")).list().single()
        assertEquals("説明", h.readmeText())
        assertFalse(h.isFeed)
    }
}
