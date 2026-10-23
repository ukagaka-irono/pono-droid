package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.TextStyle
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** SSP と同じイベント・タグ・プロパティが届くこと */
class SspCompatibilityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun GhostController.awaitText(scope: Int = 0): String = withTimeout(5000) {
        state.first { s -> s.characters[scope]?.balloon?.any { it is BalloonItem.Text } == true }
    }.characters[scope]!!.balloon!!.filterIsInstance<BalloonItem.Text>().joinToString("") { it.text }

    /**
     * [id] の選択肢が出て、選ばれるのを待つ状態になるまで待つ。
     * 選択肢は話している途中に出るが、選べるのは話し終わって待ちに入ってから（それより前に選ぶと捨てられる）
     */
    private suspend fun GhostController.awaitChoices(id: String) = withTimeout(5000) {
        state.first { s -> s.waitingChoice && s.characters[0]?.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == id } == true }
    }

    @Test
    fun scriptsGoThroughOnTranslate() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0元の文""", "OnTranslate" to """\0訳した文"""))
        val scope = testScope()
        try {
            val controller = testController(tmp.newTestGhost(), shiori, scope)
            controller.start()
            assertEquals("訳した文", controller.awaitText())
            assertEquals(listOf("""\0元の文"""), shiori.awaitEvent("OnTranslate"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun bootNotifiesEnvironmentAndUsesShioriResources() = runBlocking {
        val shiori = ScriptedShiori(mapOf("homeurl" to "https://shiori.test/g/", "username" to "太郎", "OnFirstBoot" to """\0%usernameさん"""))
        val platform = RecordingPlatform().apply {
            device = DeviceInfo(osVersion = "14", arch = "arm64-v8a", cpuCount = 8, memoryTotalMb = 4096, memoryFreeMb = 1024, uptimeSeconds = 3600, screenWidth = 1080, screenHeight = 2400, dpi = 420)
            battery = BatteryInfo(percent = 80, charging = true, plugged = true)
        }
        val requested = mutableListOf<String>()
        val fetcher = HttpFetcher { url, _, _ -> requested += url; FetchResult.HttpError(404) }
        val scope = testScope()
        try {
            val controller = testController(tmp.newTestGhost(), shiori, scope, platform, fetcher)
            controller.start()
            assertEquals("太郎さん", controller.awaitText())
            val ids = shiori.events.map { it.first }
            for (expected in listOf("OnNotifySelfInfo", "OnNotifyBalloonInfo", "OnNotifyShellInfo", "OnNotifyUserInfo", "OnNotifyOSInfo", "OnDisplayChange", "OnNotifyInternationalInfo", "OnBatteryNotify")) {
                assertTrue(expected, expected in ids)
            }
            assertEquals(listOf("Android,14,Android 14", "arm64-v8a,0,", "4194304,4194304", "60"), shiori.awaitEvent("OnNotifyOSInfo"))
            assertEquals(listOf("32", "1080", "2400"), shiori.awaitEvent("OnDisplayChange"))
            assertEquals(listOf("80", "-1", "online", "high,charging"), shiori.awaitEvent("OnBatteryNotify"))
            assertEquals(listOf("太郎", "", "", "undef"), shiori.awaitEvent("OnNotifyUserInfo"))

            // ネットワーク更新は SHIORI の homeurl を使う
            controller.networkUpdate()
            shiori.awaitEvent("OnUpdateFailure")
            assertEquals(listOf("https://shiori.test/g/updates2.dau", "https://shiori.test/g/updates.txt"), requested)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anchorsUseAnchorEventsAndScriptChoicesRunInline() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\_a[ID1,x]link\_a\q[直接,script:\0直接の文]""", "OnAnchorSelect" to """\0選んだ"""))
        val scope = testScope()
        try {
            val controller = testController(tmp.newTestGhost(), shiori, scope)
            controller.start()
            withTimeout(5000) { controller.state.first { it.waitingChoice } }
            controller.onChoice(Choice("link", "ID1", listOf("x"), anchor = true))
            // OnAnchorSelectEx に答えが無ければ OnAnchorSelect
            assertEquals(listOf("link", "ID1", "x"), shiori.awaitEvent("OnAnchorSelectEx"))
            assertEquals(listOf("ID1", "x"), shiori.awaitEvent("OnAnchorSelect"))
            assertEquals("選んだ", controller.awaitText())

            controller.onBalloonClick(0)
            shiori.awaitEvent("OnBalloonClose")
            controller.raise("OnFirstBoot", emptyList())
            withTimeout(5000) { controller.state.first { it.waitingChoice } }
            controller.onChoice(Choice("直接", "script:\\0直接の文"))
            assertEquals("直接の文", controller.awaitText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mouseUpSuppressesClickWhenAnswered() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnMouseUp" to """\0押した"""))
        val scope = testScope()
        try {
            val controller = testController(tmp.newTestGhost(), shiori, scope)
            controller.start()
            shiori.awaitEvent("OnFirstBoot")
            controller.onMouseDown(0, 1, 2, "Head")
            assertEquals(listOf("1", "2", "0", "0", "Head", "0", "touch"), shiori.awaitEvent("OnMouseDown"))
            controller.onMouseUp(0, 1, 2, "Head", 1)
            assertEquals("押した", controller.awaitText())
            delay(200)
            assertEquals(0, shiori.count("OnMouseClick"))
            // 答えが無ければ OnMouseClick。ダブルクリックは答えの有無によらず送る
            controller.onMouseUp(0, 1, 2, "Head", 2)
            shiori.awaitEvent("OnMouseDoubleClick")
            Unit
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun surfaceChangesAndBalloonEventsAreReported() = runBlocking {
        val shiori = ScriptedShiori(
            mapOf("OnFirstBoot" to """\0\s[5]\1\s[12]ok""", "OnBalloonTimeout" to """\0time""", "OnBreakTest" to """\0長い\_w[3000]話"""),
        )
        val scope = testScope()
        try {
            val controller = GhostController(
                ghost = tmp.newTestGhost(),
                shell = net.pono.baseware.shell.Shell(File(tmp.root, "ghost/shell/master")),
                shioriFactory = { _, _ -> shiori },
                scope = scope,
                platform = RecordingPlatform(),
                player = net.pono.baseware.sakura.ScriptPlayer(charWaitMs = 0),
                balloonTimeoutMs = 300,
            )
            controller.start()
            assertEquals("ok", controller.awaitText(1))
            val changes = withTimeout(5000) {
                while (shiori.count("OnSurfaceChange") < 2) delay(10)
                shiori.events.filter { it.first == "OnSurfaceChange" }.map { it.second }
            }
            assertEquals(listOf("5", "10", "0,5,0,0"), changes[0])
            assertEquals(listOf("5", "12", "1,12,0,0"), changes[1])
            // バルーンが時間で消えたら OnBalloonTimeout
            assertEquals(listOf("""\0\s[5]\1\s[12]ok""", "0"), shiori.awaitEvent("OnBalloonTimeout"))
            assertEquals("time", controller.awaitText())
            // 話している途中にバルーンを叩くと OnBalloonBreak
            controller.onBalloonClick(0)
            shiori.awaitEvent("OnBalloonClose")
            controller.raise("OnBreakTest", emptyList())
            assertEquals("長い", controller.awaitText())
            controller.onBalloonClick(0)
            assertEquals(listOf("""\0長い\_w[3000]話""", "0", ""), shiori.awaitEvent("OnBalloonBreak"))
            assertFalse(controller.state.value.talking)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun menuShowsShioriSitesAndShellsCanBeSwitched() = runBlocking {
        val ghost = tmp.newTestGhost()
        // 2つ目のシェル
        val second = File(ghost.dir, "shell/second").apply { mkdirs() }
        File(second, "descript.txt").writeText("charset,UTF-8\r\nname,二つ目\r\n")
        File(ghost.masterDir, "se.wav").writeBytes(ByteArray(4))
        val sites = "公式\u0001https://a.test/\u0001\u0001\u0002-\u0002掲示板\u0001script:\\0板\u0001\u0001\\0開いた"
        val shiori = ScriptedShiori(
            mapOf(
                "sakura.recommendsites" to sites, "sakura.recommendbuttoncaption" to "おすすめ！", "readmebuttoncaption" to "説明書",
                "OnRecommendsiteChoice" to """\0サイトだ""",
                "OnShellChanging" to """\0着替える""",
                "OnFirstBoot" to """\8[se.wav]\![change,balloon,random]""",
            ),
        )
        val platform = RecordingPlatform()
        val scope = testScope()
        try {
            val controller = testController(ghost, shiori, scope, platform)
            controller.start()
            shiori.awaitEvent("OnFirstBoot")
            delay(200)
            assertEquals(listOf("se.wav/false/null"), platform.sounds)

            // 長押しのメニュー: SHIORI のおすすめとボタン名、シェルの切り替えが並ぶ
            controller.onMouseLongPress(0, 0, 0, null)
            withTimeout(5000) { controller.state.first { it.waitingChoice } }
            val menu = controller.state.value.characters[0]!!.balloon!!.filterIsInstance<BalloonItem.ChoiceItem>().map { it.choice.title to it.choice.id }
            assertTrue(menu.toString(), ("おすすめ！" to "pono:sites") in menu)
            assertTrue(menu.toString(), ("シェル" to "pono:shells") in menu)
            assertFalse(menu.toString(), menu.any { it.second == "pono:readme" })

            // おすすめ → 2つ目（区切りの次）を選ぶと OnRecommendsiteChoice が送られ、script: の URL は再生される
            controller.onChoice(Choice("おすすめ！", "pono:sites", listOf("recommend", "0")))
            controller.awaitChoices("pono:site")
            controller.onChoice(Choice("掲示板", "pono:site", listOf("recommend", "0", "2")))
            assertEquals(listOf("掲示板", "script:\\0板", "", "recommend", "0", "2"), shiori.awaitEvent("OnRecommendsiteChoice"))
            // 答えのトーク（サイトだ）のあとに script: の URL が再生される
            withTimeout(5000) {
                controller.state.first { s -> s.characters[0]?.balloon?.filterIsInstance<BalloonItem.Text>()?.joinToString("") { it.text } == "板" }
            }

            // シェルの切り替え（メニューから）: OnShellChanging → 切り替え → OnShellChanged
            controller.onMouseLongPress(0, 0, 0, null)
            controller.awaitChoices("pono:shells")
            controller.onChoice(Choice("シェル", "pono:shells", listOf("0")))
            controller.awaitChoices("pono:shell")
            controller.onChoice(Choice("二つ目", "pono:shell", listOf("second")))
            assertEquals(listOf("二つ目", "master", second.absolutePath + "/"), shiori.awaitEvent("OnShellChanging"))
            assertEquals(listOf("二つ目", "テスト", second.absolutePath + "/"), shiori.awaitEvent("OnShellChanged"))
            assertEquals(listOf("second"), platform.shellChanges)
            assertEquals("second", controller.shell.dir.name)
            assertEquals("second", ghost.shellDir()?.name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun dateAndSliderInputsAnswerLikeSsp() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\![open,dateinput,誕生日,0,2000,1,2]""", "OnUserInput" to """\![open,sliderinput,OnSlider,0,5,0,10]"""))
        val scope = testScope()
        try {
            val controller = testController(tmp.newTestGhost(), shiori, scope)
            controller.start()
            val date = withTimeout(5000) { controller.state.first { it.input != null } }.input!!
            assertEquals(InputRequest.Kind.DATE, date.kind)
            assertEquals(listOf(2000, 1, 2), date.values)
            controller.onInputSubmit(date.serial, "2001,3,4")
            assertEquals(listOf("誕生日", "2001,3,4"), shiori.awaitEvent("OnUserInput"))
            val slider = withTimeout(5000) { controller.state.first { it.input?.kind == InputRequest.Kind.SLIDER } }.input!!
            controller.onInputSubmit(slider.serial, "7")
            // ID が On で始まるので、そのイベントに 値 と「最小,最大」
            assertEquals(listOf("7", "0,10"), shiori.awaitEvent("OnSlider"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun thirdCharactersAreShownFromDescriptAndScripts() = runBlocking {
        // Emily/Phase4.5 のエミリオのように、ゴーストの descript.txt に char2 がある
        val ghost = tmp.newTestGhost(extraDescript = "char2.name,Emilio\r\nchar2.seriko.defaultsurface,200\r\n")
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\p[2]\s[205]やあ\p[4]\s[3]\p[99999]\s[1]"""))
        val scope = testScope()
        try {
            val controller = testController(ghost, shiori, scope)
            assertEquals(listOf(0, 1, 2), controller.characterScopes)
            assertEquals(200, controller.defaultSurface(2))
            controller.start()
            assertEquals("やあ", controller.awaitText(2))
            // 番号の上限を超えた \p は無視して、直前のキャラクター（\p[4]）のまま \s[1] を受ける
            withTimeout(5000) { controller.state.first { it.characters[4]?.surface == 1 } }
            val chars = controller.state.value.characters
            assertEquals(205, chars[2]!!.surface)
            assertFalse(99999 in chars)
            assertEquals("Emilio", controller.property("currentghost.scope(2).name"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun everyAnimationRequestInAScriptIsKept() = runBlocking {
        // 1つのスクリプトの \i をすべて、\![anim,...] と一緒に出された順に残す
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\i[1]\i[2]\![anim,offset,2,5,6]\i[3]\![anim,clear,1]\![anim,stop]\![anim,pause,3]ok"""))
        val scope = testScope()
        try {
            val controller = testController(tmp.newTestGhost(), shiori, scope)
            controller.start()
            controller.awaitText()
            val commands = controller.state.value.characters[0]!!.animationCommands
            assertEquals(
                listOf(
                    AnimationCommand.Start(1), AnimationCommand.Start(2), AnimationCommand.Offset(2, 5, 6),
                    AnimationCommand.Start(3), AnimationCommand.Clear(1), AnimationCommand.StopAll,
                ),
                commands.map { it.second },
            )
            // 通し番号は増え続ける（表示する側はまだ実行していないものだけを実行する）
            assertEquals(commands.map { it.first }.sorted(), commands.map { it.first })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun timerRaisePassiveModeAndProperties() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\![timerraise,100,1,OnTimerTest,a]\![enter,passivemode]\f[bold,1]太い\![open,readme]"""))
        val platform = RecordingPlatform()
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            File(ghost.masterDir, "readme.txt").writeText("説明")
            val controller = testController(ghost, shiori, scope, platform)
            controller.start()
            assertEquals(listOf("a"), shiori.awaitEvent("OnTimerTest"))
            withTimeout(5000) { controller.state.first { it.passive } }
            // パッシブモードでは「話せる」が 0
            assertEquals("0", shiori.awaitEvent("OnSecondChange")[3])
            val bold = controller.state.value.characters[0]!!.balloon!!.filterIsInstance<BalloonItem.Text>().first { it.text == "太い" }
            assertEquals(TextStyle(bold = true), bold.style)
            assertEquals(listOf("テスト" to "説明"), platform.texts)

            assertEquals("pono-droid", controller.property("baseware.name"))
            assertEquals("テスト", controller.property("currentghost.name"))
            assertEquals("1", controller.property("ghostlist.count"))
            assertEquals("テスト", controller.property("ghostlist.index(0).name"))
            assertEquals("2", controller.property("currentghost.scope.count"))
            assertEquals("master", controller.property("currentghost.shelllist.current.name"))
            assertEquals("talking,passive".takeIf { controller.state.value.talking } ?: "passive", controller.property("currentghost.status"))
            assertEquals(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString(), controller.property("system.year"))
            assertFalse(controller.property("system.os.locale").isNullOrEmpty())
        } finally {
            scope.cancel()
        }
    }
}
