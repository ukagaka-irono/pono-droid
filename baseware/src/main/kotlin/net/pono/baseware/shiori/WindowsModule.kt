package net.pono.baseware.shiori

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** PE の種類を読み、32 ビット DLL を 64 ビットのホストに渡さないための検査。コードは実行しない。 */
data class WindowsModule(val file: File, val architecture: Architecture) {
    enum class Architecture(val machine: Int, val bits: Int) {
        X86(0x014c, 32), X64(0x8664, 64), ARM64(0xaa64, 64),
    }

    companion object {
        /** 壊れたファイル、PE でないもの、実行ファイル、未対応の CPU は null。 */
        fun inspect(file: File): WindowsModule? = try {
            RandomAccessFile(file, "r").use { input ->
                fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
                if (input.length() < 64 || u16() != 0x5a4d) return null
                input.seek(0x3c)
                val pe = u32()
                if (pe < 64 || pe > input.length() - 26) return null
                input.seek(pe)
                if (u32() != 0x00004550L) return null
                val machine = u16()
                val kind = Architecture.entries.firstOrNull { it.machine == machine } ?: return null
                input.seek(pe + 20)
                val optionalSize = u16()
                if (optionalSize < 2 || pe + 24 + optionalSize > input.length()) return null
                if (u16() and 0x2000 == 0) return null // IMAGE_FILE_DLL
                val magic = u16()
                if (magic != if (kind.bits == 32) 0x10b else 0x20b) return null
                WindowsModule(file, kind)
            }
        } catch (_: IOException) { null }
    }
}
