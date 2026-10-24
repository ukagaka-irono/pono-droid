package net.pono.baseware.ghost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** 消去（vanish）・利用規約・読み直し・OnCloseAll・TeachBox の開け閉め・\a \+ \_+ */
class GhostLifecycleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 切り替えを頼まれたら覚えておくプラットフォーム */
    private class SwitchPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val switches: MutableList<Pair<String, PreviousGhost>> = Collections.synchronizedList(mutableListOf())

        override fun switchGhost(dirName: String, previous: PreviousGhost) {
            switches += dirName to previous
        }
    }

    private fun ghostIn(root: File, dir: String, name: String): Ghost {
        val g = File(root, dir)
        File(g, "ghost/master").mkdirs()
        File(g, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,$name\r\nsakura.name,${name}さくら\r\nshiori,yaya.dll\r\n")
        File(g, "shell/master").mkdirs()
        File(g, "shell/master/descript.txt").writeText("charset,UTF-8\r\nname,master\r\n")
        return Ghost(g)
    }

    private class Env(val c: GhostController, val shiori: ScriptedShiori, val platform: SwitchPlatform, val ghost: Ghost, val other: Ghost?)

    /** ghosts/ に me（と [withOther] なら next）を入れて me を起こす。[previous] があれば切り替わって来たことにする */
    private fun run(
        replies: Map<String, String>,
        withOther: Boolean = true,
        previous: PreviousGhost? = null,
        prepare: (Ghost) -> Unit = {},
        block: suspend Env.() -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope: CoroutineScope = testScope()
        try {
            val root = tmp.newFolder("ghosts")
            val ghost = ghostIn(root, "me", "わたし")
            val other = if (withOther) ghostIn(root, "next", "つぎ") else null
            prepare(ghost)
            if (previous != null) ghost.saveProfile(ghost.loadProfile().apply { setProperty("booted", "1") })
            val platform = SwitchPlatform()
            val c = GhostController(
                ghost = ghost,
                shell = Shell(ghost.shellDir()!!),
                shioriFactory = { _, _ -> shiori },
                scope = scope,
                platform = platform,
                repository = GhostRepository(root),
                player = ScriptPlayer(charWaitMs = 0),
                updateFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
                previous = previous,
            )
            c.start()
            Env(c, shiori, platform, ghost, other).block()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun GhostController.awaitChoice(id: String) = withTimeout(5000) {
        state.first { s -> s.waitingChoice && s.characters.values.any { ch -> ch.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == id } == true } }
    }

    private fun GhostController.choiceIds(): List<String> = state.value.characters.values.flatMap { ch ->
        ch.balloon.orEmpty().filterIsInstance<BalloonItem.ChoiceItem>().map { it.choice.id }
    }

    private fun GhostController.text(scope: Int) =
        state.value.characters[scope]?.balloon.orEmpty().filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    private suspend fun SwitchPlatform.awaitSwitch(): Pair<String, PreviousGhost> = withTimeout(5000) {
        while (switches.isEmpty()) delay(10)
        switches.single()
    }

    @Test
    fun vanishFromTheMenuAsksThenUninstallsAndSwitches() = run(
        mapOf("OnVanishSelecting" to """\0消えちゃうの？\e""", "OnVanishSelected" to """\0ばいばい\e"""),
    ) {
        shiori.awaitEvent("OnFirstBoot")
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:vanish")
        c.onChoice(Choice("消去", "pono:vanish", listOf("0")))
        shiori.awaitEvent("OnVanishSelecting")
        c.awaitChoice("pono:vanish-yes")
        assertTrue(c.text(0).startsWith("わたし を消去しますか？"))
        c.onChoice(Choice("消去する", "pono:vanish-yes", emptyList()))
        shiori.awaitEvent("OnVanishSelected")
        val (dir, previous) = platform.awaitSwitch()
        assertEquals("next", dir)
        assertEquals(PreviousGhost("わたしさくら", """\0ばいばい\e""", "わたし", ghost.dir.absolutePath + "/", vanished = true), previous)
        assertFalse(ghost.dir.exists())
        assertTrue(other!!.dir.exists())
    }

    @Test
    fun vanishCanBeCancelledOrHidden() = run(mapOf("vanishbuttonvisible" to "1")) {
        shiori.awaitEvent("OnFirstBoot")
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:vanish")
        c.onChoice(Choice("消去", "pono:vanish", listOf("0")))
        c.awaitChoice("pono:vanish-no")
        c.onChoice(Choice("やめる", "pono:vanish-no", emptyList()))
        shiori.awaitEvent("OnVanishCancel")
        assertTrue(ghost.dir.exists())
        assertTrue(platform.switches.isEmpty())
    }

    @Test
    fun vanishButtonIsHiddenWhenTheGhostSaysSo() = run(mapOf("vanishbuttonvisible" to "0")) {
        shiori.awaitEvent("OnFirstBoot")
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:close")
        assertFalse("pono:vanish" in c.choiceIds())
    }

    @Test
    fun theOnlyGhostCannotVanish() = run(mapOf("OnFirstBoot" to """\0\![vanishbymyself]\e"""), withOther = false) {
        shiori.awaitEvent("OnFirstBoot")
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:close")
        assertFalse("pono:vanish" in c.choiceIds())
        assertTrue(ghost.dir.exists())
        assertTrue(platform.inner.logs.any { "ほかにゴーストがいない" in it })
    }

    @Test
    fun tappingTheBalloonDuringOnVanishSelectingHoldsIt() = run(mapOf("OnVanishSelecting" to """\0ほんとに\_w[3000]消えるの\e""")) {
        shiori.awaitEvent("OnFirstBoot")
        c.onMouseLongPress(0, 0, 0, null)
        c.awaitChoice("pono:vanish")
        c.onChoice(Choice("消去", "pono:vanish", listOf("0")))
        withTimeout(5000) { c.state.first { it.talking && "ほんとに" in c.text(0) } }
        c.onBalloonClick(0)
        assertEquals(listOf("""\0ほんとに\_w[3000]消えるの\e""", "0", ""), shiori.awaitEvent("OnVanishButtonHold"))
        delay(300)
        assertFalse("pono:vanish-yes" in c.choiceIds())
        assertEquals(0, shiori.count("OnBalloonBreak"))
        assertTrue(ghost.dir.exists())
    }

    @Test
    fun vanishByMyselfRunsWithoutAsking() = run(mapOf("OnFirstBoot" to """\0さようなら\![vanishbymyself]\e""")) {
        val (dir, previous) = platform.awaitSwitch()
        assertEquals("next", dir)
        assertTrue(previous.vanished)
        assertEquals("""\0さようなら\![vanishbymyself]\e""", previous.script)
        assertEquals(0, shiori.count("OnVanishSelecting"))
        assertEquals(0, shiori.count("OnVanishSelected"))
        assertFalse(ghost.dir.exists())
    }

    @Test
    fun vanishByMyselfCanAskFirstButNeverRemovesAnotherGhost() = run(
        mapOf("OnFirstBoot" to """\0\![vanishbymyself,つぎ]\![vanishbymyself,わたし,--option=query]\e"""),
    ) {
        c.awaitChoice("pono:vanish-yes")
        assertTrue(platform.inner.logs.any { "ほかのゴーストは消去できません: つぎ" in it })
        assertTrue(other!!.dir.exists())
        c.onChoice(Choice("消去する", "pono:vanish-yes", emptyList()))
        assertEquals("next", platform.awaitSwitch().first)
        assertTrue(other.dir.exists())
        assertFalse(ghost.dir.exists())
    }

    @Test
    fun theNextGhostHearsOnVanishedAndFallsBackToOnBoot() = run(
        emptyMap(),
        previous = PreviousGhost("きえたさくら", """\0ばいばい\e""", "きえた", "/gone/", vanished = true),
    ) {
        assertEquals(listOf("きえたさくら", """\0ばいばい\e""", "きえた", "", "", "", "", "master"), shiori.awaitEvent("OnVanished"))
        shiori.awaitEvent("OnBoot")
        assertEquals(0, shiori.count("OnGhostChanged"))
    }

    @Test
    fun reloadGhostRestartsTheSameGhostWithoutEvents() = run(mapOf("OnFirstBoot" to """\0\![reload,ghost]\e""")) {
        val (dir, previous) = platform.awaitSwitch()
        assertEquals("me", dir)
        assertTrue(previous.reload)
        assertTrue(ghost.dir.exists())
    }

    @Test
    fun reloadedGhostSendsNoBootEvents() = run(emptyMap(), previous = PreviousGhost("わたしさくら", "", "わたし", "", reload = true)) {
        shiori.awaitEvent("OnSecondChange", 3000)
        assertEquals(0, shiori.count("OnBoot") + shiori.count("OnGhostChanged") + shiori.count("OnFirstBoot"))
    }

    @Test
    fun termsAreShownAndAnswered() = run(
        mapOf("OnFirstBoot" to """\0\![open,terms]\e""", "OnGhostTermsAccept" to """\0\![open,terms]\e"""),
        prepare = { File(it.masterDir, "terms.txt").writeText("第1条 \\と100%\r\n第2条 \\e も文字\r\n") },
    ) {
        c.awaitChoice("pono:terms")
        assertEquals("第1条 \\と100%第2条 \\e も文字", c.text(0))
        c.onChoice(Choice("同意する", "pono:terms", listOf("accept")))
        shiori.awaitEvent("OnGhostTermsAccept")
        delay(200)
        c.awaitChoice("pono:terms")
        c.onChoice(Choice("同意しない", "pono:terms", listOf("decline")))
        shiori.awaitEvent("OnGhostTermsDecline")
    }

    @Test
    fun teachBoxSendsStartAndCancel() = run(mapOf("OnFirstBoot" to """\0\![open,teachbox]\e""")) {
        shiori.awaitEvent("OnTeachStart")
        val input = withTimeout(5000) { c.state.first { it.input != null } }.input!!
        c.onInputCancel(input.serial)
        assertEquals(listOf("", "cancel"), shiori.awaitEvent("OnTeachInputCancel"))
    }

    @Test
    fun closingSendsOnCloseAllThenOnClose() = run(emptyMap()) {
        shiori.awaitEvent("OnFirstBoot")
        c.close("system", 1)
        assertEquals(listOf("system", "1", "1"), shiori.awaitEvent("OnCloseAll"))
        assertEquals(listOf("system"), shiori.awaitEvent("OnClose"))
    }

    @Test
    fun onCloseAllAnswerReplacesOnClose() = run(mapOf("OnCloseAll" to """\0みんなおやすみ\e""")) {
        shiori.awaitEvent("OnFirstBoot")
        c.close()
        assertEquals(listOf("user", "0", "0"), shiori.awaitEvent("OnCloseAll"))
        delay(300)
        assertEquals(0, shiori.count("OnClose"))
    }

    @Test
    fun aiTalkAndGhostSwitchTags() = run(mapOf("OnFirstBoot" to """\0\a\e""", "OnAITalk" to """\0\_+\e""")) {
        shiori.awaitEvent("OnAITalk")
        assertEquals("next", platform.awaitSwitch().first)
        assertEquals(0, shiori.count("OnGhostChanging"))
    }

    @Test
    fun reloadShellBalloonAndShiori() = run(
        mapOf("OnFirstBoot" to """\0\![reloadsurface]\![reload,shell]\![reload,balloon]\![unload,shiori]\_w[100]\![load,shiori]\![change,calendarskin,x]\![reload,makoto]\e"""),
    ) {
        shiori.awaitEvent("OnFirstBoot")
        withTimeout(5000) { while (shiori.count("OnNotifyUserInfo") < 2) delay(10) }
        withTimeout(5000) { while (platform.inner.shellChanges.size < 2) delay(10) }
        assertEquals(listOf("master", "master"), platform.inner.shellChanges.toList())
        // 簡易バルーンなので読み直すものが無い
        assertTrue(platform.inner.balloonChanges.isEmpty())
        assertFalse(platform.inner.logs.any { it.startsWith("未対応") })
    }
}
