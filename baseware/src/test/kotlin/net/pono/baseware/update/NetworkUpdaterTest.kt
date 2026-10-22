package net.pono.baseware.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NetworkUpdaterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://example.test/ghost/"

    /** URL → 中身。無い URL は 404。 */
    private class FakeServer(val files: MutableMap<String, ByteArray> = mutableMapOf()) : HttpFetcher {
        val requested = mutableListOf<String>()
        override fun fetch(url: String): FetchResult {
            requested += url
            return files[url]?.let { FetchResult.Ok(it) } ?: FetchResult.HttpError(404)
        }
    }

    private fun manifest(vararg files: Pair<String, ByteArray>) =
        files.joinToString("") { (path, bytes) -> "$path\u0001${NetworkUpdater.md5(bytes)}\u0001size=${bytes.size}\u0001\r\n" }
            .toByteArray()

    private fun ghostDir(vararg files: Pair<String, String>): File {
        val dir = tmp.newFolder("ghost")
        for ((path, text) in files) File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        return dir
    }

    @Test
    fun parsesBothManifestFormats() {
        val dau = "ghost/master/a.dic\u0001D41D8CD98F00B204E9800998ECF8427E\u0001size=0\u0001\r\n"
        val txt = "file,shell/master/surface0.png\u0001d41d8cd98f00b204e9800998ecf8427e\u0001\r\nbroken line\r\n"
        assertEquals(listOf(UpdateEntry("ghost/master/a.dic", "d41d8cd98f00b204e9800998ecf8427e")), UpdateManifest.parse(dau))
        assertEquals(listOf(UpdateEntry("shell/master/surface0.png", "d41d8cd98f00b204e9800998ecf8427e")), UpdateManifest.parse(txt))
    }

    @Test
    fun noChangeWhenAllMd5Match() = runTest {
        val dir = ghostDir("ghost/master/a.dic" to "same")
        val server = FakeServer(mutableMapOf(base + "updates2.dau" to manifest("ghost/master/a.dic" to "same".toByteArray())))
        assertEquals(UpdateResult.NoChange, NetworkUpdater(dir, server).update(base))
    }

    @Test
    fun downloadsChangedAndNewFilesAndFallsBackToUpdatesTxt() = runTest {
        val dir = ghostDir("ghost/master/a.dic" to "old", "ghost/master/b.dic" to "same")
        val newA = "new".toByteArray()
        val added = "日本語".toByteArray()
        val server = FakeServer(
            mutableMapOf(
                // updates2.dau は無い（404）ので updates.txt を見る
                base + "updates.txt" to String(manifest("ghost/master/a.dic" to newA, "ghost/master/b.dic" to "same".toByteArray(), "shell/master/新しい.txt" to added))
                    .lines().joinToString("\r\n") { if (it.isEmpty()) it else "file,$it" }.toByteArray(),
                base + "ghost/master/a.dic" to newA,
                base + "shell/master/%E6%96%B0%E3%81%97%E3%81%84.txt" to added,
            ),
        )
        var ready = -1
        val result = NetworkUpdater(dir, server).update(base.removeSuffix("/")) { ready = it }

        assertEquals(UpdateResult.Changed(listOf("ghost/master/a.dic", "shell/master/新しい.txt")), result)
        assertEquals(2, ready)
        assertEquals("new", File(dir, "ghost/master/a.dic").readText())
        assertEquals("日本語", File(dir, "shell/master/新しい.txt").readText())
        assertFalse(File(dir, "pono/update-staging").exists())
    }

    @Test
    fun md5MismatchLeavesFilesUntouched() = runTest {
        val dir = ghostDir("ghost/master/a.dic" to "old")
        val server = FakeServer(
            mutableMapOf(
                base + "updates2.dau" to manifest("ghost/master/a.dic" to "new".toByteArray()),
                base + "ghost/master/a.dic" to "corrupted".toByteArray(),
            ),
        )
        assertEquals(UpdateResult.Failure("md5 miss"), NetworkUpdater(dir, server).update(base))
        assertEquals("old", File(dir, "ghost/master/a.dic").readText())
    }

    @Test
    fun ignoresPathsOutsideTheGhost() = runTest {
        val dir = ghostDir("ghost/master/a.dic" to "same")
        val evil = "x".toByteArray()
        val server = FakeServer(
            mutableMapOf(
                base + "updates2.dau" to manifest(
                    "../outside.txt" to evil,
                    "pono/profile.properties" to evil,
                    "ghost/master/a.dic" to "same".toByteArray(),
                ),
            ),
        )
        assertEquals(UpdateResult.NoChange, NetworkUpdater(dir, server).update(base))
        assertFalse(File(dir.parentFile, "outside.txt").exists())
        assertEquals(listOf(base + "updates2.dau"), server.requested)
    }

    @Test
    fun reportsHttpErrorsAndBadUrls() = runTest {
        val dir = ghostDir()
        assertEquals(UpdateResult.Failure("404"), NetworkUpdater(dir, FakeServer()).update(base))
        assertEquals(UpdateResult.Failure("invalid homeurl"), NetworkUpdater(dir, FakeServer()).update("ftp://x/"))
        val timeout = HttpFetcher { FetchResult.Timeout }
        assertEquals(UpdateResult.Failure("timeout"), NetworkUpdater(dir, timeout).update(base))
    }
}
