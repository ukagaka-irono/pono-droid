package net.pono.droid

import android.view.KeyEvent

/** Android のキーコードを SHIORI の OnKeyPress が要求する Win32 VK に写す。 */
object WindowsKeys {
    fun code(key: Int): Int? = when (key) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x41 + key - KeyEvent.KEYCODE_A
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 0x30 + key - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x70 + key - KeyEvent.KEYCODE_F1
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> 0x60 + key - KeyEvent.KEYCODE_NUMPAD_0
        KeyEvent.KEYCODE_DEL -> 0x08; KeyEvent.KEYCODE_TAB -> 0x09
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x0d
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 0x10
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 0x11
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 0x12
        KeyEvent.KEYCODE_BREAK -> 0x13; KeyEvent.KEYCODE_CAPS_LOCK -> 0x14
        KeyEvent.KEYCODE_ESCAPE -> 0x1b; KeyEvent.KEYCODE_SPACE -> 0x20
        KeyEvent.KEYCODE_PAGE_UP -> 0x21; KeyEvent.KEYCODE_PAGE_DOWN -> 0x22
        KeyEvent.KEYCODE_MOVE_END -> 0x23; KeyEvent.KEYCODE_MOVE_HOME -> 0x24
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x25; KeyEvent.KEYCODE_DPAD_UP -> 0x26
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27; KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
        KeyEvent.KEYCODE_SYSRQ -> 0x2c; KeyEvent.KEYCODE_INSERT -> 0x2d
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x2e
        KeyEvent.KEYCODE_META_LEFT -> 0x5b; KeyEvent.KEYCODE_META_RIGHT -> 0x5c
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x6a; KeyEvent.KEYCODE_NUMPAD_ADD -> 0x6b
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x6d; KeyEvent.KEYCODE_NUMPAD_DOT -> 0x6e
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x6f; KeyEvent.KEYCODE_NUM_LOCK -> 0x90
        KeyEvent.KEYCODE_SCROLL_LOCK -> 0x91
        KeyEvent.KEYCODE_SEMICOLON -> 0xba; KeyEvent.KEYCODE_EQUALS -> 0xbb
        KeyEvent.KEYCODE_COMMA -> 0xbc; KeyEvent.KEYCODE_MINUS -> 0xbd
        KeyEvent.KEYCODE_PERIOD -> 0xbe; KeyEvent.KEYCODE_SLASH -> 0xbf
        KeyEvent.KEYCODE_GRAVE -> 0xc0; KeyEvent.KEYCODE_LEFT_BRACKET -> 0xdb
        KeyEvent.KEYCODE_BACKSLASH -> 0xdc; KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xdd
        KeyEvent.KEYCODE_APOSTROPHE -> 0xde
        else -> null
    }

    fun name(virtualKey: Int, label: Char = '\u0000'): String = when {
        virtualKey in 0x41..0x5a -> virtualKey.toChar().lowercase()
        virtualKey in 0x30..0x39 -> virtualKey.toChar().toString()
        virtualKey in 0x70..0x87 -> "F${virtualKey - 0x6f}"
        label != '\u0000' -> label.toString()
        else -> names[virtualKey].orEmpty()
    }

    val names = linkedMapOf(0x08 to "Backspace", 0x09 to "Tab", 0x0d to "Enter", 0x1b to "Esc", 0x20 to "Space",
        0x21 to "PageUp", 0x22 to "PageDown", 0x23 to "End", 0x24 to "Home", 0x25 to "Left", 0x26 to "Up", 0x27 to "Right", 0x28 to "Down", 0x2d to "Insert", 0x2e to "Delete")
}
