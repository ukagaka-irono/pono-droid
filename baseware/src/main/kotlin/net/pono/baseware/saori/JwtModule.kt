package net.pono.baseware.saori

import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64

/** internal_jwt: JWT（JWS のコンパクト形式）の作成と検証 */
object JwtModule {
    val module = SaoriModule(
        "internal_jwt",
        listOf(
            SaoriFunction("Sign", 3, 3, listOf("header", "key-in")) { c -> SaoriResult(sign(c)) },
            SaoriFunction("Verify", 2, 2, listOf("alg", "leeway", "key-in", "time")) { c -> SaoriResult(verify(c)) },
        ),
    )

    private val B64URL = Base64.getUrlEncoder().withoutPadding()

    /** alg の正規の綴り（大文字小文字は見ない） */
    private fun alg(raw: String): String = when (raw.trim().uppercase()) {
        "HS256" -> "HS256"
        "HS384" -> "HS384"
        "HS512" -> "HS512"
        "RS256" -> "RS256"
        "PS256" -> "PS256"
        "ES256" -> "ES256"
        "EDDSA" -> "EdDSA"
        else -> throw SaoriException.bad("unsupported algorithm")
    }

    private fun hmacKey(c: SaoriCall, alg: String): Pair<HMac, ByteArray> {
        val mac = HMac(CryptoModule.digest("sha" + alg.substring(2)))
        val key = c.bytes(1, "key", "text")
        if (key.size < mac.macSize) throw SaoriException.bad("invalid key")
        return mac to key
    }

    private fun hmac(mac: HMac, key: ByteArray, input: ByteArray): ByteArray {
        mac.init(KeyParameter(key))
        mac.update(input, 0, input.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    private fun pkMethod(alg: String) = when (alg) {
        "RS256" -> "rs256"
        "PS256" -> "ps256"
        "ES256" -> "es256"
        else -> "ed25519"
    }

    private fun sign(c: SaoriCall): String {
        val alg = alg(c.arg(0))
        val hs = alg.startsWith("HS")
        if (!hs && c.options.containsKey("key-in")) throw SaoriException.bad("invalid parameter")
        val payloadText = c.arg(2)
        if (MiniJson.parse(payloadText) !is Map<*, *>) throw SaoriException.bad("invalid parameter")
        val header = linkedMapOf<String, Any?>("alg" to alg, "typ" to "JWT")
        c.options["header"]?.let { raw ->
            val extra = MiniJson.parse(raw) as? Map<*, *> ?: throw SaoriException.bad("invalid parameter")
            for ((k, v) in extra) {
                val key = k as String
                if (key == "alg" && v != alg) throw SaoriException.bad("invalid parameter")
                header[key] = v
            }
        }
        val input = B64URL.encodeToString(MiniJson.write(header).toByteArray(Charsets.UTF_8)) + "." +
            B64URL.encodeToString(payloadText.toByteArray(Charsets.UTF_8))
        val data = input.toByteArray(Charsets.US_ASCII)
        val sig = if (hs) {
            val (mac, key) = hmacKey(c, alg)
            hmac(mac, key, data)
        } else {
            PublicKeys.signBytes(pkMethod(alg), c.arg(1), data, c.env.random)
        }
        return input + "." + B64URL.encodeToString(sig)
    }

    private fun verify(c: SaoriCall): String {
        val alg = alg(c.options["alg"] ?: throw SaoriException.bad("invalid parameter"))
        val hs = alg.startsWith("HS")
        if (!hs && c.options.containsKey("key-in")) throw SaoriException.bad("invalid parameter")
        val leeway = c.intOption("leeway", 0, 0..86400)
        val now = c.options["time"]?.let { TimeModule.parse(it, TimeModule.zone(null, c.env), c) } ?: Math.floorDiv(c.env.now(), 1000L)
        // 鍵の誤りは、トークンを読む前に 400
        val hmacKey = if (hs) hmacKey(c, alg) else null
        if (!hs) PublicKeys.checkVerifyKey(pkMethod(alg), c.arg(1))
        val fail = SaoriException.fail("authentication failed")
        val parts = c.arg(0).trim().split('.')
        if (parts.size != 3) throw fail
        fun part(s: String): ByteArray {
            if (s.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) throw fail
            return runCatching { Base64.getUrlDecoder().decode(s) }.getOrElse { throw fail }
        }
        val headerText = utf8OrFail(part(parts[0]))
        val payloadText = utf8OrFail(part(parts[1]))
        val header = runCatching { MiniJson.parse(headerText) }.getOrNull() as? Map<*, *> ?: throw fail
        val payload = runCatching { MiniJson.parse(payloadText) }.getOrNull() as? Map<*, *> ?: throw fail
        if (header["alg"] != alg || header.containsKey("crit")) throw fail
        val input = (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)
        val sig = part(parts[2])
        val ok = if (hmacKey != null) {
            MessageDigest.isEqual(hmac(hmacKey.first, hmacKey.second, input), sig)
        } else {
            PublicKeys.verifyBytes(pkMethod(alg), c.arg(1), input, sig)
        }
        if (!ok) throw fail
        fun time(name: String): BigDecimal? = if (!payload.containsKey(name)) null else payload[name] as? BigDecimal ?: throw fail
        val t = BigDecimal.valueOf(now)
        val lee = BigDecimal.valueOf(leeway.toLong())
        time("exp")?.let { if (t >= it + lee) throw SaoriException.fail("expired") }
        time("nbf")?.let { if (t + lee < it) throw SaoriException.fail("not yet valid") }
        return payloadText.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')
    }

    private fun utf8OrFail(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw SaoriException.fail("authentication failed")
    }
}

/** JWT のための小さな JSON（オブジェクトは LinkedHashMap、数は BigDecimal）。読めなければ invalid parameter */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw SaoriException.bad("invalid parameter")
        return v
    }

    /** 読む JSON の入れ子の深さの上限 */
    private const val MAX_DEPTH = 64

    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> string(sb, v)
            is Boolean -> sb.append(v)
            is BigDecimal -> sb.append(v.toString())
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    string(sb, k.toString())
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, e ->
                    if (i > 0) sb.append(',')
                    write(sb, e)
                }
                sb.append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        private val bad get() = SaoriException.bad("invalid parameter")

        /** 入れ子の深さ。深すぎるもの（[[[[…）は、スタックを使い切る前に invalid parameter にする */
        private var depth = 0

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            if (i >= s.length) throw bad
            return when (s[i]) {
                '{', '[' -> {
                    if (++depth > MAX_DEPTH) throw bad
                    try {
                        if (s[i] == '{') obj() else arr()
                    } finally {
                        depth--
                    }
                }
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw bad
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s.getOrNull(i) == '}') {
                i++
                return m
            }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"') throw bad
                val k = str()
                ws()
                if (s.getOrNull(i) != ':') throw bad
                i++
                ws()
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return m
                    }
                    else -> throw bad
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s.getOrNull(i) == ']') {
                i++
                return l
            }
            while (true) {
                ws()
                l += value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return l
                    }
                    else -> throw bad
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw bad
                val ch = s[i++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (i >= s.length) throw bad
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw bad
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw bad)
                                i += 4
                            }
                            else -> throw bad
                        }
                    }
                    ch < ' ' -> throw bad
                    else -> sb.append(ch)
                }
            }
        }

        private fun num(): BigDecimal {
            val m = NUMBER.find(s, i)?.takeIf { it.range.first == i } ?: throw bad
            i = m.range.last + 1
            return BigDecimal(m.value)
        }
    }

    private val NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
}
