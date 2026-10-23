package net.pono.baseware.ghost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** なでる（OnMouseMove）と、話し終わってしばらくしたときの OnSurfaceRestore */
class StrokeAndRestoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun controller(shiori: ScriptedShiori, scope: CoroutineScope, balloonTimeoutMs: Long = 15_000, surfaceRestoreMs: Long = 15_000): GhostController {
        val ghost = tmp.newTestGhost()
        return GhostController(
            ghost = ghost,
            shell = Shell(ghost.shellDir()!!),
            shioriFactory = { _, _ -> shiori },
            scope = scope,
            platform = RecordingPlatform(),
            player = ScriptPlayer(charWaitMs = 0),
            balloonTimeoutMs = balloonTimeoutMs,
            surfaceRestoreMs = surfaceRestoreMs,
            updateFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
        )
    }

    @Test
    fun strokingSendsMouseMove() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnMouseMove" to """\0なでなで"""))
        val scope = testScope()
        try {
            val c = controller(shiori, scope)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            c.onMouseMove(0, 12, 34, "Head")
            // SSP と同じ並び（Reference2 はホイール、Reference5 は 0、Reference6 は touch）
            assertEquals(listOf("12", "34", "0", "0", "Head", "0", "touch"), shiori.awaitEvent("OnMouseMove"))
            withTimeout(5000) {
                c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.Text && it.text == "なでなで" } == true }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun surfaceRestoreComesOnceAfterTheBalloonCloses() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\s[5]こんにちは\e"""))
        val scope = testScope()
        try {
            val c = controller(shiori, scope, balloonTimeoutMs = 100, surfaceRestoreMs = 200)
            c.start()
            // 本体と相方の今のサーフェス（相方は既定の 10）
            assertEquals(listOf("5", "10"), shiori.awaitEvent("OnSurfaceRestore"))
            // 1回だけ（答えが無いので、もう話さない）
            delay(600)
            assertEquals(1, shiori.count("OnSurfaceRestore"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun silentScriptsDoNotCancelSurfaceRestore() = runBlocking<Unit> {
        // 毎秒、文字を出さないスクリプト（Emily/Phase4.5 の \C\0\0\1\e のようなもの）を返すゴースト
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\s[5]こんにちは\e""", "OnSecondChange" to """\C\0\0\1\e"""))
        val scope = testScope()
        try {
            val c = controller(shiori, scope, balloonTimeoutMs = 100, surfaceRestoreMs = 1500)
            c.start()
            shiori.awaitEvent("OnBalloonTimeout")
            // 何も見せないスクリプトが途中で動いても、OnSurfaceRestore は届く
            assertEquals(listOf("5", "10"), shiori.awaitEvent("OnSurfaceRestore", timeoutMs = 5000))
            assertTrue(shiori.count("OnSecondChange") >= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun newTalkCancelsPendingSurfaceRestore() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0はじめ\e""", "OnMouseDoubleClick" to """\0つぎ\e"""))
        val scope = testScope()
        try {
            val c = controller(shiori, scope, balloonTimeoutMs = 100, surfaceRestoreMs = 400)
            c.start()
            shiori.awaitEvent("OnBalloonTimeout")
            // バルーンが閉じてすぐ次の話を始める（前の分の OnSurfaceRestore は取り消す）
            c.onMouseClick(0, 0, 0, null, 2)
            withTimeout(5000) { c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.Text && it.text == "つぎ" } == true } }
            delay(300)
            assertEquals(0, shiori.count("OnSurfaceRestore"))
            // 次の話のバルーンが閉じてから送る
            shiori.awaitEvent("OnSurfaceRestore")
            assertEquals(1, shiori.count("OnSurfaceRestore"))
        } finally {
            scope.cancel()
        }
    }
}
