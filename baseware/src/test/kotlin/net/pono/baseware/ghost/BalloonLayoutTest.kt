package net.pono.baseware.ghost

import kotlinx.coroutines.test.runTest
import net.pono.baseware.ghost.BalloonLayout.Run.ChoiceRun
import net.pono.baseware.ghost.BalloonLayout.Run.Text
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ScriptHost
import net.pono.baseware.sakura.ScriptPlayer
import org.junit.Assert.assertEquals
import org.junit.Test

class BalloonLayoutTest {
    /** さくらスクリプトを再生して、\0 のバルーンに溜まったものを返す */
    private suspend fun balloon(script: String): List<BalloonItem> {
        var state = CharacterState(0, 0, balloon = emptyList())
        val host = object : ScriptHost {
            override fun setSurface(scope: Int, surface: Int) {}
            override fun appendText(scope: Int, text: String) { state = state.appendText(text) }
            override fun newLine(scope: Int) { state = state.append(BalloonItem.NewLine) }
            override fun clearBalloon(scope: Int) { state = state.copy(balloon = emptyList()) }
            override fun addChoice(scope: Int, choice: Choice) { state = state.append(BalloonItem.ChoiceItem(choice)) }
            override fun addMarker(scope: Int) { state = state.append(BalloonItem.Marker) }
            override fun moveCursor(scope: Int, x: Int) { state = state.append(BalloonItem.Column) }
            override suspend fun waitClick() {}
            override fun raise(event: String, references: List<String>) {}
            override fun openUrl(url: String) {}
            override fun envVariable(name: String): String? = null
        }
        ScriptPlayer(charWaitMs = 0).play(script, host)
        return state.balloon.orEmpty()
    }

    @Test
    fun inlineAnchorStaysInTheLine() = runTest {
        // 植本とラフウのお知らせ。リンクの前後の文字が別の行に分かれない
        val lines = BalloonLayout.lines(balloon("""\0『\_a[https://example.test/a]除夜の鐘\_a』こんなのがあるんだね。"""))
        assertEquals(
            listOf(
                BalloonLayout.Line(
                    listOf(
                        listOf(
                            Text("『"),
                            ChoiceRun(Choice("除夜の鐘", "https://example.test/a", anchor = true), marked = false),
                            Text("』こんなのがあるんだね。"),
                        ),
                    ),
                ),
            ),
            lines,
        )
    }

    @Test
    fun sideBySideChoicesBecomeColumns() = runTest {
        // 植本とラフウのメニュー（半分メニュー）。\_l[145] で2つ目を同じ行に置く
        val lines = BalloonLayout.lines(
            balloon(
                """\0なに？\_q\n\![*]\q[何か話して      ,Menu_AITALK]\_l[145]\![*]\q[話しかける      ,Menu_TALKTO]\n""" +
                    """\_l[0]\![*]\q[なんでもない,Menu_CANCEL]\n""",
            ),
        )
        assertEquals(
            listOf(
                BalloonLayout.Line(listOf(listOf(Text("なに？")))),
                BalloonLayout.Line(
                    listOf(
                        listOf(ChoiceRun(Choice("何か話して", "Menu_AITALK"), marked = true)),
                        listOf(ChoiceRun(Choice("話しかける", "Menu_TALKTO"), marked = true)),
                    ),
                ),
                // 行の頭の \_l は列を増やさない
                BalloonLayout.Line(listOf(listOf(ChoiceRun(Choice("なんでもない", "Menu_CANCEL"), marked = true)))),
            ),
            lines,
        )
    }

    @Test
    fun keepsEmptyLinesButNotTheTrailingOne() = runTest {
        val lines = BalloonLayout.lines(balloon("""\0一行目\n\n三行目\n"""))
        assertEquals(
            listOf("一行目", "", "三行目"),
            lines.map { line -> line.columns.single().joinToString("") { (it as Text).text } },
        )
    }
}
