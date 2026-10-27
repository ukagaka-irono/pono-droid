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
            override fun locate(scope: Int, x: net.pono.baseware.sakura.BalloonCoord?, y: net.pono.baseware.sakura.BalloonCoord?) {
                state = state.append(BalloonItem.Locate(x, y))
            }
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

    @Test
    fun locateSplitsIntoSegments() = runTest {
        val items = balloon("""\0なに？\_q\n\![*]\q[何か話して,A]\_l[145]\![*]\q[話しかける,B]\n\f[height,20]大\_l[@-1em,3lh]続き""")
        val segments = BalloonLayout.segments(items)
        assertEquals(3, segments.size)
        assertEquals(null, segments[0].x)
        assertEquals(net.pono.baseware.sakura.BalloonCoord(145f, net.pono.baseware.sakura.BalloonCoord.Unit.PX), segments[1].x)
        assertEquals(null, segments[1].y)
        assertEquals(2, segments[0].lines.size)
        // 2つ目のまとまりは、選択肢の行と「大」の行
        assertEquals(2, segments[1].lines.size)
        assertEquals(net.pono.baseware.sakura.BalloonCoord(-1f, net.pono.baseware.sakura.BalloonCoord.Unit.EM, relative = true), segments[2].x)
        assertEquals(net.pono.baseware.sakura.BalloonCoord(3f, net.pono.baseware.sakura.BalloonCoord.Unit.LH), segments[2].y)
        // em の基準は \_l を書いた時点の文字の見た目
        val big = net.pono.baseware.sakura.TextStyle(height = 20f)
        val styled = BalloonLayout.segments(listOf(BalloonItem.Text("a", big), BalloonItem.Locate(null, null), BalloonItem.Text("b")))
        assertEquals(big, styled[1].style)
    }

}
