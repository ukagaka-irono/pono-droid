package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.MoveCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 指の出入り・ドラッグ・ジェスチャー・中クリックと、ウィンドウにかかること（最小化・重なり順・一緒に動く組など） */
class TouchAndWindowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 起動してから [block] を実行する。スクリプトは OnTest などのイベントの答えにしておき、raise で動かす */
    private fun run(replies: Map<String, String>, extraDescript: String = "", block: suspend (GhostController, ScriptedShiori) -> Unit) =
        runBlocking<Unit> {
            val shiori = ScriptedShiori(replies)
            val scope = testScope()
            try {
                val c = testController(tmp.newTestGhost(extraDescript = extraDescript), shiori, scope)
                c.start()
                shiori.awaitEvent("OnFirstBoot")
                block(c, shiori)
            } finally {
                scope.cancel()
            }
        }

    private suspend fun GhostController.awaitState(predicate: (GhostState) -> Boolean) = withTimeout(5000) { state.first(predicate) }

    private fun ScriptedShiori.all(id: String) = synchronized(events) { events.filter { it.first == id }.map { it.second } }

    private suspend fun ScriptedShiori.awaitCount(id: String, n: Int) = withTimeout(5000) { while (count(id) < n) delay(10) }

    private fun GhostState.says(scope: Int, text: String) =
        characters[scope]?.balloon?.any { it is BalloonItem.Text && it.text == text } == true

    @Test
    fun touchEntersAndLeavesCollisions() = run(emptyMap()) { c, shiori ->
        c.onTouchEnter(0, 10, 20, "Head")
        assertEquals(listOf("10", "20", "0", "0", "Head", "0", "touch"), shiori.awaitEvent("OnMouseEnterAll"))
        shiori.awaitEvent("OnMouseEnter")
        // 同じ当たり判定の中を動いても何も送らない
        c.onTouchCollision(0, 11, 21, "Head")
        c.onTouchCollision(0, 12, 60, "Bust")
        shiori.awaitCount("OnMouseEnter", 2)
        assertEquals("Head", shiori.all("OnMouseLeave").single()[4])
        assertEquals("Bust", shiori.all("OnMouseEnter").last()[4])
        c.onTouchLeave(0, 12, 60)
        shiori.awaitEvent("OnMouseLeaveAll")
        shiori.awaitCount("OnMouseLeave", 2)
        assertEquals(listOf("Head", "Bust"), shiori.all("OnMouseLeave").map { it[4] })
    }

    @Test
    fun dragEventsAreSuppressedInPassiveMode() = run(mapOf("OnTest" to """\![enter,passivemode]\e""")) { c, shiori ->
        c.onMouseDragStart(1, 5, 6, null)
        assertEquals(listOf("5", "6", "0", "1", "", "0", "touch"), shiori.awaitEvent("OnMouseDragStart"))
        c.onMouseDragEnd(1, 7, 8, null)
        shiori.awaitEvent("OnMouseDragEnd")
        c.raise("OnTest", emptyList())
        c.awaitState { it.passive }
        c.onMouseDragStart(1, 5, 6, null)
        c.onMouseDragEnd(1, 5, 6, null)
        delay(200)
        assertEquals(1, shiori.count("OnMouseDragStart"))
        assertEquals(1, shiori.count("OnMouseDragEnd"))
    }

    @Test
    fun gestureFallsBackToWheelAndEnds() = run(mapOf("OnMouseWheel" to """\0ころころ\e""")) { c, shiori ->
        c.onMouseGesture(0, 50, 20, "Head", 50, 80, "Bust", "up", 90)
        val wheel = shiori.awaitEvent("OnMouseWheel")
        // 上に払ったら正、量は縦に動いた距離
        assertEquals("60", wheel[2])
        assertEquals("Head", wheel[4])
        shiori.awaitCount("OnMouseGesture", 2)
        val (first, last) = shiori.all("OnMouseGesture")
        assertEquals(listOf("0", "50\u000120", "Head", "50\u000180", "Bust", "up", "90"), first)
        assertEquals("end", last[5])
        c.awaitState { it.says(0, "ころころ") }
    }

    @Test
    fun sidewaysGestureHasNoWheelFallback() = run(emptyMap()) { c, shiori ->
        c.onMouseGesture(0, 90, 50, null, 10, 50, null, "right", 0)
        shiori.awaitCount("OnMouseGesture", 2)
        assertEquals(0, shiori.count("OnMouseWheel"))
    }

    @Test
    fun twoFingerWheel() = run(mapOf("OnMouseWheel" to """\0くるくる\e""")) { c, shiori ->
        c.onMouseWheel(0, 30, 40, "Head", -240)
        assertEquals(listOf("30", "40", "-240", "0", "Head", "0", "touch"), shiori.awaitEvent("OnMouseWheel"))
        c.awaitState { it.says(0, "くるくる") }
    }

    @Test
    fun middleClickFallsBackToOldClick() = run(mapOf("OnMouseClick" to """\0まんなか\e""")) { c, shiori ->
        c.onMiddleClick(0)
        assertEquals("middle", shiori.awaitEvent("OnMouseDownEx")[5])
        shiori.awaitEvent("OnMouseUpEx")
        shiori.awaitEvent("OnMouseClickEx")
        // 古いゴースト向けには Reference5 が 2
        assertEquals("2", shiori.awaitEvent("OnMouseClick")[5])
        c.awaitState { it.says(0, "まんなか") }
    }

    @Test
    fun middleClickStopsAtTheFirstAnswer() = run(mapOf("OnMouseUpEx" to """\0うえ\e""")) { c, shiori ->
        c.onMiddleClick(0)
        c.awaitState { it.says(0, "うえ") }
        assertEquals(0, shiori.count("OnMouseClickEx"))
        assertEquals(0, shiori.count("OnMouseClick"))
    }

    @Test
    fun middleDoubleClick() = run(mapOf("OnMouseDoubleClickEx" to """\0にかい\e""")) { c, shiori ->
        c.onMiddleClick(0, 3, 4, "Head", count = 2)
        assertEquals(listOf("3", "4", "0", "0", "Head", "middle", "touch"), shiori.awaitEvent("OnMouseDoubleClickEx"))
        c.awaitState { it.says(0, "にかい") }
        assertEquals(0, shiori.count("OnMouseClickEx"))
    }

    @Test
    fun middleMultipleClickFallsBackByCount() = run(emptyMap()) { c, shiori ->
        // 答えが無ければ、3回目は OnMouseClickEx、4回目は OnMouseDoubleClickEx に戻る
        c.onMiddleClick(0, button = "xbutton1", device = "mouse", count = 3)
        assertEquals(listOf("0", "0", "0", "0", "", "xbutton1", "mouse", "3"), shiori.awaitEvent("OnMouseMultipleClickEx"))
        shiori.awaitEvent("OnMouseClickEx")
        c.onMiddleClick(0, button = "xbutton1", device = "mouse", count = 4)
        shiori.awaitCount("OnMouseMultipleClickEx", 2)
        shiori.awaitEvent("OnMouseDoubleClickEx")
        delay(200)
        assertEquals(1, shiori.count("OnMouseClickEx"))
        // 中ボタンでないので、古いゴースト向けの OnMouseClick は送らない
        assertEquals(0, shiori.count("OnMouseClick"))
    }

    @Test
    fun mouseHover() = run(mapOf("OnMouseHover" to """\0とまった\e""")) { c, shiori ->
        c.onMouseHover(1, 7, 8, "Face", "pen")
        assertEquals(listOf("7", "8", "0", "1", "Face", "0", "pen"), shiori.awaitEvent("OnMouseHover"))
    }

    @Test
    fun minimizeAndRestore() = run(mapOf("OnTest" to """\![set,windowstate,minimize]\e""")) { c, shiori ->
        c.raise("OnTest", emptyList())
        c.awaitState { it.window.minimized }
        assertEquals(listOf("script"), shiori.awaitEvent("OnWindowStateMinimize"))
        c.restoreWindows()
        c.awaitState { !it.window.minimized }
        assertEquals(listOf("user"), shiori.awaitEvent("OnWindowStateRestore"))
    }

    @Test
    fun resetWindowPosFromScriptAndMenu() = run(
        mapOf("OnTest" to """\![execute,resetwindowpos]\e""", "OnMenu" to """\0\q[位置を戻す,pono:resetpos]\e"""),
    ) { c, shiori ->
        c.raise("OnTest", emptyList())
        c.awaitState { it.window.resetRequests == 1 }
        // メニューからは、ゴーストが OnResetWindowPos に答えなければ戻す
        c.raise("OnMenu", emptyList())
        c.awaitState { it.waitingChoice }
        c.onChoice(Choice("位置を戻す", "pono:resetpos"))
        shiori.awaitEvent("OnResetWindowPos")
        c.awaitState { it.window.resetRequests == 2 }
    }

    @Test
    fun zorderStickyAndAlignment() = run(
        mapOf(
            "OnTest" to """\![set,zorder,1,0]\![set,sticky-window,0,1]\1\![set,alignmenttodesktop,top]\e""",
            "OnTest2" to """\![reset,zorder]\![reset,sticky-window]\1\![set,alignmenttodesktop,free]\e""",
        ),
    ) { c, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.characters[1]?.alignment == "top" }
        // 数だけなら、そのキャラクターのバルーンとサーフェス（バルーンが手前）
        assertEquals(listOf(listOf("b1", "s1", "b0", "s0")), s.window.zorder)
        assertEquals(listOf(setOf(0, 1)), s.window.stickyGroups)
        c.raise("OnTest2", emptyList())
        val r = c.awaitState { it.characters[1]?.alignment == null }
        assertTrue(r.window.zorder.isEmpty())
        assertTrue(r.window.stickyGroups.isEmpty())
    }

    @Test
    fun zorderCanNameSurfacesAndBalloons() = run(mapOf("OnTest" to """\![set,zorder,surface1,b0,s0]\![set,zorder,2,3]\e""")) { c, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.window.zorder.size == 2 }
        assertEquals(listOf(listOf("s1", "b0", "s0"), listOf("b2", "s2", "b3", "s3")), s.window.zorder)
    }

    @Test
    fun newStickyGroupTakesMembersFromOldOnes() = run(mapOf("OnTest" to """\![set,sticky-window,0,1,2]\![set,sticky-window,2,3]\e""")) { c, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.window.stickyGroups.size == 2 }
        assertEquals(listOf(setOf(0, 1), setOf(2, 3)), s.window.stickyGroups)
    }

    @Test
    fun nudgeAndFront() = run(mapOf("OnTest" to """\1\4\0\5\v\e""")) { c, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.window.frontRequests == 1 && it.characters[0]?.nudge != null }
        assertEquals(true, s.characters[1]?.nudge?.second)
        assertEquals(false, s.characters[0]?.nudge?.second)
        assertTrue(s.characters[0]!!.nudge!!.first > s.characters[1]!!.nudge!!.first)
    }

    @Test
    fun moveCanIgnoreStickyWindows() = run(mapOf("OnTest" to """\![moveasync,--X=10,--Y=20,--option=ignore-sticky-window]\e""")) { c, _ ->
        c.raise("OnTest", emptyList())
        val s = c.awaitState { it.characters[0]?.move != null }
        assertEquals(MoveCommand(10, 20, ignoreSticky = true), s.characters[0]?.move?.second)
    }

    @Test
    fun overlapAndOffscreenAreSentOnlyWhenChanged() = run(emptyMap()) { c, shiori ->
        c.onWindowLayout(listOf(1 to 0), emptyList())
        assertEquals(listOf("0-1", ""), shiori.awaitEvent("OnOverlap"))
        c.onWindowLayout(listOf(0 to 1), listOf(1))
        assertEquals(listOf("1", ""), shiori.awaitEvent("OnOffscreen"))
        c.onWindowLayout(emptyList(), listOf(1))
        shiori.awaitCount("OnOverlap", 2)
        assertEquals(listOf("", "0-1"), shiori.all("OnOverlap").last())
        delay(100)
        assertEquals(1, shiori.count("OnOffscreen"))
    }

    @Test
    fun shellScalingSendsBothAxes() = run(emptyMap()) { c, shiori ->
        c.onShellScaling(150, 100)
        assertEquals(listOf("150", "100", "150", "100"), shiori.awaitEvent("OnShellScaling"))
        c.onShellScaling(150, 150)
        delay(100)
        assertEquals(1, shiori.count("OnShellScaling"))
    }

    @Test
    fun defaultPositionsComeFromResourcesOrDescript() = run(
        mapOf("sakura.defaultx" to "-100"),
        extraDescript = "kero.defaulttop,40\r\nkero.defaultleft,--5\r\n",
    ) { c, _ ->
        val s = c.awaitState { it.window.defaultPositions.size == 2 }
        assertEquals(DefaultPosition(x = ScreenCoordinate(100, fromEnd = true)), s.window.defaultPositions[0])
        assertEquals(
            DefaultPosition(left = ScreenCoordinate(-5, fromEnd = false), top = ScreenCoordinate(40, fromEnd = false)),
            s.window.defaultPositions[1],
        )
    }

    @Test
    fun screenCoordinatesAndDefaultPlacement() {
        assertEquals(ScreenCoordinate(0, fromEnd = true), ScreenCoordinate.parse("-0"))
        assertEquals(ScreenCoordinate(-30, fromEnd = false), ScreenCoordinate.parse("--30"))
        assertNull(ScreenCoordinate.parse(""))
        assertNull(ScreenCoordinate.parse("abc"))
        // x / y は基準点（中央下）、left / top はウィンドウの左上。右端（下端）から数えるときは大きさも引く
        val pos = DefaultPosition(x = ScreenCoordinate(100, fromEnd = true), top = ScreenCoordinate(50, fromEnd = true))
        assertEquals(1000 - 100 - 40, pos.windowLeft(80, 1000))
        assertEquals(2000 - 50 - 300, pos.windowTop(300, 2000))
        assertNull(DefaultPosition().windowLeft(80, 1000))
        assertFalse(pos.isEmpty)
    }
}
