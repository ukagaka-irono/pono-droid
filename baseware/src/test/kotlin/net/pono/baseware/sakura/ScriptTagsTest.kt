package net.pono.baseware.sakura

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ukadoc-spec に合わせたタグ（\x・\__w・\*・\__t・\__c・\b の fallback・\j・\f の default / disable・古い \q など） */
class ScriptTagsTest {
    private class Host : ScriptHost {
        val log = mutableListOf<String>()
        var style: TextStyle = TextStyle.Default
        override fun setSurface(scope: Int, surface: Int) {}
        override fun appendText(scope: Int, text: String) { log += "t$scope:$text" }
        override fun newLine(scope: Int) { log += "n$scope" }
        override fun clearBalloon(scope: Int) { log += "clear$scope" }
        override fun clearAllBalloons() { log += "clearall" }
        override fun addChoice(scope: Int, choice: Choice) { log += "q:${choice.title}/${choice.id}/${choice.anchor}" }
        override suspend fun waitClick() { log += "click" }
        override fun raise(event: String, references: List<String>) { log += "raise:$event" }
        override fun openUrl(url: String) { log += "url:$url" }
        override fun envVariable(name: String): String? = null
        override fun open(kind: String, args: List<String>): Boolean {
            log += "open:$kind$args"
            return true
        }
        override fun disableChoiceTimeout() { log += "nochoicetimeout" }
        override fun setBalloonIds(scope: Int, candidates: List<Int>) { log += "balloon$scope:$candidates" }
        override fun change(kind: String, name: String, raiseEvent: Boolean): Boolean {
            log += "change:$kind/$name/$raiseEvent"
            return true
        }
        override fun bind(scope: Int, category: String, part: String, on: Boolean?, noEvent: Boolean) { log += "bind:$category/$part/$on/$noEvent" }
        override fun setFont(scope: Int, style: TextStyle) { this.style = style }
        override fun unknownTag(tag: Token.Tag) { log += "unknown:${tag.name}" }
    }

    private fun play(script: String) = Host().also { h -> kotlinx.coroutines.runBlocking { ScriptPlayer(charWaitMs = 0).play(script, h) } }

    @Test
    fun clickResetsToSakuraAndClearsEveryBalloon() {
        val h = play("""\1あ\x\b[2,--fallback=4]い""")
        assertEquals(listOf("t1:あ", "click", "clearall", "balloon0:[2, 4]", "t0:い"), h.log)
    }

    @Test
    fun boxesNoTimeoutLinksAndOpen() {
        val h = play("""\*\__t\__c\j[file:///readme.txt]\j[mailto:a@example.test]\j[javascript:x]""")
        assertEquals(listOf("nochoicetimeout", "open:teachbox[]", "open:communicatebox[]", "open:file[readme.txt]", "url:mailto:a@example.test"), h.log)
    }

    @Test
    fun linksAloneAreNotChoices() = runTest {
        val h = Host()
        assertFalse(ScriptPlayer(charWaitMs = 0).play("""\_a[OnA]リンク\_a""", h).hasChoices)
        assertTrue(ScriptPlayer(charWaitMs = 0).play("""\__q[OnQ]選ぶ\__q""", h).hasChoices)
    }

    @Test
    fun oldChoiceFormBreaksTheLine() {
        val h = play("""\q[OnYes][はい]\q0[OnNo][いいえ]\q[今の,OnNow]""")
        assertEquals(listOf("q:はい/OnYes/false", "n0", "q:いいえ/OnNo/false", "n0", "q:今の/OnNow/false"), h.log)
    }

    @Test
    fun longWaitCountsFromTheStart() = kotlinx.coroutines.runBlocking {
        val h = Host()
        val started = System.currentTimeMillis()
        // \_w で 200 待ったあとの \__w[300] は、残りの 100 だけ待つ
        ScriptPlayer(charWaitMs = 0).play("""\_w[200]\__w[300]""", h)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("elapsed $elapsed", elapsed in 290..1500)
    }

    @Test
    fun fontFlagsDefaultDisableAndColours() {
        assertEquals(false, play("""\f[bold,0]""").style.bold)
        assertEquals(null, play("""\f[bold,1]\f[bold,default]""").style.bold)
        assertEquals(setOf("italic"), play("""\f[italic,disable]""").style.disabledFlags)
        assertEquals("anchorvisited", play("""\f[color,default.anchorvisited]""").style.colorRef)
        assertEquals(0x6495ED, play("""\f[color,cornflowerblue]""").style.color)
        // 読めない色は無視する
        assertEquals(0xFF0000, play("""\f[color,red]\f[color,nosuchcolor]""").style.color)
        val big = play("""\f[height,xx-large]""").style
        assertEquals(200f, big.height)
        assertEquals(TextStyle.HeightMode.PERCENT, big.heightMode)
    }

    @Test
    fun changeAndBindOptions() {
        val h = play("""\![change,shell,lastinstalled,--option=raise-event]\![bind,arm,,0]\![bind-noevent,head,ribbon,1]\![bind,head,ribbon]""")
        assertEquals(
            listOf("change:shell/lastinstalled/true", "bind:arm//false/false", "bind:head/ribbon/true/true", "bind:head/ribbon/null/false"),
            h.log,
        )
    }
}
