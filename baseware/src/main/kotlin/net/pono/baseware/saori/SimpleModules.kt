package net.pono.baseware.saori

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** internal_system: 内蔵モジュールと関数の一覧 */
object SystemModule {
    val module = SaoriModule(
        "internal_system",
        listOf(
            SaoriFunction("ListModules", 0, 0, emptyList()) { SaoriResult.list(InternalSaori.MODULES.map { it.name }) },
            SaoriFunction("ListFunctions", 1, 1, emptyList()) { c ->
                val m = InternalSaori.MODULES.firstOrNull { it.name.equals(c.arg(0).trim(), ignoreCase = true) }
                    ?: throw SaoriException.bad("unknown module")
                SaoriResult.list(m.functions.map { it.name })
            },
        ),
    )
}

/** internal_random: 暗号論的に安全な乱数 */
object RandomModule {
    val module = SaoriModule(
        "internal_random",
        listOf(
            SaoriFunction("Bytes", 1, 1, listOf("out")) { c ->
                val n = c.intArg(0, 1..1024)
                SaoriResult(c.output(ByteArray(n).also(c.env.random::nextBytes), "hex"))
            },
            SaoriFunction("Number", 2, 2, emptyList()) { c ->
                val min = c.arg(0).trim().toLongOrNull() ?: throw SaoriException.bad("invalid parameter")
                val max = c.arg(1).trim().toLongOrNull() ?: throw SaoriException.bad("invalid parameter")
                if (min > max) throw SaoriException.bad("invalid parameter")
                SaoriResult(number(c, min, max).toString())
            },
            SaoriFunction("UUID", 0, 0, listOf("version")) { c ->
                when (c.options["version"]?.trim() ?: "4") {
                    "4" -> SaoriResult(uuid4(c))
                    "7" -> SaoriResult(uuid7(c))
                    else -> throw SaoriException.bad("invalid parameter")
                }
            },
        ),
    )

    /** [min] 以上 [max] 以下を偏りなく（棄却法） */
    private fun number(c: SaoriCall, min: Long, max: Long): Long {
        val span = max - min + 1 // 0 なら 64 ビット全部
        if (span == 0L) return c.env.random.nextLong()
        if (span > 0) {
            val limit = Long.MAX_VALUE - (Long.MAX_VALUE % span + 1) % span
            while (true) {
                val r = c.env.random.nextLong() ushr 1
                if (r <= limit) return min + r % span
            }
        }
        // 幅が 2^63 を超える（span は負に見える）。範囲に入るまで引き直す
        while (true) {
            val r = c.env.random.nextLong()
            if (r in min..max) return r
        }
    }

    private fun uuid4(c: SaoriCall): String {
        val b = ByteArray(16).also(c.env.random::nextBytes)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        return format(b)
    }

    private fun uuid7(c: SaoriCall): String {
        val b = ByteArray(16).also(c.env.random::nextBytes)
        val ms = c.env.now()
        for (i in 0 until 6) b[i] = (ms ushr (8 * (5 - i))).toByte()
        b[6] = ((b[6].toInt() and 0x0F) or 0x70).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        return format(b)
    }

    private fun format(b: ByteArray): String {
        val bb = ByteBuffer.wrap(b)
        return UUID(bb.long, bb.long).toString()
    }
}

/** internal_text: 文字列の処理 */
object TextModule {
    val module = SaoriModule(
        "internal_text",
        listOf(
            SaoriFunction("Normalize", 2, 2, emptyList()) { c ->
                val form = when (c.arg(0).trim().uppercase()) {
                    "NFC" -> Normalizer.Form.NFC
                    "NFD" -> Normalizer.Form.NFD
                    "NFKC" -> Normalizer.Form.NFKC
                    "NFKD" -> Normalizer.Form.NFKD
                    else -> throw SaoriException.bad("unsupported algorithm")
                }
                val s = c.arg(1)
                if (hasLoneSurrogate(s)) throw SaoriException.bad("invalid encoding")
                SaoriResult(Normalizer.normalize(s, form))
            },
        ),
    )

    fun hasLoneSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(ch)) return true
            i++
        }
        return false
    }
}

/** internal_data: 形式の変換と、圧縮・展開 */
object DataModule {
    val module = SaoriModule(
        "internal_data",
        listOf(
            SaoriFunction("Convert", 1, 1, listOf("data-in", "out", "charset", "hrp")) { c ->
                SaoriResult(c.output(c.bytes(0, "data", "text"), "text", allowBech32 = true))
            },
            SaoriFunction("Compress", 2, 2, listOf("data-in", "level", "out", "charset")) { c ->
                val method = c.arg(0).trim().lowercase()
                if (method !in setOf("gzip", "zlib", "deflate")) throw SaoriException.bad("unsupported algorithm")
                val level = c.intOption("level", Deflater.DEFAULT_COMPRESSION, 0..9)
                val data = c.bytes(1, "data", "text")
                SaoriResult(c.output(compress(method, data, level), "base64"))
            },
            SaoriFunction("Decompress", 2, 2, listOf("data-in", "out", "charset")) { c ->
                val method = c.arg(0).trim().lowercase()
                if (method !in setOf("gzip", "zlib", "deflate")) throw SaoriException.bad("unsupported algorithm")
                val data = c.bytes(1, "data", "base64")
                SaoriResult(c.output(decompress(method, data), "text"))
            },
        ),
    )

    fun compress(method: String, data: ByteArray, level: Int): ByteArray {
        val deflater = Deflater(level, method != "zlib")
        try {
            deflater.setInput(data)
            deflater.finish()
            val body = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) body.write(buf, 0, deflater.deflate(buf))
            if (method != "gzip") return body.toByteArray()
            val crc = CRC32().apply { update(data) }.value
            val out = ByteArrayOutputStream()
            // ヘッダ: ID1 ID2 CM=8 FLG=0 MTIME=0 XFL=0 OS=255（不明）
            out.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 0, 0xff.toByte()))
            body.writeTo(out)
            for (v in longArrayOf(crc, data.size.toLong() and 0xffffffffL)) for (i in 0 until 4) out.write((v ushr (8 * i)).toInt() and 0xff)
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** 展開する。壊れている・途中で切れている・後ろに余計なデータがあれば invalid encoding、8MB を超えれば too large */
    fun decompress(method: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        when (method) {
            "zlib", "deflate" -> {
                val used = inflate(data, 0, method == "deflate", out)
                if (used != data.size) throw SaoriException.bad("invalid encoding")
            }
            else -> {
                var pos = 0
                if (data.isEmpty()) throw SaoriException.bad("invalid encoding")
                // 複数のメンバーをつないだものも読む
                while (pos < data.size) {
                    val start = out.size()
                    pos = gzipHeader(data, pos)
                    pos += inflate(data, pos, true, out) - pos
                    if (pos + 8 > data.size) throw SaoriException.bad("invalid encoding")
                    val member = out.toByteArray().copyOfRange(start, out.size())
                    val crc = CRC32().apply { update(member) }.value
                    if (le32(data, pos) != crc || le32(data, pos + 4) != (member.size.toLong() and 0xffffffffL)) throw SaoriException.bad("invalid encoding")
                    pos += 8
                }
            }
        }
        return out.toByteArray()
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xff) shl (8 * i)) }

    /** gzip のメンバーのヘッダを読み飛ばし、圧縮データの始まりを返す */
    private fun gzipHeader(d: ByteArray, at: Int): Int {
        val bad = SaoriException.bad("invalid encoding")
        if (at + 10 > d.size || d[at] != 0x1f.toByte() || d[at + 1] != 0x8b.toByte() || d[at + 2] != 8.toByte()) throw bad
        val flg = d[at + 3].toInt()
        var p = at + 10
        if (flg and 4 != 0) {
            if (p + 2 > d.size) throw bad
            p += 2 + ((d[p].toInt() and 0xff) or ((d[p + 1].toInt() and 0xff) shl 8))
        }
        for (bit in intArrayOf(8, 16)) if (flg and bit != 0) {
            while (p < d.size && d[p] != 0.toByte()) p++
            p++
        }
        if (flg and 2 != 0) p += 2
        if (p > d.size) throw bad
        return p
    }

    /** [at] から deflate（[raw] なら生、でなければ zlib）を展開して [out] に足し、読み終えた位置を返す */
    private fun inflate(d: ByteArray, at: Int, raw: Boolean, out: ByteArrayOutputStream): Int {
        val inflater = Inflater(raw)
        try {
            inflater.setInput(d, at, d.size - at)
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: DataFormatException) {
                    throw SaoriException.bad("invalid encoding")
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw SaoriException.bad("invalid encoding")
                out.write(buf, 0, n)
                if (out.size() > SaoriCodec.MAX_BYTES) throw SaoriException.bad("too large")
            }
            return d.size - inflater.remaining
        } finally {
            inflater.end()
        }
    }
}
