package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** SERIKO の talk（話した文字の数）と yen-e（話し終わり）に使う数 */
class SerikoTalkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun countsCharactersPerCharacterAndScriptEnds() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0あいう\n\1えお\0か\e"""))
        val scope = testScope()
        try {
            val c = testController(tmp.newTestGhost(), shiori, scope)
            c.start()
            withTimeout(5000) { c.state.first { it.scriptEnds == 1 } }
            val s = c.state.value
            // キャラクターごとに数える（改行は文字に入れない）
            assertEquals(4, s.characters[0]!!.talkCount)
            assertEquals(2, s.characters[1]!!.talkCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun interruptedScriptIsNotAnEnd() = runBlocking<Unit> {
        // 1文字ずつ時間をかけて話すスクリプトを、別のイベントで割り込ませる
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0ながいながいおはなし\e""", "OnMouseDoubleClick" to """\0はい\e"""))
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            val c = GhostController(
                ghost = ghost,
                shell = Shell(ghost.shellDir()!!),
                shioriFactory = { _, _ -> shiori },
                scope = scope,
                platform = RecordingPlatform(),
                player = ScriptPlayer(charWaitMs = 200),
                updateFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
            )
            c.start()
            withTimeout(5000) { c.state.first { (it.characters[0]?.talkCount ?: 0) >= 1 } }
            c.onMouseUp(0, 0, 0, null, 2)
            withTimeout(5000) { c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.Text && it.text == "はい" } == true && !s.talking } }
            // 割り込まれた方は数えず、話し終えた方だけ数える
            assertEquals(1, c.state.value.scriptEnds)
        } finally {
            scope.cancel()
        }
    }
}
