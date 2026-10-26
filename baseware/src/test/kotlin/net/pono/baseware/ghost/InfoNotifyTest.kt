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

/** 起動時の NOTIFY（入っているもの・置き場・capability）と OnDisplayChangeEx */
class InfoNotifyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun bootNotifiesWhatIsInstalled() = runBlocking<Unit> {
        val ghost = tmp.newTestGhost()
        val shiori = ScriptedShiori(mapOf("OnDisplayChangeEx" to """\0広くなった\e"""))
        val platform = RecordingPlatform().apply { device = DeviceInfo(screenWidth = 1080, screenHeight = 2400) }
        val scope = testScope()
        try {
            val c = testController(ghost, shiori, scope, platform)
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            assertEquals(listOf("master"), shiori.awaitEvent("installedshellname"))
            assertTrue(shiori.awaitEvent("capability").contains("response.valuenotify"))
            assertEquals(listOf("init", "0,0,1080,2400,32,1,unknown,0"), shiori.awaitEvent("OnDisplayChangeEx"))
            c.onDisplayChanged(2400, 1080)
            withTimeout(5000) { while (shiori.count("OnDisplayChangeEx") < 2) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("update", "0,0,2400,1080,32,1,unknown,0"), shiori.awaitEvent("OnDisplayChangeEx"))
            // Ex に答えがあれば、古い OnDisplayChange は送らない
            kotlinx.coroutines.delay(200)
            assertEquals(1, shiori.count("OnDisplayChange"))
        } finally {
            scope.cancel()
        }
    }
}
