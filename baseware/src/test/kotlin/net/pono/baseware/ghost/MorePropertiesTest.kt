package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.sakura.SoundOptions
import net.pono.baseware.shell.Shell
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.util.Collections

/** プロパティシステムの残り（端末・ウィンドウ・音・履歴・使用率・開発の記録）と \![set,property] */
class MorePropertiesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class PropPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val soundCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var sounds: List<SoundStatus> = emptyList()

        override fun windowGeometry(scope: Int) =
            if (scope == 0) WindowGeometry(ScreenRect(100, 200, 300, 600), ScreenRect(120, 50, 400, 190), 1.5, 1.25) else null

        override fun diskInfo() = listOf(DiskInfo("/data", "fixed", 64_000, 12_000), DiskInfo("/storage/emulated/0", "removable", 128_000, 100_000))

        override fun soundStatus() = sounds

        override fun imageMainColor(file: File): Int? = if (file.name == "balloons0.png") 0x336699 else null

        override fun soundControl(command: String, file: File?, options: SoundOptions) {
            soundCalls += "$command:${file?.name}:${options.seekMs}:${options.seekRelative}"
        }

        override fun stopSound(file: File?) {
            soundCalls += "stop:${file?.name}"
        }

        override fun playSound(file: File, loop: Boolean, options: SoundOptions) {
            soundCalls += "play:${file.name}:$loop"
        }
    }

    private class Env(val c: GhostController, val shiori: ScriptedShiori, val platform: PropPlatform, val ghost: Ghost, val root: File)

    private fun run(
        replies: Map<String, String> = emptyMap(),
        prepare: (File) -> Unit = {},
        withBalloon: Boolean = false,
        block: suspend Env.() -> Unit,
    ) = runBlocking<Unit> {
        val shiori = ScriptedShiori(replies)
        val scope = testScope()
        try {
            val root = tmp.newFolder("ghosts")
            val g = File(root, "me")
            File(g, "ghost/master").mkdirs()
            File(g, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,わたし\r\nsakura.name,さくら\r\nshiori,yaya.dll\r\n")
            for (shell in listOf("master", "summer")) {
                File(g, "shell/$shell").mkdirs()
                File(g, "shell/$shell/descript.txt").writeText("charset,UTF-8\r\nname,$shell\r\n" + if (shell == "summer") "menu,hidden\r\nsakura.menu,auto\r\n" else "")
            }
            // 幅 3・高さ 7 の PNG の頭
            File(g, "shell/master/surface0.png").writeBytes(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 3, 0, 0, 0, 7, 8, 6, 0, 0, 0),
            )
            File(g, "shell/master/surfaces.txt").writeText("charset,UTF-8\r\nsurface0\r\n{\r\ncollision0,0,0,2,2,Head\r\n}\r\nsurface5\r\n{\r\nelement0,overlay,surface0.png,0,0\r\n}\r\nsakura.tooltips\r\n{\r\nHead,あたま\r\n}\r\n")
            prepare(root)
            val balloon = if (withBalloon) {
                val dir = File(root.parentFile, "balloon/maid").apply { mkdirs() }
                File(dir, "descript.txt").writeText("charset,UTF-8\r\nname,maid\r\norigin.x,10\r\norigin.y,10\r\nvalidrect.right,-10\r\nvalidrect.bottom,-10\r\nfont.height,20\r\n")
                // 幅 200・高さ 100 の PNG の頭
                File(dir, "balloons0.png").writeBytes(
                    byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, -56, 0, 0, 0, 100, 8, 6, 0, 0, 0),
                )
                Balloon(dir)
            } else {
                null
            }
            val ghost = Ghost(g)
            val platform = PropPlatform()
            platform.inner.system = SystemInfo(darkTheme = true, doNotDisturb = true)
            platform.inner.network = NetworkStatus(online = true, addresses = listOf("192.168.0.5"), type = "wifi", maxMbps = 433.0, effectiveMbps = 12.5, metering = "unrestricted")
            val c = GhostController(
                ghost = ghost,
                shell = Shell(ghost.shellDir()!!),
                shioriFactory = { _, _ -> shiori },
                scope = scope,
                platform = platform,
                repository = GhostRepository(root),
                balloon = balloon,
                player = ScriptPlayer(charWaitMs = 0),
                updateFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
            )
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            Env(c, shiori, platform, ghost, root).block()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deviceProperties() = run {
        assertEquals("light", c.property("system.theme.app.mode"))
        assertEquals("dark", c.property("system.theme.os.mode"))
        assertEquals("quiet", c.property("system.dnd.mode"))
        assertEquals(listOf("online", "192.168.0.5", "wifi", "unrestricted", "433", "12.5"),
            listOf("status", "ipaddress", "type", "cost", "downlink", "downlink.estimate").map { c.property("system.network.$it") })
        assertEquals("2", c.property("system.disk.count"))
        assertEquals(listOf("/storage/emulated/0", "removable", "128000", "100000"),
            listOf("mountpoint", "type", "total", "free").map { c.property("system.disk.index(1).$it") })
        assertNull(c.property("system.disk.index(2).type"))
    }

    @Test
    fun windowAndSurfaceProperties() = run {
        assertEquals("100,200,300,600", c.property("currentghost.scope(0).rect"))
        assertEquals(listOf("200", "600", "100", "200", "150"), listOf("x", "y", "surface.x", "surface.y", "scaling").map { c.property("currentghost.scope(0).$it") })
        assertEquals("0,0,3,7", c.property("currentghost.scope(0).surface(0).rect"))
        assertEquals("120,50,400,190", c.property("currentghost.balloon.scope(0).rect"))
        assertEquals("125", c.property("currentghost.balloon.scope(0).scaling"))
        assertEquals("0,0,0,0", c.property("currentghost.scope(1).rect"))
        assertEquals("0,5", c.property("currentghost.seriko.surfacelist.all"))
        assertEquals("0,5", c.property("currentghost.seriko.surfacelist.defined"))
        assertEquals("1", c.property("currentghost.seriko.tooltip.scope(0).textlist.count"))
        assertEquals("あたま", c.property("currentghost.seriko.tooltip.scope(0).textlist(Head).text"))
        assertEquals("Head", c.property("currentghost.seriko.tooltip.scope(0).textlist.index(0).name"))
    }

    @Test
    fun balloonTextAreaAndColour() = run(withBalloon = true) {
        assertEquals(listOf("180", "80", "180", "80"),
            listOf("validwidth", "validheight", "validwidth.initial", "validheight.initial").map { c.property("currentghost.balloon.scope(0).$it") })
        // 1行は文字の高さの 1.4 倍（20 → 28）なので、80 には 2 行
        assertEquals("2", c.property("currentghost.balloon.scope(0).lines"))
        assertEquals("10", c.property("currentghost.balloon.scope(0).char_width"))
        assertEquals("51,102,153", c.property("currentghost.balloon.scope(0).background.color"))
        assertEquals("yaya.dll", c.property("currentghost.shiori"))
        assertEquals("yaya.dll", c.property("ghostlist.index(0).shiori"))
    }

    @Test
    fun simpleBalloonIsWhite() = run {
        assertEquals("255,255,255", c.property("currentghost.balloon.scope(0).background.color"))
        assertNull(c.property("currentghost.balloon.scope(0).validwidth"))
    }

    @Test
    fun usbDevicesComeAndGo() = run {
        val keyboard = ConnectedDevice("usb", "Keyboard", "ACME", "/dev/bus/usb/001/002")
        c.onDevicesArrived(emptyList())
        c.onDevicesArrived(listOf(keyboard))
        assertEquals(listOf("usb\u0001Keyboard\u0001ACME\u0001/dev/bus/usb/001/002"), shiori.awaitEvent("OnDeviceArrival"))
        c.onDevicesRemoved(listOf(keyboard.copy(name = "Key\nboard")))
        assertEquals(listOf("usb\u0001Key board\u0001ACME\u0001/dev/bus/usb/001/002"), shiori.awaitEvent("OnDeviceRemove"))
        assertEquals(1, shiori.count("OnDeviceArrival"))
    }

    @Test
    fun writableProperties() = run(
        mapOf(
            "OnFirstBoot" to """\0\![set,property,currentghost.seriko.sticky-window,0,1]""" +
                """\![set,property,currentghost.seriko.tooltip.scope(0).textlist(Head).text,なでる]""" +
                """\![set,property,currentghost.shelllist(summer).menu,]\![set,property,currentghost.kero.bind.menu,hidden]""" +
                """\![set,property,currentghost.mousecursor.text,x.cur]\![set,property,system.year,1999]\e""",
        ),
    ) {
        withTimeout(5000) { c.state.first { !it.talking } }
        // カンマはタグの区切りなので、sticky-window は SakuraScript から "0,1" と渡せない（\![set,property] の値は1つだけ）
        assertTrue(c.setProperty("currentghost.seriko.sticky-window", "0,1;2,3;1,4"))
        assertEquals("0,1;2,3", c.property("currentghost.seriko.sticky-window"))
        assertTrue(c.setProperty("currentghost.seriko.zorder", "s0,b0;s1,b1"))
        assertEquals("s0,b0;s1,b1", c.property("currentghost.seriko.zorder"))
        assertFalse(c.setProperty("currentghost.seriko.zorder", "s0,b0;2,3"))
        assertEquals("なでる", c.tooltip(0, "Head"))
        assertEquals("", c.property("currentghost.shelllist(summer).menu"))
        assertEquals("hidden", c.property("currentghost.kero.bind.menu"))
        assertEquals("none", c.property("currentghost.sakura.bind.menu"))
        assertEquals("x.cur", c.property("currentghost.mousecursor.text"))
        assertTrue(platform.inner.logs.any { it.startsWith("未対応のタグ") && "system.year" in it })
    }

    @Test
    fun hiddenShellsStayOutOfTheMenu() = run {
        assertEquals("hidden", c.property("currentghost.shelllist(summer).menu"))
        c.onMouseLongPress(0, 0, 0, null)
        withTimeout(5000) { c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == "pono:shells" } == true } }
        c.onChoice(net.pono.baseware.sakura.Choice("シェル", "pono:shells", listOf("0")))
        val ids = withTimeout(5000) {
            c.state.first { s -> s.characters[0]?.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == "pono:shell" } == true }
        }.characters[0]!!.balloon!!.filterIsInstance<BalloonItem.ChoiceItem>().map { it.choice.references }
        assertEquals(listOf(listOf("master")), ids.filter { it.isNotEmpty() && it != listOf("0") })
    }

    @Test
    fun soundProperties() = run {
        val file = File(ghost.masterDir, "bgm.ogg").canonicalFile
        platform.sounds = listOf(SoundStatus(file, 7, 1500, 60_000, playing = true, paused = false, loop = true, preload = false))
        assertEquals("1", c.property("currentghost.sound.count"))
        assertEquals(listOf("bgm.ogg", "7", "1500", "60000", "1", "0", "1", "0"),
            listOf("name", "id", "position", "duration", "playing", "pause", "loop", "preload").map { c.property("currentghost.sound.index(0).$it") })
        assertEquals("1500", c.property("currentghost.sound(bgm.ogg).position"))
        assertTrue(c.setProperty("currentghost.sound(bgm.ogg).position", "@-500"))
        assertTrue(c.setProperty("currentghost.sound.index(0).pause", "1"))
        assertTrue(c.setProperty("currentghost.sound(bgm.ogg).playing", "0"))
        assertEquals(listOf("option:bgm.ogg:-500:true", "pause:bgm.ogg:null:false", "stop:bgm.ogg"), platform.soundCalls.toList())
    }

    @Test
    fun historyAndRateOfUse() = run(prepare = { root ->
        val other = File(root, "other")
        File(other, "ghost/master").mkdirs()
        File(other, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,ほか\r\nsakura.name,ほかのこ\r\n")
        val h = UsageHistory(File(root.parentFile, "pono/history.properties"))
        h.ghostBooted("other")
        repeat(30) { h.ghostMinute("other") }
    }) {
        assertEquals("2", c.property("history.ghost.count"))
        assertEquals("わたし", c.property("history.ghost.index(0).name"))
        assertEquals("ほか", c.property("history.ghost.index(1).name"))
        assertEquals("ほかのこ", c.property("rateofuselist.index(0).sakuraname"))
        assertEquals("30", c.property("rateofuselist.index(0).bootminute"))
        // 分が変わるところをまたぐと、起動中のこのゴーストにも1分つく
        assertTrue(c.property("rateofuselist.index(0).percent")!!.toDouble() >= 96.0)
        assertEquals("1", c.property("rateofuselist.index(1).boottime"))
        assertEquals("0", c.property("rateofuselist.index(1).percentweekly"))
    }

    @Test
    fun usageKeepsOnlyRecentDays() {
        var today = LocalDate.of(2026, 10, 1)
        val file = File(tmp.root, "h.properties")
        val h = UsageHistory(file) { today }
        h.ghostBooted("a")
        repeat(5) { h.ghostMinute("a") }
        today = LocalDate.of(2026, 10, 20)
        h.ghostMinute("a")
        val u = UsageHistory(file) { today }.usage().single()
        assertEquals(6, u.minutes)
        assertEquals(1, u.minutes(7, today))
        assertEquals(6, u.minutes(30, today))
        today = LocalDate.of(2026, 12, 1)
        h.ghostBooted("a")
        assertEquals(0, UsageHistory(file) { today }.usage().single().minutes(30, today))
    }

    @Test
    fun developerLogs() = run(mapOf("OnFirstBoot" to """\0\![zzz]\e""")) {
        withTimeout(5000) { while (c.developerLog.entries("error").isEmpty()) delay(10) }
        assertEquals("1", c.property("developer.log.script.count"))
        assertEquals("""\0\![zzz]\e""", c.property("developer.log.script.index(0).value"))
        assertEquals("Ghost", c.property("developer.log.script(わたし).type"))
        assertEquals("1", c.property("developer.log.script(わたし).count"))
        assertEquals("Warning", c.property("developer.log.error.index(0).type"))
        assertTrue(c.property("developer.log.error.index(0).value")!!.startsWith("未対応のタグ"))
        assertTrue(Regex("""\d{4}/\d\d/\d\d \d\d:\d\d""").matches(c.property("developer.log.script.index(0).time")!!))
        assertEquals("0", c.property("developer.log.network.count"))
    }
}
