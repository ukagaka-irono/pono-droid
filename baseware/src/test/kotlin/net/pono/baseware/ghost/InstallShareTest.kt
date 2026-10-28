package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** シェル・追加ファイル・package の .nar、インストールのイベント、文字と URL の共有、x-ukagaka-link */
class InstallShareTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun nar(file: File, entries: Map<String, String>): File {
        ZipOutputStream(file.outputStream()).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return file
    }

    private fun nar(name: String, entries: Map<String, String>) = nar(File(tmp.root, name), entries)

    private fun run(
        replies: Map<String, String> = emptyMap(),
        fetcher: HttpFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
        block: suspend (GhostController, ScriptedShiori, Ghost) -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val ghostsDir = tmp.newFolder("ghosts")
            val ghostDir = File(ghostsDir, "me").apply { mkdirs() }
            File(ghostDir, "ghost/master").mkdirs()
            File(ghostDir, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,テスト\r\nsakura.name,さくら\r\nshiori,yaya.dll\r\n")
            File(ghostDir, "shell/master").mkdirs()
            File(ghostDir, "shell/master/descript.txt").writeText("charset,UTF-8\r\nname,master\r\n")
            val ghost = Ghost(ghostDir)
            val repo = GhostRepository(ghostsDir)
            val c = GhostController(
                ghost = ghost,
                shell = net.pono.baseware.shell.Shell(ghost.shellDir()!!),
                shioriFactory = { _, _ -> shiori },
                scope = scope,
                platform = RecordingPlatform(),
                repository = repo,
                player = net.pono.baseware.sakura.ScriptPlayer(charWaitMs = 0),
                updateFetcher = fetcher,
                httpClient = net.pono.baseware.net.FetcherHttpClient(fetcher),
            )
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori, ghost)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shellNarGoesIntoTheAcceptingGhost() = run { c, shiori, ghost ->
        val file = nar("shell.nar", mapOf("install.txt" to "type,shell\r\nname,夏服\r\ndirectory,summer\r\naccept,さくら\r\n", "descript.txt" to "name,夏服\r\n"))
        val r = c.installNar(file).getOrThrow()
        assertEquals(File(ghost.dir, "shell/summer").canonicalPath, r.dir.canonicalPath)
        shiori.awaitEvent("OnInstallBegin")
        val ex = shiori.awaitEvent("OnInstallCompleteEx")
        assertEquals(listOf("shell", "夏服"), ex.take(2))
        shiori.awaitEvent("OnInstallCompleteAll")
    }

    @Test
    fun shellForAnotherGhostIsRefused() = run { c, shiori, ghost ->
        val file = nar("other.nar", mapOf("install.txt" to "type,shell\r\nname,よそ\r\ndirectory,other\r\naccept,ほかの子\r\n"))
        assertEquals(null, c.installNar(file).getOrNull())
        assertEquals(listOf("ほかの子", "shell", "よそ"), shiori.awaitEvent("OnInstallRefuse"))
        assertTrue(!File(ghost.dir, "shell/other").exists())
    }

    @Test
    fun packageAndBundledBalloonsAndRefresh() = run { c, shiori, _ ->
        val inner1 = nar("g.nar", mapOf("install.txt" to "type,ghost\r\nname,二人目\r\ndirectory,second\r\nballoon0.directory,b0\r\nballoon0.source.directory,extra/b0\r\n", "extra/b0/descript.txt" to "name,b0\r\n"))
        val pkg = File(tmp.root, "pkg.nar")
        ZipOutputStream(pkg.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("install.txt"))
            z.write("type,package\r\nbootghost,second\r\n".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("g.nar"))
            z.write(inner1.readBytes())
            z.closeEntry()
        }
        val r = c.installNar(pkg).getOrThrow()
        assertEquals(listOf("ghost" to "二人目", "balloon" to "b0"), r.installed.map { it.type.id to it.name })
        // bootghost は入れたゴーストのディレクトリ名
        assertEquals("second", r.bootGhost)
        val ex = shiori.awaitEvent("OnInstallCompleteEx")
        assertEquals("ghost\u0001balloon", ex[0])
        assertEquals("二人目\u0001b0", ex[1])
        // refresh,1 なら前のファイルを消してから入れる（refreshundeletemask は残す）
        val dir = r.dir
        File(dir, "old.txt").writeText("x")
        File(dir, "keep.txt").writeText("y")
        val again = nar("g2.nar", mapOf("install.txt" to "type,ghost\r\ndirectory,second\r\nrefresh,1\r\nrefreshundeletemask,keep.txt\r\n", "new.txt" to "n"))
        c.installNar(again)
        assertTrue(!File(dir, "old.txt").exists())
        assertTrue(File(dir, "keep.txt").exists())
        assertTrue(File(dir, "new.txt").exists())
    }

    @Test
    fun brokenNarFails() = run { c, shiori, _ ->
        assertEquals(null, c.installNar(nar("bad.nar", mapOf("readme.txt" to "x"))).getOrNull())
        assertEquals(listOf("invalid type"), shiori.awaitEvent("OnInstallFailure"))
    }

    @Test
    fun textAndUrlsFromTheShareSheet() = run(mapOf("OnURLQuery" to """\0見ておく\e""")) { c, shiori, _ ->
        c.onTextShared("一行目\n二行目", 1)
        assertEquals(listOf("一行目\u0001二行目", "1"), shiori.awaitEvent("OnTextDrop"))
        c.onTextShared("https://e.test/page")
        assertEquals(listOf("https://e.test/page", "0", "text/html", "unknown"), shiori.awaitEvent("OnURLQuery"))
    }

    @Test
    fun sharedNarUrlsAreDownloadedAndInstalled() {
        val narBytes = java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("install.txt"))
                z.write("type,balloon\r\nname,吹き出し\r\ndirectory,fuki\r\n".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val fetcher = HttpFetcher { url, sink, _ ->
            if (url.endsWith(".nar")) {
                sink.write(narBytes)
                FetchResult.Ok
            } else {
                FetchResult.HttpError(404)
            }
        }
        run(fetcher = fetcher) { c, shiori, _ ->
            c.onTextShared("https://e.test/fuki.nar")
            assertEquals("nar", shiori.awaitEvent("OnURLQuery")[3])
            assertEquals(listOf("https://e.test/fuki.nar", "0"), shiori.awaitEvent("OnURLDropping"))
            assertEquals("https://e.test/fuki.nar", shiori.awaitEvent("OnURLDropped")[1])
            assertEquals(listOf("balloon", "吹き出し"), shiori.awaitEvent("OnInstallCompleteEx").take(2))
        }
    }

    @Test
    fun ukagakaLinks() = run { c, shiori, _ ->
        assertTrue(!c.onUkagakaLink("x-ukagaka-link:type=event&ghost=ほか&info=a"))
        assertTrue(c.onUkagakaLink("x-ukagaka-link:type=event&ghost=%E3%81%95%E3%81%8F%E3%82%89&info=sweet%20potato"))
        assertEquals(listOf("sweet potato"), shiori.awaitEvent("OnXUkagakaLinkOpen"))
    }
}
