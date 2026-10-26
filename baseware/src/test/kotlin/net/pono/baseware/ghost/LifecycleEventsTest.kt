package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** OnInitialize / OnDestroy、OnBasewareUpdated、プラグインが無いときの失敗、古い OnFileDrop / OnFileDropped */
class LifecycleEventsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(
        replies: Map<String, String>,
        before: (Ghost) -> Unit = {},
        block: suspend (GhostController, ScriptedShiori) -> Unit,
    ) = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        before(ghost)
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val c = testController(ghost, shiori, scope)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            block(c, shiori)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun initializeAndDestroyAroundReload() = run(mapOf("OnReload" to """\0\![reload,shiori]\e""")) { c, shiori ->
        assertEquals(listOf(""), shiori.awaitEvent("OnInitialize"))
        c.raise("OnReload", emptyList())
        assertEquals(listOf("reload"), shiori.awaitEvent("OnDestroy"))
        withTimeout(5000) { while (shiori.count("OnInitialize") < 2) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("reload"), shiori.awaitEvent("OnInitialize"))
    }

    @Test
    fun basewareUpdatedInsteadOfBoot() = run(
        mapOf("OnBasewareUpdated" to """\0新しくなったね\e"""),
        before = { g -> g.saveProfile(g.loadProfile().apply { setProperty("booted", "1"); setProperty("basewareversion", "0.0.1") }) },
    ) { _, shiori ->
        val refs = shiori.awaitEvent("OnBasewareUpdated")
        assertEquals(2, refs.size)
        assertEquals(0, shiori.count("OnBoot"))
    }

    @Test
    fun pluginsAreNeverFound() = run(
        mapOf("OnTest" to """\0\![raiseplugin,時計,OnTick,1,2]\![timernotifyplugin,100,0,天気,OnWeather]\e"""),
    ) { c, shiori ->
        c.raise("OnTest", emptyList())
        assertEquals(listOf("notfound", "時計", "OnTick", "1", "2"), shiori.awaitEvent("OnRaisePluginFailure"))
        assertEquals(listOf("notfound", "天気", "OnWeather"), shiori.awaitEvent("OnNotifyPluginFailure"))
    }

    @Test
    fun oldFileDropEvents() = run(emptyMap()) { c, shiori ->
        c.onFileDrop(listOf("/sdcard/a.txt", "/sdcard/b.txt"), listOf("text/plain", "text/plain"), 1)
        // 1ファイルずつ送る（答えが無ければ次のファイルへ）
        withTimeout(5000) { while (shiori.count("OnFileDropped") < 2) kotlinx.coroutines.delay(10) }
        assertEquals(2, shiori.count("OnFileDrop"))
        assertEquals(listOf("/sdcard/b.txt", "1", "text/plain"), shiori.awaitEvent("OnFileDropped"))
    }
}
