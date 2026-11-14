package net.pono.baseware.ghost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GamepadStateTest {
    @Test fun combinesKeyAndHatWithoutPrematureRelease() {
        val events = mutableListOf<String>()
        val pad = GamepadState { event, _ -> events += event }
        pad.button(1, "DPadUp", true)
        pad.button(1, "DPadUp", false, "hat")
        pad.button(1, "DPadUp", true, "hat")
        pad.button(1, "DPadUp", false)
        assertEquals(listOf("OnGamepadConnected", "OnGamepadButtonDown"), events)
        pad.button(1, "DPadUp", false, "hat")
        assertEquals("OnGamepadButtonUp", events.last())
        pad.button(1, "DPadUp", true, "hat")
        pad.button(1, "DPadUp", true)
        pad.releaseInputs()
        pad.button(1, "DPadUp", false)
        assertEquals(2, events.count { it == "OnGamepadButtonUp" })
    }

    @Test fun stableIndicesButtonEdgesAndDisconnect() {
        val events = mutableListOf<Pair<String, List<String>>>()
        val pad = GamepadState { event, refs -> events += event to refs }
        assertEquals(0, pad.connect(50))
        assertEquals(1, pad.connect(90))
        assertEquals(0, pad.connect(50))
        pad.button(90, "A", true)
        pad.button(90, "A", true)
        pad.button(90, "A", false)
        pad.disconnect(50)
        assertEquals(1, pad.connect(90))
        assertEquals(0, pad.connect(100))
        assertEquals(listOf(
            "OnGamepadConnected" to listOf("0"), "OnGamepadConnected" to listOf("1"),
            "OnGamepadButtonDown" to listOf("1", "A"), "OnGamepadButtonUp" to listOf("1", "A"),
            "OnGamepadDisconnected" to listOf("0"), "OnGamepadConnected" to listOf("0"),
        ), events)
    }

    @Test fun axesIgnoreJitterAndAlwaysReturnToNeutral() {
        val events = mutableListOf<Pair<String, List<String>>>()
        val pad = GamepadState { event, refs -> events += event to refs }
        pad.axis(1, "LeftStickX", 0.04f)
        pad.axis(1, "LeftStickX", 0.5f)
        pad.axis(1, "LeftStickX", 0.505f)
        pad.axis(1, "LeftStickX", 0.001f)
        pad.axis(1, "RightTrigger", -0.5f)
        pad.axis(1, "RightTrigger", 2f)
        pad.axis(1, "RightTrigger", Float.NaN)
        pad.button(1, "DPadUp", true)
        pad.button(1, "DPadRight", true)
        pad.releaseInputs()
        val axes = events.filter { it.first == "OnGamepadAxisMove" }.map { it.second }
        assertEquals(listOf("0.5", "0.0", "1.0", "0.0"), axes.map { it[3] })
        assertEquals(listOf("LeftStickX", "LeftStickX", "RightTrigger", "RightTrigger"), axes.map { it[1] })
        assertEquals(2, events.count { it.first == "OnGamepadButtonUp" })
        assertFalse(events.any { "NaN" in it.second })
    }
}
