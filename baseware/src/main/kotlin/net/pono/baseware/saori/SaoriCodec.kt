package net.pono.baseware.saori

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * 内蔵モジュールのバイト列の形式（--名前-in= と --out=）。読めないものは [SaoriException]（400 invalid encoding）
 */
object SaoriCodec {
    /** デコードした後のデータの上限（1つの引数あたり） */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** [format] の形式の [value] をバイト列にする。file はここでは扱わない（呼ぶ側で読む） */
    fun decode(value: String, format: String, charset: Charset): ByteArray {
        val bytes = when (format.lowercase()) {
            "text" -> encodeText(value, charset)
            "hex" -> hexDecode(value)
            "base64", "base64url" -> base64Decode(value)
            "base32" -> base32Decode(value)
            "url" -> urlDecode(value)
            "bech32" -> Bech32.decode(value).second
            else -> throw SaoriException.bad("invalid parameter")
        }
        if (bytes.size > MAX_BYTES) throw SaoriException.bad("too large")
        return bytes
    }

    /** バイト列を [format] の形式で書く。bech32 は [hrp] が要る（Convert だけ） */
    fun encode(bytes: ByteArray, format: String, charset: Charset, hrp: String? = null): String = when (format) {
        "HEX" -> hexEncode(bytes).uppercase()
        else -> when (format.lowercase()) {
            "hex" -> hexEncode(bytes)
            "base64" -> Base64.getEncoder().encodeToString(bytes)
            "base64url" -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            "base32" -> base32Encode(bytes)
            "url" -> urlEncode(bytes)
            "bech32" -> Bech32.encode(hrp ?: throw SaoriException.bad("invalid parameter"), bytes)
            "text" -> decodeText(bytes, charset)
            else -> throw SaoriException.bad("invalid parameter")
        }
    }

    /** --out= に書ける名前か（bech32 は Convert だけ） */
    fun isOutputFormat(format: String): Boolean =
        format == "HEX" || format.lowercase() in setOf("hex", "base64", "base64url", "base32", "url", "bech32", "text")

    /** 文字列を [charset] のバイト列にする。その文字コードで表せない文字・対になっていないサロゲートは invalid encoding */
    fun encodeText(value: String, charset: Charset): ByteArray = try {
        val buf = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(buf.remaining()).also { buf.get(it) }
    } catch (e: CharacterCodingException) {
        throw SaoriException.bad("invalid encoding")
    }

    /** バイト列を [charset] の文字列に戻す。戻せない・NUL や改行を含む（SAORI のヘッダに書けない）なら 500 not text */
    fun decodeText(bytes: ByteArray, charset: Charset): String {
        val text = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw SaoriException.fail("not text")
        }
        if (text.any { it == '\u0000' || it == '\r' || it == '\n' }) throw SaoriException.fail("not text")
        return text
    }

    // ---- 16進 ----

    private const val HEX = "0123456789abcdef"

    fun hexEncode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
        return sb.toString()
    }

    fun hexDecode(value: String): ByteArray {
        if (value.length % 2 != 0) throw SaoriException.bad("invalid encoding")
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(value[i * 2], 16)
            val lo = Character.digit(value[i * 2 + 1], 16)
            if (hi < 0 || lo < 0 || !value[i * 2].isAsciiHex() || !value[i * 2 + 1].isAsciiHex()) throw SaoriException.bad("invalid encoding")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun Char.isAsciiHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // ---- base64 / base64url（どちらの文字も読む。パディングはあってもなくてもよい） ----

    fun base64Decode(value: String): ByteArray {
        val padAt = value.indexOf('=')
        val body = if (padAt >= 0) {
            // パディングは末尾にだけ、全体が 4 の倍数になるように 1～2 個
            val pad = value.length - padAt
            if (pad > 2 || value.substring(padAt).any { it != '=' } || value.length % 4 != 0) throw SaoriException.bad("invalid encoding")
            value.substring(0, padAt)
        } else {
            value
        }
        if (body.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '-' || it == '_') }) {
            throw SaoriException.bad("invalid encoding")
        }
        if (body.length % 4 == 1) throw SaoriException.bad("invalid encoding")
        val normalized = body.replace('-', '+').replace('_', '/')
        return try {
            Base64.getDecoder().decode(normalized)
        } catch (e: IllegalArgumentException) {
            throw SaoriException.bad("invalid encoding")
        }
    }

    // ---- base32（RFC 4648） ----

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buffer shl (5 - bits)) and 31])
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }

    fun base32Decode(value: String): ByteArray {
        val body = value.trimEnd('=').uppercase()
        // パディングを除いた長さとして、あり得ない余り（1・3・6）はだめ
        if (body.length % 8 in setOf(1, 3, 6)) throw SaoriException.bad("invalid encoding")
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in body) {
            val v = B32.indexOf(c)
            if (v < 0) throw SaoriException.bad("invalid encoding")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return out.toByteArray()
    }

    // ---- パーセントエンコード（+ は空白に戻さない） ----

    fun urlDecode(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length || !value[i + 1].isAsciiHex() || !value[i + 2].isAsciiHex()) throw SaoriException.bad("invalid encoding")
                out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16))
                i += 3
            } else {
                // % 以外の文字は UTF-8 のバイト列のまま
                val end = if (Character.isHighSurrogate(c) && i + 1 < value.length) i + 2 else i + 1
                out.write(encodeText(value.substring(i, end), Charsets.UTF_8))
                i = end
            }
        }
        return out.toByteArray()
    }

    fun urlEncode(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[(b.toInt() shr 4) and 15].uppercaseChar()).append(HEX[b.toInt() and 15].uppercaseChar())
            }
        }
        return sb.toString()
    }
}

/** BIP-173 の bech32（bech32m は読まない） */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((top ushr i) and 1 != 0) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray =
        IntArray(hrp.length * 2 + 1).also { r ->
            for (i in hrp.indices) {
                r[i] = hrp[i].code ushr 5
                r[i + hrp.length + 1] = hrp[i].code and 31
            }
        }

    /** 読んで、hrp とデータのバイト列を返す */
    fun decode(value: String): Pair<String, ByteArray> {
        val bad = SaoriException.bad("invalid encoding")
        if (value.any { it.code < 33 || it.code > 126 }) throw bad
        if (value.any { it.isUpperCase() } && value.any { it.isLowerCase() }) throw bad
        val s = value.lowercase()
        val sep = s.lastIndexOf('1')
        if (sep < 1 || sep + 7 > s.length) throw bad
        val hrp = s.substring(0, sep)
        val data = IntArray(s.length - sep - 1) { CHARSET.indexOf(s[sep + 1 + it]).also { v -> if (v < 0) throw bad } }
        if (polymod(hrpExpand(hrp) + data) != 1) throw bad
        val bytes = convertBits(data.copyOfRange(0, data.size - 6), 5, 8, pad = false) ?: throw bad
        return hrp to ByteArray(bytes.size) { bytes[it].toByte() }
    }

    /** [hrp]（1～83文字の ASCII）でバイト列を書く（小文字） */
    fun encode(hrp: String, bytes: ByteArray): String {
        val h = hrp.lowercase()
        if (h.isEmpty() || h.length > 83 || h.any { it.code < 33 || it.code > 126 }) throw SaoriException.bad("invalid parameter")
        val data = convertBits(IntArray(bytes.size) { bytes[it].toInt() and 0xFF }, 8, 5, pad = true)!!
        val mod = polymod(hrpExpand(h) + data + IntArray(6)) xor 1
        val sb = StringBuilder(h).append('1')
        for (d in data) sb.append(CHARSET[d])
        for (i in 0 until 6) sb.append(CHARSET[(mod ushr (5 * (5 - i))) and 31])
        return sb.toString()
    }

    private fun convertBits(data: IntArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (v in data) {
            acc = (acc shl from) or v
            bits += from
            while (bits >= to) {
                bits -= to
                out += (acc ushr bits) and maxv
            }
        }
        if (pad) {
            if (bits > 0) out += (acc shl (to - bits)) and maxv
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }

    private operator fun IntArray.plus(other: IntArray): IntArray = IntArray(size + other.size).also {
        System.arraycopy(this, 0, it, 0, size)
        System.arraycopy(other, 0, it, size, other.size)
    }
}
