package net.pono.baseware.ghost

import kotlin.math.abs

/** Android の入力を SSP のイベントにする。微小な軸の揺れとボタンのリピートは送らない。 */
class GamepadState(private val emit: (String, List<String>) -> Unit) {
    private data class Pad(val index: Int, val buttons: MutableMap<String, MutableSet<String>> = mutableMapOf(), val axes: MutableMap<String, Float> = mutableMapOf())
    private val pads = linkedMapOf<Int, Pad>()
    val indices: List<Int> get() = pads.values.map { it.index }

    fun connect(device: Int): Int {
        pads[device]?.let { return it.index }
        val index = generateSequence(0) { it + 1 }.first { candidate -> pads.values.none { it.index == candidate } }
        pads[device] = Pad(index)
        emit("OnGamepadConnected", listOf(index.toString()))
        return index
    }

    fun disconnect(device: Int) {
        val pad = pads.remove(device) ?: return
        emit("OnGamepadDisconnected", listOf(pad.index.toString()))
    }

    fun button(device: Int, name: String, down: Boolean, source: String = "key") {
        if (name !in BUTTONS) return
        connect(device)
        val pad = pads.getValue(device)
        val sources = pad.buttons.getOrPut(name) { mutableSetOf() }
        val wasDown = sources.isNotEmpty()
        if (down) sources.add(source) else sources.remove(source)
        val isDown = sources.isNotEmpty()
        if (!isDown) pad.buttons.remove(name)
        if (wasDown != isDown) emit(if (isDown) "OnGamepadButtonDown" else "OnGamepadButtonUp", listOf(pad.index.toString(), name))
    }

    fun axis(device: Int, name: String, value: Float, deadZone: Float = 0.05f) {
        if (name !in AXES || !value.isFinite()) return
        connect(device)
        val pad = pads.getValue(device)
        val trigger = name.endsWith("Trigger")
        val bounded = value.coerceIn(if (trigger) 0f else -1f, 1f)
        val normalized = if (abs(bounded) <= deadZone.coerceIn(0f, 0.95f)) 0f else bounded
        val old = pad.axes[name] ?: 0f
        if (normalized == old || (normalized != 0f && abs(normalized - old) < 0.01f)) return
        pad.axes[name] = normalized
        val raw = (normalized * 32767).toInt()
        emit("OnGamepadAxisMove", listOf(pad.index.toString(), name, raw.toString(), normalized.toString()))
    }

    /** 入力画面を離れたとき、押したままのキーや軸が残らないようにする。 */
    fun releaseInputs() {
        for ((device, pad) in pads.toMap()) {
            val buttons = pad.buttons.keys.toList()
            pad.buttons.clear()
            buttons.forEach { emit("OnGamepadButtonUp", listOf(pad.index.toString(), it)) }
            pad.axes.keys.toList().forEach { axis(device, it, 0f) }
        }
    }

    companion object {
        val BUTTONS = setOf("A", "B", "X", "Y", "LB", "RB", "LSB", "RSB", "Start", "Back", "DPadUp", "DPadDown", "DPadLeft", "DPadRight")
        val AXES = setOf("LeftStickX", "LeftStickY", "RightStickX", "RightStickY", "LeftTrigger", "RightTrigger")
    }
}
