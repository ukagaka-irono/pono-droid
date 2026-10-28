package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/** .nar と更新データを作る・サーフェスとバルーンの書き出し・ファイルやメールを開く・ショートカット・ヘルプ */
class DevToolsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun File.put(rel: String, text: String = rel) = File(this, rel).apply { parentFile.mkdirs(); writeText(text) }

    private fun narEntries(file: File) = ZipFile(file).use { z -> z.entries().toList().map { it.name }.sorted() }

    @Test
    fun narLeavesOutWhatSspLeavesOut() {
        val root = tmp.newFolder("g")
        for (f in listOf(
            "install.txt", "ghost/master/descript.txt", "ghost/master/dic.txt", "ghost/master/profile/save.dat", "ghost/master/var/got.html",
            "shell/master/surface0.png", "shell/master/Thumbs.db", "pono/profile.properties", "shell/old/surface0.png",
            "ghost/master/notes.bak", "ghost/master/keep.bak", "secret/key.txt", "ghost/master/savedata.txt",
        )) root.put(f)
        root.put("developer_options.txt", "shell/old/,nonar\r\nghost/master/savedata.txt,noupdate\r\n")
        root.put(".narignore", "# メモ\n*.bak\n!keep.bak\nsecret/\n")
        val out = File(root, "ghost/master/out.nar")
        val count = GhostPackager(root).createNar(out)
        val names = narEntries(out)
        assertEquals(
            listOf(".narignore", "developer_options.txt", "ghost/master/descript.txt", "ghost/master/dic.txt", "ghost/master/keep.bak", "ghost/master/savedata.txt", "install.txt", "shell/master/surface0.png"),
            names,
        )
        assertEquals(names.size, count)
    }

    @Test
    fun includeListsKeepOnlyWhatTheyName() {
        val root = tmp.newFolder("g")
        for (f in listOf("install.txt", "ghost/master/descript.txt", "shell/master/surface0.png", "shell/extra/surface0.png", "readme.txt")) root.put(f)
        root.put(".narinclude", "install.txt\nghost/\nshell/master\ninclude:more.txt\n")
        root.put("more.txt", "readme.txt\n")
        val out = File(tmp.root, "x.nar")
        GhostPackager(root).createNar(out)
        assertEquals(listOf("ghost/master/descript.txt", "install.txt", "readme.txt", "shell/master/surface0.png"), narEntries(out))
    }

    @Test
    fun updateDataListsFilesWithMd5() {
        val root = tmp.newFolder("g")
        root.put("ghost/master/descript.txt", "abc")
        root.put("ghost/master/savedata.txt", "x")
        val options = "ghost/master/savedata.txt,noupdate\r\n"
        root.put("developer_options.txt", options)
        root.put("updates2.dau", "old")
        val dau = File(root, "updates2.dau")
        val txt = File(root, "ghost/master/updates.txt")
        GhostPackager(root).createUpdateData(listOf(dau, txt))
        // "abc" の MD5
        assertEquals(
            "developer_options.txt\u0001${md5(options)}\u0001size=${options.length}\u0001\r\n" +
                "ghost/master/descript.txt\u0001900150983cd24fb0d6963f7d28e17f72\u0001size=3\u0001\r\n",
            dau.readText(),
        )
        assertTrue(txt.readText().lines().first().startsWith("file,developer_options.txt\u0001"))
        // 読み戻せる形
        assertEquals(listOf("developer_options.txt", "ghost/master/descript.txt"), net.pono.baseware.update.UpdateManifest.parse(txt.readText()).map { it.path })
    }

    @Test
    fun oldUpdatesDefIgnoresFilesInTheUpdateList() {
        val root = tmp.newFolder("g")
        root.put("ghost/master/descript.txt", "abc")
        root.put("ghost/master/save.txt", "x")
        root.put("ghost/master/keep.txt", "y")
        // フォルダは書けない（無視する）。ignore の無い行も効かない
        root.put("updates_def.txt", "ghost\\master\\save.txt,ignore\r\nghost/master/keep.txt,other\r\nghost/,ignore\r\n")
        val dau = File(root, "updates2.dau")
        GhostPackager(root).createUpdateData(listOf(dau))
        val names = dau.readText().lines().filter { it.isNotEmpty() }.map { it.substringBefore('\u0001') }
        assertEquals(listOf("ghost/master/descript.txt", "ghost/master/keep.txt", "updates_def.txt"), names)
        // .nar には入る（ignore は更新の一覧だけ）
        val out = File(tmp.root, "g.nar")
        GhostPackager(root).createNar(out)
        assertTrue("ghost/master/save.txt" in narEntries(out))
    }

    private fun md5(s: String) = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 書き出しなどを頼まれたら覚えておくプラットフォーム */
    private class ToolPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val shared: MutableList<File> = Collections.synchronizedList(mutableListOf())
        val dumps: MutableList<SurfaceDump> = Collections.synchronizedList(mutableListOf())
        val balloons: MutableList<Pair<Int, File>> = Collections.synchronizedList(mutableListOf())
        val opened: MutableList<Pair<File, String>> = Collections.synchronizedList(mutableListOf())
        val shortcuts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

        override fun shareFile(file: File, mimeType: String) {
            shared += file
        }

        override suspend fun dumpSurfaces(dump: SurfaceDump): Int {
            dumps += dump
            delay(200)
            return dump.surfaces.size
        }

        override suspend fun dumpBalloon(scope: Int, file: File, hide: Set<String>): Boolean {
            balloons += scope to file
            return true
        }

        override fun openFile(file: File, mimeType: String): Boolean {
            opened += file to mimeType
            return true
        }

        override fun createShortcut(dirName: String, label: String): Boolean {
            shortcuts += dirName to label
            return true
        }
    }

    private fun run(script: String, extra: Map<String, String> = emptyMap(), prepare: (Ghost) -> Unit = {}, block: suspend (GhostController, ScriptedShiori, ToolPlatform, Ghost) -> Unit) =
        runBlocking<Unit> {
            val shiori = ScriptedShiori(mapOf("OnFirstBoot" to script) + extra)
            val scope = testScope()
            try {
                val ghost = tmp.newTestGhost("dev")
                for (i in listOf(0, 1, 5, 10)) File(ghost.dir, "shell/master/surface$i.png").writeBytes(byteArrayOf(1))
                prepare(ghost)
                val platform = ToolPlatform()
                val c = testController(ghost, shiori, scope, platform)
                c.start()
                shiori.awaitEvent("OnFirstBoot")
                block(c, shiori, platform, ghost)
            } finally {
                scope.cancel()
            }
        }

    private suspend fun <T> awaitItem(list: List<T>): T = withTimeout(5000) {
        while (list.isEmpty()) delay(10)
        list.first()
    }

    @Test
    fun createNarFromAScriptSharesItWhenNoPathIsGiven() = run("""\0\![execute,createnar]\e""") { _, shiori, platform, ghost ->
        val out = File(ghost.dir, "pono/nar/dev.nar")
        assertEquals(listOf("テスト", out.absolutePath, "ghost"), shiori.awaitEvent("OnNarCreating"))
        shiori.awaitEvent("OnNarCreated")
        assertEquals(out, awaitItem(platform.shared))
        assertTrue("ghost/master/descript.txt" in narEntries(out))
        assertFalse(narEntries(out).any { it.startsWith("pono/") })
    }

    @Test
    fun createNarAndUpdateDataStayInsideTheGhost() = run(
        """\0\![execute,createnar,../../../../outside.nar]\![execute,createnar,dist/me.nar]\![execute,createupdatedata]\e""",
    ) { _, shiori, platform, ghost ->
        shiori.awaitEvent("OnNarCreated")
        shiori.awaitEvent("OnUpdatedataCreated")
        assertTrue(File(ghost.masterDir, "dist/me.nar").isFile)
        assertFalse(File(ghost.dir.parentFile.parentFile, "outside.nar").exists())
        assertTrue(platform.shared.isEmpty())
        for (f in listOf(File(ghost.dir, "updates2.dau"), File(ghost.dir, "updates.txt"), File(ghost.masterDir, "updates2.dau"), File(ghost.masterDir, "updates.txt"))) {
            assertTrue(f.path, f.isFile)
            assertFalse(f.readText().contains("updates2.dau"))
        }
        assertTrue(platform.inner.logs.any { "nar の出力先にできません" in it })
    }

    @Test
    fun dumpSurfaceWaitsUnlessAnEventIsGiven() = run(
        """\0\![execute,dumpsurface,dump,0,"0-9,!1"]待った\![execute,dumpsurface,--dir=dump2,--scope=1,--surface=__system_surface_all__,--prefix=s,--event=OnDumped,--animation=3]\e""",
    ) { c, shiori, platform, ghost ->
        assertEquals(listOf("4"), shiori.awaitEvent("OnDumped"))
        val (first, second) = platform.dumps.toList()
        assertEquals(SurfaceDump(File(ghost.masterDir, "dump").canonicalFile, 0, listOf(0, 5), "surface", emptySet()), first)
        assertEquals(listOf(0, 1, 5, 10), second.surfaces)
        assertEquals(1, second.scope)
        assertEquals("s", second.prefix)
        assertEquals(3, second.animation)
        assertTrue(File(ghost.masterDir, "dump").isDirectory)
        assertTrue(c.state.value.characters[0]!!.balloon!!.any { it is BalloonItem.Text && "待った" in it.text })
    }

    @Test
    fun dumpsRefuseToWriteOutsideTheGhost() = run(
        """\0\![execute,dumpsurface,../../../x]\![execute,dumpsurface,dump,0,0,../x]\![execute,dumpballoon,--dir=b,--scope=1,--event=OnBalloonDumped]\e""",
    ) { _, shiori, platform, ghost ->
        assertEquals(listOf("1"), shiori.awaitEvent("OnBalloonDumped"))
        assertTrue(platform.dumps.isEmpty())
        assertEquals(listOf(1 to File(File(ghost.masterDir, "b").canonicalFile, "balloon1.png")), platform.balloons.toList())
        assertEquals(2, platform.inner.logs.count { "書き出せる場所ではありません" in it })
    }

    @Test
    fun openFileMailerHelpAndShortcut() = run(
        """\0\![open,file,readme.txt]\![open,file,../../../etc/passwd]\![open,mailer,a@example.com]\![open,mailer,a@b.c?cc=x@y.z]\![open,help]\![create,shortcut]\![create,shortcut,startup]\e""",
        prepare = { File(it.masterDir, "readme.txt").writeText("r") },
    ) { _, _, platform, ghost ->
        withTimeout(5000) { while (platform.shortcuts.isEmpty()) delay(10) }
        assertEquals(listOf(File(ghost.masterDir, "readme.txt").canonicalFile to "text/plain"), platform.opened.toList())
        assertEquals(listOf("mailto:a@example.com", GhostController.HELP_URL), platform.inner.urls.toList())
        assertEquals(listOf("dev" to "テスト"), platform.shortcuts.toList())
        assertTrue(platform.inner.logs.any { it.startsWith("未対応のタグ") && "startup" in it })
    }
}
