package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** OnUpdateProcessExec、OnUpdateResultExplorer、OnArchiveViewerOpen */
class UpdateAndExtTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(
        replies: Map<String, String>,
        platform: GhostPlatform = object : GhostPlatform by RecordingPlatform() {
            override fun openFile(file: java.io.File, mimeType: String): Boolean = true
        },
        block: suspend (GhostController, ScriptedShiori) -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope, platform)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            block(c, shiori)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun processExecReplacesTheUpdate() = run(mapOf("OnUpdateProcessExec" to """\0自分で更新するね\e""")) { c, shiori ->
        c.networkUpdate()
        assertEquals(listOf("manual"), shiori.awaitEvent("OnUpdateProcessExec"))
        delay(300)
        assertEquals(0, shiori.count("OnUpdateBegin"))
    }

    @Test
    fun explorerUpdatesReportWithTheExplorerEvent() = run(emptyMap()) { c, shiori ->
        c.networkUpdate(explorer = true)
        shiori.awaitEvent("OnUpdateResultExplorer", timeoutMs = 5000)
        assertEquals(0, shiori.count("OnUpdateResult"))
    }

    @Test
    fun archivesOpenInAnotherApp() = run(emptyMap()) { c, shiori ->
        c.onFileDrop(listOf("/sdcard/a.zip"), listOf("application/zip"), 0)
        assertEquals(listOf("/sdcard/a.zip", "0", "application/zip"), shiori.awaitEvent("OnArchiveViewerOpen"))
    }
}
