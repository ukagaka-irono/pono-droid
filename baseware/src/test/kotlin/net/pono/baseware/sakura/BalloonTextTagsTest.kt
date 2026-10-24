package net.pono.baseware.sakura

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.pono.baseware.sakura.Token.Tag
import net.pono.baseware.sakura.Token.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文字とバルーンのタグ（\c[char] \n[half] \_n \_? \f[name] \![set,balloonwait] など）を ScriptPlayer がどう伝えるか */
class BalloonTextTagsTest {
    /** 伝えられたことを順に記録する */
    private open class Recorder : ScriptHost {
        val log = mutableListOf<String>()
        override fun setSurface(scope: Int, surface: Int) {}
        override fun appendText(scope: Int, text: String) { log += "t$scope:$text" }
        override fun newLine(scope: Int) { log += "n$scope" }
        override fun newLine(scope: Int, percent: Int) { log += "n$scope:$percent" }
        override fun clearBalloon(scope: Int) { log += "c$scope" }
        override fun clearPart(scope: Int, lines: Boolean, count: Int, start: Int?) { log += "cut$scope:${if (lines) "line" else "char"}/$count/$start" }
        override fun addChoice(scope: Int, choice: Choice) {}
        override fun setFont(scope: Int, style: TextStyle) { log += "font$scope:$style" }
        override fun setVerticalAlign(scope: Int, align: VerticalAlign) { log += "valign$scope:$align" }
        override fun setTimeCritical() { log += "timecritical" }
        override fun lock(what: String, locked: Boolean, manual: Boolean): Boolean {
            log += "lock:$what/$locked/$manual"
            return true
        }
        override fun setMode(name: String, on: Boolean): Boolean {
            log += "mode:$name/$on"
            return true
        }
        override fun setOption(scope: Int, name: String, args: List<String>): Boolean {
            log += "set$scope:$name$args"
            return true
        }
        override suspend fun waitClick() {}
        override fun raise(event: String, references: List<String>) {}
        override fun openUrl(url: String) {}
        override fun envVariable(name: String) = if (name == "username") "ぽの" else null
        override fun unknownTag(tag: Tag) { log += "unknown:${tag.name}${tag.args}" }
    }

    private fun play(script: String, host: Recorder = Recorder()): List<String> {
        runBlocking { ScriptPlayer(charWaitMs = 0).play(script, host) }
        return host.log
    }

    @Test
    fun literalSectionsKeepTagsAsText() {
        assertEquals(
            listOf(Text("前"), Text("""\1そのまま\n%username""", literal = true), Text("後")),
            SakuraScript.tokenize("""前\_?\1そのまま\n%username\_?後"""),
        )
        // 古い書き方の \_! も同じ。閉じていなければ最後まで
        assertEquals(listOf(Text("""\s[5]""", literal = true)), SakuraScript.tokenize("""\_!\s[5]"""))
        // 中の環境変数も展開しない
        assertEquals(listOf("""t0:%username\e""", "t0:さん"), play("""\_?%username\e\_?さん"""))
    }

    @Test
    fun partialClearsAndLineBreaks() {
        assertEquals(
            listOf("cut0:char/3/null", "cut0:char/3/4", "cut1:line/1/null", "cut1:line/1/2", "c1", "n1:50", "n1:150", "n1:-250", "n1"),
            play("""\c[char,3]\c[char,3,4]\1\c[line,1]\c[line,1,2]\c\n[half]\n[150]\n[-250]\n"""),
        )
        // 数が無い・0 の \c[char] は何もしない
        assertEquals(emptyList<String>(), play("""\c[char]\c[line,0]"""))
    }

    @Test
    fun noWrapTogglesAndSurvivesFontDefault() {
        val log = play("""\_n\f[bold,1]\f[default]\_n""")
        assertEquals(
            listOf(
                "font0:${TextStyle(noWrap = true)}",
                "font0:${TextStyle(noWrap = true, bold = true)}",
                "font0:${TextStyle(noWrap = true)}",
                "font0:${TextStyle()}",
            ),
            log,
        )
    }

    @Test
    fun fontNameShadowOutlineAndValign() {
        val log = play(
            """\f[name,メイリオ,meiryo.ttf]\f[shadowcolor,#ffff00]\f[shadowstyle,outline]\f[outline,1]""" +
                """\f[shadowcolor,none]\f[valign,center]\f[name,default]\f[outline,default]\f[color,disable]""",
        )
        val withFont = TextStyle(font = listOf("メイリオ", "meiryo.ttf"))
        val yellow = withFont.copy(shadowColor = 0xFFFF00)
        val outlineShadow = yellow.copy(shadowStyle = TextStyle.ShadowStyle.OUTLINE)
        val hollow = outlineShadow.copy(outline = true)
        val noShadow = hollow.copy(shadowColor = null, noShadow = true)
        assertEquals(
            listOf(
                "font0:$withFont", "font0:$yellow", "font0:$outlineShadow", "font0:$hollow", "font0:$noShadow",
                "valign0:CENTER",
                "font0:${noShadow.copy(font = null)}", "font0:${noShadow.copy(font = null, outline = null)}",
                "font0:${noShadow.copy(font = null, outline = null, disabled = true)}",
            ),
            log,
        )
    }

    @Test
    fun percentColors() {
        assertEquals((127 shl 16) or (229 shl 8) or 51, ScriptPlayer.parseColor(listOf("50%", "90%", "20%")))
    }

    @Test
    fun clickWaitResetsStylesUnlessNoclear() {
        val log = play("""\f[bold,1]\x[noclear]あ\xい""")
        assertEquals(
            listOf("font0:${TextStyle(bold = true)}", "t0:あ", "c0", "c1", "font0:${TextStyle()}", "t0:い"),
            log,
        )
    }

    @Test
    fun timeCriticalOldEndAndModes() {
        val log = play(
            """\t\![lock,balloonrepaint]\![unlock,balloonrepaint]\![lock,repaint,manual]\![enter,onlinemode]""" +
                """\![leave,nouserbreakmode]\![set,autoscroll,disable]\![set,serikotalk,false]\z消えない""",
        )
        assertEquals(
            listOf(
                "timecritical",
                "lock:balloonrepaint/true/false", "lock:balloonrepaint/false/false", "lock:repaint/true/true",
                "mode:onlinemode/true", "mode:nouserbreakmode/false",
                "set0:autoscroll[disable]", "set0:serikotalk[false]",
            ),
            log,
        )
    }

    @Test
    fun balloonWaitChangesCharacterSpeed() = runTest {
        val host = Recorder()
        val player = ScriptPlayer(charWaitMs = 50)
        val start = testScheduler.currentTime
        // 0 なら一度に出し、200% なら倍、30ms なら 30ms ごと。スクリプトが終われば元に戻る
        player.play("""\![set,balloonwait,0]あいう\![set,balloonwait,200%]えお\![set,balloonwait,30ms]か""", host)
        assertEquals(2 * 100L + 30L, testScheduler.currentTime - start)
        val next = testScheduler.currentTime
        player.play("""きく""", host)
        assertEquals(100L, testScheduler.currentTime - next)
        assertTrue(host.log.contains("t0:あいう"))
    }
}
