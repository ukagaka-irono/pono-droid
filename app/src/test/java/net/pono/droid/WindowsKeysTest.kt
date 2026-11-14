package net.pono.droid

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowsKeysTest {
    @Test fun mapsPhysicalKeysToWindowsRatherThanAndroidCodes() {
        assertEquals(65, WindowsKeys.code(KeyEvent.KEYCODE_A))
        assertEquals(90, WindowsKeys.code(KeyEvent.KEYCODE_Z))
        assertEquals(112, WindowsKeys.code(KeyEvent.KEYCODE_F1))
        assertEquals(123, WindowsKeys.code(KeyEvent.KEYCODE_F12))
        assertEquals(37, WindowsKeys.code(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(46, WindowsKeys.code(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(8, WindowsKeys.code(KeyEvent.KEYCODE_DEL))
        assertNull(WindowsKeys.code(KeyEvent.KEYCODE_POWER))
    }
}
