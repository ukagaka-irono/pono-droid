package net.pono.droid.overlay

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import net.pono.baseware.ghost.GamepadState

/** 接続は常駐サービスで監視し、ボタンと軸はユーザーが開いた操作画面で受け取る。 */
class GamepadInput(context: Context, emit: (String, List<String>) -> Unit) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    val state = GamepadState(emit)

    fun start() {
        manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        manager.inputDeviceIds.forEach(::onInputDeviceAdded)
    }
    fun stop() { state.releaseInputs(); manager.unregisterInputDeviceListener(this) }
    private fun isPad(device: InputDevice) = device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK)
    override fun onInputDeviceAdded(id: Int) { manager.getInputDevice(id)?.takeIf(::isPad)?.let { state.connect(id) } }
    override fun onInputDeviceRemoved(id: Int) { state.disconnect(id) }
    override fun onInputDeviceChanged(id: Int) {
        if (manager.getInputDevice(id)?.let(::isPad) == true) state.connect(id) else state.disconnect(id)
    }

    fun key(event: KeyEvent): Boolean {
        if (event.device?.let(::isPad) != true) return false
        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> "A"; KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_X -> "X"; KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "LB"; KeyEvent.KEYCODE_BUTTON_R1 -> "RB"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "LSB"; KeyEvent.KEYCODE_BUTTON_THUMBR -> "RSB"
            KeyEvent.KEYCODE_BUTTON_START -> "Start"; KeyEvent.KEYCODE_BUTTON_SELECT -> "Back"
            KeyEvent.KEYCODE_DPAD_UP -> "DPadUp"; KeyEvent.KEYCODE_DPAD_DOWN -> "DPadDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "DPadLeft"; KeyEvent.KEYCODE_DPAD_RIGHT -> "DPadRight"
            else -> return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> state.button(event.deviceId, button, true)
            KeyEvent.ACTION_UP -> state.button(event.deviceId, button, false)
        }
        return true
    }

    fun motion(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) return false
        fun axis(android: Int, name: String, invert: Boolean = false) {
            val range = device.getMotionRange(android, event.source) ?: return
            val value = event.getAxisValue(android)
            val trigger = name.endsWith("Trigger")
            val size = if (trigger) range.max - range.min else maxOf(kotlin.math.abs(range.min), kotlin.math.abs(range.max))
            if (size <= 0f) return
            val normalized = if (trigger) (value - range.min) / size else value / size
            state.axis(device.id, name, if (invert) -normalized else normalized, range.flat / size)
        }
        axis(MotionEvent.AXIS_X, "LeftStickX")
        axis(MotionEvent.AXIS_Y, "LeftStickY", invert = true)
        axis(MotionEvent.AXIS_Z, "RightStickX")
        axis(MotionEvent.AXIS_RZ, "RightStickY", invert = true)
        axis(if (device.getMotionRange(MotionEvent.AXIS_LTRIGGER, event.source) != null) MotionEvent.AXIS_LTRIGGER else MotionEvent.AXIS_BRAKE, "LeftTrigger")
        axis(if (device.getMotionRange(MotionEvent.AXIS_RTRIGGER, event.source) != null) MotionEvent.AXIS_RTRIGGER else MotionEvent.AXIS_GAS, "RightTrigger")
        for ((android, negative, positive) in listOf(
            Triple(MotionEvent.AXIS_HAT_X, "DPadLeft", "DPadRight"), Triple(MotionEvent.AXIS_HAT_Y, "DPadUp", "DPadDown"),
        )) {
            if (device.getMotionRange(android, event.source) == null) continue
            val value = event.getAxisValue(android)
            state.button(device.id, negative, value < -0.5f, source = "hat")
            state.button(device.id, positive, value > 0.5f, source = "hat")
        }
        return true
    }
}
