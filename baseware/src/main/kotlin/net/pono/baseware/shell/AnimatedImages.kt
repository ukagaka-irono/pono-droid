package net.pono.baseware.shell

import java.io.File

/**
 * GIF / APNG / WebP が動く画像か（2コマ以上あるか）。surface*.png や element に動く画像を書くと、
 * SERIKO を書かなくても動かす（SSP 2.7.38）。中身を全部は読まず、先頭だけ見る
 */
object AnimatedImages {
    /** 見る長さ。APNG の acTL も WebP の VP8X も先頭にある。GIF は2コマ目の印がここまでにあれば動くとみなす */
    private const val HEAD = 256 * 1024

    fun isAnimated(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(HEAD)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            isAnimated(buf.copyOf(n))
        }
    }.getOrDefault(false)

    fun isAnimated(b: ByteArray): Boolean = when {
        b.startsWith("GIF8") -> gifFrames(b) > 1
        b.size > 8 && b[0] == 0x89.toByte() && b.startsWith("PNG", 1) -> pngHasAnimation(b)
        b.startsWith("RIFF") && b.startsWith("WEBP", 8) -> webpHasAnimation(b)
        else -> false
    }

    private fun ByteArray.startsWith(s: String, at: Int = 0): Boolean =
        size >= at + s.length && s.indices.all { this[at + it] == s[it].code.toByte() }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** PNG の chunk を IDAT まで見て、acTL（アニメーションの印）があるか */
    private fun pngHasAnimation(b: ByteArray): Boolean {
        var i = 8
        while (i + 8 <= b.size) {
            val len = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
            if (len < 0) return false
            if (b.startsWith("acTL", i + 4)) {
                // コマの数が2以上
                return i + 12 <= b.size && ((u8(b, i + 8) shl 24) or (u8(b, i + 9) shl 16) or (u8(b, i + 10) shl 8) or u8(b, i + 11)) > 1
            }
            if (b.startsWith("IDAT", i + 4)) return false
            i += 12 + len
        }
        return false
    }

    /** WebP の VP8X の旗にアニメーションが立っているか、ANIM の chunk があるか */
    private fun webpHasAnimation(b: ByteArray): Boolean {
        if (b.startsWith("VP8X", 12) && b.size > 20 && (u8(b, 20) and 0x02) != 0) return true
        var i = 12
        while (i + 8 <= b.size) {
            if (b.startsWith("ANIM", i)) return true
            val len = u8(b, i + 4) or (u8(b, i + 5) shl 8) or (u8(b, i + 6) shl 16) or (u8(b, i + 7) shl 24)
            if (len < 0) return false
            i += 8 + len + (len and 1)
        }
        return false
    }

    /** GIF のコマ（Image Descriptor）の数。見た範囲で数える */
    private fun gifFrames(b: ByteArray): Int {
        if (b.size < 13) return 0
        var i = 13
        val flags = u8(b, 10)
        if (flags and 0x80 != 0) i += 3 * (1 shl ((flags and 0x07) + 1))
        var frames = 0
        while (i < b.size) {
            when (u8(b, i)) {
                0x2C -> {
                    frames++
                    if (frames > 1) return frames
                    if (i + 10 > b.size) return frames
                    val f = u8(b, i + 9)
                    i += 10
                    if (f and 0x80 != 0) i += 3 * (1 shl ((f and 0x07) + 1))
                    i++ // LZW の最小コード長
                    i = skipBlocks(b, i) ?: return frames
                }
                0x21 -> i = skipBlocks(b, i + 2) ?: return frames
                0x3B -> return frames
                else -> return frames
            }
        }
        return frames
    }

    private fun skipBlocks(b: ByteArray, from: Int): Int? {
        var i = from
        while (i < b.size) {
            val n = u8(b, i)
            i += 1 + n
            if (n == 0) return i
        }
        return null
    }
}
