package net.pono.baseware.shiori

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WindowsModuleTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pe(machine: Int, bits: Int, dll: Boolean = true): ByteArray {
        val bytes = ByteArray(256)
        fun u16(at: Int, value: Int) { bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte() }
        u16(0, 0x5a4d)
        u16(0x3c, 128)
        u16(128, 0x4550)
        u16(132, machine)
        u16(148, 2)
        u16(150, if (dll) 0x2000 else 0)
        u16(152, if (bits == 32) 0x10b else 0x20b)
        return bytes
    }

    @Test fun identifiesArchitectureAndRejectsExecutableOrMalformedHeaders() {
        val file = tmp.newFile("shiori.dll")
        for (architecture in WindowsModule.Architecture.entries) {
            file.writeBytes(pe(architecture.machine, architecture.bits))
            assertEquals(architecture, WindowsModule.inspect(file)?.architecture)
        }
        for (bad in listOf(
            byteArrayOf(), "not a DLL".toByteArray(), pe(0x014c, 32, dll = false),
            pe(0x014c, 64), pe(0xffff, 64), pe(0x8664, 64).copyOf(153),
            pe(0x014c, 32).also { for (i in 0x3c..0x3f) it[i] = 0xff.toByte() },
        )) {
            file.writeBytes(bad)
            assertNull(WindowsModule.inspect(file))
        }
    }
}
