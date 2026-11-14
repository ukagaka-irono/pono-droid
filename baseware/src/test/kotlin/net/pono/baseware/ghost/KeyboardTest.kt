package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyboardTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun keyboardSendsWindowsCodeRepeatScopeAndModifiers() = runBlocking<Unit> {
        val scope = testScope()
        val shiori = ScriptedShiori(emptyMap())
        val c = testController(tmp.newTestGhost(), shiori, scope)
        try {
            c.start()
            withTimeout(5000) { c.state.first { it.booted } }
            c.onKeyPress("a", 65, 3, 1, setOf("alt", "ctrl", "invalid"))
            assertEquals(listOf("a", "65", "3", "1", "ctrl,alt"), shiori.awaitEvent("OnKeyPress"))
        } finally { c.dispose().join(); scope.cancel() }
    }
}
