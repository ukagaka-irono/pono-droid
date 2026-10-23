package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** SHIORI やシェルを読み込んでいる間は「読み込み中」になること */
class GhostLoadingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** [gate] が開くまで load から戻らない SHIORI */
    private class SlowShiori(val inner: ScriptedShiori, val gate: CountDownLatch, val result: Boolean = true) : Shiori by inner {
        override fun load(dir: File): Boolean {
            gate.await(5, TimeUnit.SECONDS)
            return result
        }
    }

    @Test
    fun loadingIsShownUntilShioriIsLoaded() = runBlocking<Unit> {
        val inner = ScriptedShiori(mapOf("OnFirstBoot" to """\0こんにちは"""))
        val gate = CountDownLatch(1)
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), SlowShiori(inner, gate), scope)
            c.start()
            // start から戻った時点で読み込み中（呼んだ側がすぐ見られる）
            assertEquals("読み込み中…", c.state.value.loading)
            delay(200)
            assertEquals("読み込み中…", c.state.value.loading)
            // 読み込み中でもキャラクターは出ている
            assertEquals(setOf(0, 1), c.state.value.characters.keys)

            gate.countDown()
            withTimeout(5000) { c.state.first { it.loading == null } }
            inner.awaitEvent("OnFirstBoot")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadingEndsWhenShioriFails() = runBlocking<Unit> {
        val gate = CountDownLatch(0)
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), SlowShiori(ScriptedShiori(emptyMap()), gate, result = false), scope)
            c.start()
            withTimeout(5000) { c.state.first { it.loading == null } }
            assertNull(c.state.value.loading)
        } finally {
            scope.cancel()
        }
    }
}
