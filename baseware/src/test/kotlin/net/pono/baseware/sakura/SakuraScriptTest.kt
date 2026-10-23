package net.pono.baseware.sakura

import kotlinx.coroutines.test.runTest
import net.pono.baseware.sakura.Token.Tag
import net.pono.baseware.sakura.Token.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SakuraScriptTest {
    @Test
    fun tokenizesTagsAndText() {
        val tokens = SakuraScript.tokenize("""\h\s0こんにちは\w9\n\u\s[10]やあ\_w[300]\e""")
        assertEquals(
            listOf(
                Tag("h"), Tag("s", listOf("0")), Text("こんにちは"), Tag("w", listOf("9")), Tag("n"),
                Tag("u"), Tag("s", listOf("10")), Text("やあ"), Tag("_w", listOf("300")), Tag("e"),
            ),
            tokens,
        )
    }

    @Test
    fun parsesQuotedArgsAndEscapes() {
        val tokens = SakuraScript.tokenize("""\q["a,b",OnTest,r0]\\\]\![raise,OnX,"1,2"]""")
        assertEquals(
            listOf(
                Tag("q", listOf("a,b", "OnTest", "r0")),
                Text("\\]"),
                Tag("!", listOf("raise", "OnX", "1,2")),
            ),
            tokens,
        )
    }

    @Test
    fun parsesUnderscoreTags() {
        val tokens = SakuraScript.tokenize("""\__q[ID1]選択\__q\_q早い\_q""")
        assertEquals(
            listOf(Tag("__q", listOf("ID1")), Text("選択"), Tag("__q"), Tag("_q"), Text("早い"), Tag("_q")),
            tokens,
        )
    }

    @Test
    fun playerReportsChoicesSurfacesAndEnv() = runTest {
        val log = mutableListOf<String>()
        val host = object : ScriptHost {
            override fun setSurface(scope: Int, surface: Int) { log += "s$scope=$surface" }
            override fun appendText(scope: Int, text: String) { log += "t$scope:$text" }
            override fun newLine(scope: Int) { log += "n$scope" }
            override fun clearBalloon(scope: Int) { log += "c$scope" }
            override fun addChoice(scope: Int, choice: Choice) { log += "q$scope:${choice.title}/${choice.id}" }
            override suspend fun waitClick() {}
            override fun raise(event: String, references: List<String>) { log += "raise:$event" }
            override fun openUrl(url: String) {}
            override fun envVariable(name: String) = if (name == "username") "ぽの" else null
        }
        val result = ScriptPlayer(charWaitMs = 0).play("""\0\s[5]%usernameさん\% \n\q[はい,OnYes]\__q[ID2]いいえ\__q\-""", host)
        assertEquals(
            listOf("s0=5", "t0:ぽのさん% ", "n0", "q0:はい/OnYes", "q0:いいえ/ID2"),
            log,
        )
        assertTrue(result.quit)
        assertTrue(result.hasChoices)
    }

    @Test
    fun playerPassesSetOptionsAndIgnoresLayoutTags() = runTest {
        val options = mutableListOf<String>()
        val unknown = mutableListOf<String>()
        val host = object : ScriptHost {
            override fun setSurface(scope: Int, surface: Int) {}
            override fun appendText(scope: Int, text: String) {}
            override fun newLine(scope: Int) {}
            override fun clearBalloon(scope: Int) {}
            override fun addChoice(scope: Int, choice: Choice) {}
            override suspend fun waitClick() {}
            override fun raise(event: String, references: List<String>) {}
            override fun openUrl(url: String) {}
            override fun envVariable(name: String): String? = null
            override fun setOption(name: String, args: List<String>): Boolean {
                options += "$name=$args"
                return name == "balloontimeout"
            }
            override fun execute(name: String, args: List<String>): Boolean {
                options += "execute:$name=$args"
                return name == "http-get"
            }
            override fun bind(scope: Int, category: String, part: String, on: Boolean?) {
                options += "bind:$scope/$category/$part/$on"
            }
            override fun setBalloonId(scope: Int, id: Int) {
                options += "balloon:$scope/$id"
            }
            override fun unknownTag(tag: Token.Tag) { unknown += tag.name + tag.args }
        }
        ScriptPlayer(charWaitMs = 0).play(
            """\C\![set,balloontimeout,5000]\_n\_l[145]\![*]\q[a,b]\![set,foo,x]\Z""" +
                """\![execute,http-get,https://e.test/a.xml,--async=X,--file=a.xml]\1\![bind,帽子,麦わら,0]\b[2]\0\b2\b[-1]""",
            host,
        )
        assertEquals(
            listOf(
                "balloontimeout=[5000]",
                "foo=[x]",
                "execute:http-get=[https://e.test/a.xml, --async=X, --file=a.xml]",
                "bind:1/帽子/麦わら/false",
                // \b[n] と、1桁の省略形 \bn
                "balloon:1/2",
                "balloon:0/2",
                "balloon:0/-1",
            ),
            options,
        )
        // 扱えなかった \![set,...] と本当に知らないタグだけが報告される（SSP 専用の \![set,wallpaper] などは黙って飛ばす）
        assertEquals(listOf("![set, foo, x]", "Z[]"), unknown)
    }

    @Test
    fun playsSspTagsForCharactersStylesAndEvents() = runTest {
        val log = mutableListOf<String>()
        val host = object : ScriptHost {
            override fun setSurface(scope: Int, surface: Int) {}
            override fun appendText(scope: Int, text: String) { log += "t$scope:$text" }
            override fun newLine(scope: Int) {}
            override fun clearBalloon(scope: Int) {}
            override fun addChoice(scope: Int, choice: Choice) { log += "q:${choice.id}/${choice.anchor}" }
            override suspend fun waitClick() {}
            override fun raise(event: String, references: List<String>) { log += "raise:$event$references" }
            override fun notify(event: String, references: List<String>) { log += "notify:$event$references" }
            override suspend fun embed(event: String, references: List<String>): String? = if (event == "OnEmbed") "埋め\\_u[0x2764]" else null
            override fun timerRaise(delayMs: Long, once: Boolean, event: String, references: List<String>) { log += "timer:$delayMs/$once/$event$references" }
            override fun getProperty(event: String, names: List<String>) { log += "prop:$event$names" }
            override fun property(name: String): String? = if (name == "baseware.name") "pono" else null
            override fun setFont(scope: Int, style: TextStyle) { log += "font:$style" }
            override fun setAlign(scope: Int, align: TextAlign) { log += "align:$align" }
            override fun setPassive(on: Boolean) { log += "passive:$on" }
            override fun reload(what: String) { log += "reload:$what" }
            override fun openUrl(url: String) {}
            override fun envVariable(name: String): String? = null
            override fun unknownTag(tag: Token.Tag) { log += "unknown:${tag.name}" }
        }
        ScriptPlayer(charWaitMs = 0).play(
            """\_u[0x3042]\_m[0x41]\&[amp]\&[#x42]%property[baseware.name]\![notify,OnN,1]\![embed,OnEmbed]""" +
                """\![timerraise,500,0,OnT,a]\![timerraise,0,1,OnT]\![get,property,OnP,system.year]""" +
                """\f[bold,1]太\f[color,255,0,0]赤\f[height,+2]\f[align,center]\f[default]\_a[ID]link\_a\q[x,script:\0hi]""" +
                """\![enter,passivemode]\![leave,passivemode]\![reload,shiori]\![lock,repaint]\![set,trayicon,x]\4\![open,foo]""",
            host,
        )
        assertEquals(
            listOf(
                "t0:あ", "t0:A", "t0:&", "t0:B", "t0:pono", "notify:OnN[1]", "t0:埋め", "t0:❤",
                "timer:500/false/OnT[a]", "timer:0/true/OnT[]", "prop:OnP[system.year]",
                "font:${TextStyle(bold = true)}", "t0:太",
                "font:${TextStyle(bold = true, color = 0xFF0000)}", "t0:赤",
                "font:${TextStyle(bold = true, color = 0xFF0000, height = 2f, heightMode = TextStyle.HeightMode.RELATIVE)}",
                "align:CENTER",
                "font:${TextStyle.Default}",
                "q:ID/true", "q:script:\\0hi/false",
                "passive:true", "passive:false", "reload:shiori", "unknown:!",
            ),
            log,
        )
    }
}
