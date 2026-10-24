package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** \![set,wallpaper]・\![open,editor / explorer]・\![set,shioridebugmode] */
class WallpaperEditorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Platform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val wallpapers: MutableList<WallpaperRequest> = Collections.synchronizedList(mutableListOf())
        val edited: MutableList<Pair<File, Int?>> = Collections.synchronizedList(mutableListOf())
        val opened: MutableList<File> = Collections.synchronizedList(mutableListOf())

        override fun setWallpaper(request: WallpaperRequest): Boolean {
            wallpapers += request
            return true
        }

        override fun editFile(file: File, line: Int?): Boolean {
            edited += file to line
            return true
        }

        override fun openFile(file: File, mimeType: String): Boolean {
            opened += file
            return true
        }
    }

    private fun run(script: String, block: suspend (GhostController, ScriptedShiori, Platform, Ghost) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to script))
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            File(ghost.masterDir, "wall.png").writeBytes(byteArrayOf(1))
            File(ghost.dir, "thumbnail.png").writeBytes(byteArrayOf(1))
            File(ghost.masterDir, "dic.txt").writeText("x")
            val platform = Platform()
            val c = testController(ghost, shiori, scope, platform)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            withTimeout(5000) { c.state.first { !it.talking } }
            block(c, shiori, platform, ghost)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun wallpaperRequests() = run(
        """\0\![set,wallpaper,wall.png]\![set,wallpaper,--file=thumbnail.png,--position=fit,--color=navy]\![set,wallpaper,"--color=32,64,96"]""" +
            """\![set,wallpaper,../../../x.png]\![set,wallpaper,--position=tile]\e""",
    ) { _, _, platform, ghost ->
        assertEquals(
            listOf(
                WallpaperRequest(File(ghost.masterDir, "wall.png").canonicalFile, "fill", null),
                WallpaperRequest(File(ghost.dir, "thumbnail.png").canonicalFile, "fit", 0x000080),
                WallpaperRequest(null, "fill", 0x204060),
            ),
            platform.wallpapers.toList(),
        )
        assertTrue(platform.inner.logs.any { "壁紙の画像が見つかりません: ../../../x.png" in it })
    }

    @Test
    fun editorExplorerAndDebugMode() = run(
        """\0\![open,editor,dic.txt,12]\![open,explorer,dic.txt]\![open,editor,../../../etc/hosts]\![set,shioridebugmode,false]\![set,shioridebugmode]\e""",
    ) { _, shiori, platform, ghost ->
        val dic = File(ghost.masterDir, "dic.txt").canonicalFile
        assertEquals(listOf(dic to 12), platform.edited.toList())
        assertEquals(listOf(dic), platform.opened.toList())
        assertTrue(platform.inner.logs.any { it.startsWith("未対応のタグ") && "etc/hosts" in it })
        withTimeout(5000) { while (shiori.count("enable_debug") < 2) kotlinx.coroutines.delay(10) }
        val debug = synchronized(shiori.events) { shiori.events.filter { it.first == "enable_debug" }.map { it.second } }
        assertEquals(listOf(listOf("0"), listOf("1")), debug)
    }
}
