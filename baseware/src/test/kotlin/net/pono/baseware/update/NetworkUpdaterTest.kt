package net.pono.baseware.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream

class NetworkUpdaterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://example.test/ghost/"

    /** URL → 中身。無い URL は 404。 */
    private class FakeServer(val files: MutableMap<String, ByteArray> = mutableMapOf()) : HttpFetcher {
        val requested = mutableListOf<String>()
        override fun fetch(url: String, sink: OutputStream, maxBytes: Long): FetchResult {
            requested += url
            val bytes = files[url] ?: return FetchResult.HttpError(404)
            if (bytes.size > maxBytes) return FetchResult.Failed("file too large")
            sink.write(bytes)
            return FetchResult.Ok
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
                    "./pono/profile.properties" to evil,
                    "ghost//master/../../../outside2.txt" to evil,
                    "ghost/master/a\u0000.dic" to evil,
                    "ghost/master/a.dic" to "same".toByteArray(),
                ),
            ),
        )
        assertEquals(UpdateResult.NoChange, NetworkUpdater(dir, server).update(base))
        assertFalse(File(dir.parentFile, "outside.txt").exists())
        assertFalse(File(dir.parentFile, "outside2.txt").exists())
        assertFalse(File(dir, "pono/profile.properties").exists())
        assertEquals(listOf(base + "updates2.dau"), server.requested)
    }

    @Test
    fun safeFileRejectsDisguisedPaths() {
        val root = tmp.newFolder("g").canonicalFile
        assertEquals(File(root, "ghost/master/a.dic").canonicalFile, NetworkUpdater.safeFile(root, "ghost/master/a.dic"))
        assertNull(NetworkUpdater.safeFile(root, "./pono/profile.properties"))
        assertNull(NetworkUpdater.safeFile(root, "pono"))
        assertNull(NetworkUpdater.safeFile(root, "pono/"))
        assertNull(NetworkUpdater.safeFile(root, "ghost//master/a.dic"))
        assertNull(NetworkUpdater.safeFile(root, "ghost/master/a\u0000.dic"))
        assertNull(NetworkUpdater.safeFile(root, "/etc/passwd"))
        assertNull(NetworkUpdater.safeFile(root, ""))
        // pono で始まる別のディレクトリは管理用ではない
        assertEquals(File(root, "pono2/x").canonicalFile, NetworkUpdater.safeFile(root, "pono2/x"))
    }

    @Test
    fun appliesDeleteListAfterUpdate() = runTest {
        val dir = ghostDir("ghost/master/old.dic" to "x", "ghost/master/keep.dic" to "y", "pono/profile.properties" to "booted=1")
        val deleteList = "ghost/master/old.dic\r\n../outside.txt\r\npono/profile.properties\r\npono\r\n./pono\r\n".toByteArray()
        val server = FakeServer(
            mutableMapOf(
                base + "updates2.dau" to manifest("delete.txt" to deleteList),
                base + "delete.txt" to deleteList,
            ),
        )
        assertEquals(UpdateResult.Changed(listOf("delete.txt")), NetworkUpdater(dir, server).update(base))
        assertFalse(File(dir, "ghost/master/old.dic").exists())
        assertEquals("y", File(dir, "ghost/master/keep.dic").readText())
        // ゴーストの外と pono/ の中（pono/ そのものも）は消さない
        assertEquals("booted=1", File(dir, "pono/profile.properties").readText())
    }

    @Test
    fun triesHttpsBeforeCleartext() = runTest {
        val dir = ghostDir("ghost/master/a.dic" to "old")
        val newA = "new".toByteArray()
        // https:// 側にだけ一覧がある → 全部 https:// で取る
        val server = FakeServer(
            mutableMapOf(
                base + "updates2.dau" to manifest("ghost/master/a.dic" to newA),
                base + "ghost/master/a.dic" to newA,
            ),
        )
        assertEquals(UpdateResult.Changed(listOf("ghost/master/a.dic")), NetworkUpdater(dir, server).update("http://example.test/ghost/"))
        assertTrue(server.requested.toString(), server.requested.all { it.startsWith("https://") })
        assertEquals("new", File(dir, "ghost/master/a.dic").readText())
    }

    @Test
    fun fallsBackToCleartextWhenHttpsHasNoManifest() = runTest {
        val dir = ghostDir("ghost/master/a.dic" to "old")
        val newA = "new".toByteArray()
        val http = "http://example.test/ghost/"
        // https:// には関係ないページ（一覧として読めない）がある
        val server = FakeServer(
            mutableMapOf(
                base + "updates2.dau" to "<html>not here</html>".toByteArray(),
                http + "updates2.dau" to manifest("ghost/master/a.dic" to newA),
                http + "ghost/master/a.dic" to newA,
            ),
        )
        assertEquals(UpdateResult.Changed(listOf("ghost/master/a.dic")), NetworkUpdater(dir, server).update(http))
        assertEquals("new", File(dir, "ghost/master/a.dic").readText())
    }

    @Test
    fun validatesFileNamesFromGhosts() {
        assertEquals("rss_temp.xml", NetworkUpdater.safeFileName("rss_temp.xml"))
        assertEquals(null, NetworkUpdater.safeFileName("../x"))
        assertEquals(null, NetworkUpdater.safeFileName("a/b"))
        assertEquals(null, NetworkUpdater.safeFileName(".hidden"))
        assertEquals(null, NetworkUpdater.safeFileName("a\u0000b"))
    }

    @Test
    fun reportsHttpErrorsAndBadUrls() = runTest {
        val dir = ghostDir()
        assertEquals(UpdateResult.Failure("404"), NetworkUpdater(dir, FakeServer()).update(base))
        assertEquals(UpdateResult.Failure("invalid homeurl"), NetworkUpdater(dir, FakeServer()).update("ftp://x/"))
        val timeout = HttpFetcher { _, _, _ -> FetchResult.Timeout }
        assertEquals(UpdateResult.Failure("timeout"), NetworkUpdater(dir, timeout).update(base))
    }

    @Test
    fun refusesOversizedFiles() = runTest {
        val dir = ghostDir("ghost/master/a.dic" to "old")
        val big = ByteArray(16) { 1 }
        val server = FakeServer(mutableMapOf(base + "updates2.dau" to manifest("ghost/master/a.dic" to big), base + "ghost/master/a.dic" to big))
        // 取ってくる側（fetcher）が上限を見るので、更新は失敗として伝わる
        val limited = HttpFetcher { url, sink, _ -> server.fetch(url, sink, if (url.endsWith(".dic")) 8 else 1024) }
        assertEquals(UpdateResult.Failure("file too large"), NetworkUpdater(dir, limited).update(base))
        assertEquals("old", File(dir, "ghost/master/a.dic").readText())
    }
}
