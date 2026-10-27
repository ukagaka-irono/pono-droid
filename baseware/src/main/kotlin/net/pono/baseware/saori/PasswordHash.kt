package net.pono.baseware.saori

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.MD5Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.OpenBSDBCrypt
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** internal_crypto の HashPassword / VerifyPassword。PHC 文字列と crypt(3) の形式 */
object PasswordHash {
    /** crypt(3) の文字（./0-9A-Za-z） */
    private const val ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val B64 = Base64.getEncoder().withoutPadding()

    fun hash(password: ByteArray, methodName: String, random: SecureRandom): String {
        if (password.size > 1024) throw SaoriException.bad("out of range")
        val method = CryptoModule.normalize(methodName)
        if (method !in setOf("argon2id", "scrypt", "pbkdf2sha256") && password.any { it == 0.toByte() }) throw SaoriException.bad("invalid parameter")
        fun salt(n: Int) = ByteArray(n).also(random::nextBytes)
        fun cryptSalt(n: Int) = String(CharArray(n) { ITOA64[random.nextInt(64)] })
        return when (method) {
            "argon2id" -> {
                val s = salt(16)
                val h = CryptoModule.argon2id(password, s, 3, CryptoModule.MAX_MEMORY_KIB, 1, 32, Argon2Parameters.ARGON2_VERSION_13)
                "\$argon2id\$v=19\$m=12288,t=3,p=1\$${B64.encodeToString(s)}\$${B64.encodeToString(h)}"
            }
            "scrypt" -> {
                val s = salt(16)
                "\$scrypt\$ln=13,r=8,p=10\$${B64.encodeToString(s)}\$${B64.encodeToString(CryptoModule.scrypt(password, s, 8192, 8, 10, 32))}"
            }
            "pbkdf2sha256" -> {
                val s = salt(16)
                "\$pbkdf2-sha256\$i=600000,l=32\$${B64.encodeToString(s)}\$${B64.encodeToString(CryptoModule.pbkdf2(SHA256Digest(), password, s, 600_000, 32))}"
            }
            "bcrypt" -> OpenBSDBCrypt.generate("2b", password.copyOf(minOf(password.size, 72)), salt(16), 12)
            "sha512crypt" -> shaCrypt(password, "6", cryptSalt(16), 656_000, explicitRounds = true)
            "sha256crypt" -> shaCrypt(password, "5", cryptSalt(16), 535_000, explicitRounds = true)
            "md5crypt" -> md5Crypt(password, "\$1\$", cryptSalt(8))
            "apr1" -> md5Crypt(password, "\$apr1\$", cryptSalt(8))
            "bsdicrypt" -> DesCrypt.extended(password, 5001, cryptSalt(4))
            "descrypt" -> DesCrypt.traditional(password, cryptSalt(2))
            else -> throw SaoriException.bad("unsupported algorithm")
        }
    }

    fun verify(password: ByteArray, stored: String): Boolean {
        if (password.size > 1024) throw SaoriException.bad("out of range")
        val s = stored.trim()
        return when {
            s.startsWith("\$argon2id\$") -> verifyArgon2(password, s)
            s.startsWith("\$scrypt\$") -> verifyScrypt(password, s)
            s.startsWith("\$pbkdf2-") -> verifyPbkdf2(password, s)
            s.startsWith("\$2a\$") || s.startsWith("\$2b\$") || s.startsWith("\$2y\$") -> {
                val cost = s.substring(4).substringBefore('$').toIntOrNull() ?: throw SaoriException.bad("invalid parameter")
                if (s.length != 60) throw SaoriException.bad("invalid parameter")
                if (cost !in 4..16) throw SaoriException.bad("out of range")
                if (password.any { it == 0.toByte() }) throw SaoriException.bad("invalid parameter")
                try {
                    OpenBSDBCrypt.checkPassword(s, password.copyOf(minOf(password.size, 72)))
                } catch (e: IllegalArgumentException) {
                    throw SaoriException.bad("invalid parameter")
                }
            }
            s.startsWith("\$6\$") || s.startsWith("\$5\$") -> {
                noNul(password)
                val v = if (s[1] == '6') "6" else "5"
                val rest = s.substring(3)
                val parts = rest.split('$')
                val explicit = parts[0].startsWith("rounds=")
                val rounds = if (explicit) {
                    val r = parts[0].removePrefix("rounds=").toLongOrNull() ?: throw SaoriException.bad("invalid parameter")
                    if (r > 10_000_000) throw SaoriException.bad("out of range")
                    maxOf(r, 1000).toInt()
                } else {
                    5000
                }
                val saltPart = if (explicit) parts.getOrNull(1) else parts.getOrNull(0)
                if (saltPart == null || parts.size != (if (explicit) 3 else 2)) throw SaoriException.bad("invalid parameter")
                // rounds= を 1000 に切り上げたものやソルトが長いものも読めるように、ハッシュの部分だけ比べる
                same(shaCrypt(password, v, saltPart, rounds, explicit).substringAfterLast('$'), s.substringAfterLast('$'))
            }
            s.startsWith("\$1\$") || s.startsWith("\$apr1\$") -> {
                noNul(password)
                val magic = if (s.startsWith("\$1\$")) "\$1\$" else "\$apr1\$"
                val rest = s.substring(magic.length)
                if (rest.count { it == '$' } != 1) throw SaoriException.bad("invalid parameter")
                same(md5Crypt(password, magic, rest.substringBefore('$')), s)
            }
            s.startsWith("_") && s.length == 20 -> {
                noNul(password)
                if (s.substring(1).any { it !in ITOA64 }) throw SaoriException.bad("invalid parameter")
                same(DesCrypt.extended(password, DesCrypt.decode24(s.substring(1, 5)), s.substring(5, 9)), s)
            }
            s.length == 13 && s.all { it in ITOA64 } -> {
                noNul(password)
                same(DesCrypt.traditional(password, s.substring(0, 2)), s)
            }
            s.startsWith("\$2x\$") || s.startsWith("\$2\$") -> throw SaoriException.bad("unsupported algorithm")
            s.startsWith("$") -> throw SaoriException.bad("unsupported algorithm")
            else -> throw SaoriException.bad("invalid parameter")
        }
    }

    private fun noNul(password: ByteArray) {
        if (password.any { it == 0.toByte() }) throw SaoriException.bad("invalid parameter")
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    /** PHC のパラメータ（m=…,t=…,p=…） */
    private fun params(s: String): Map<String, String> = s.split(',').associate {
        val k = it.substringBefore('=', "")
        if (k.isEmpty()) throw SaoriException.bad("invalid parameter")
        k to it.substringAfter('=')
    }

    /** PHC のソルト・ハッシュ（パディングなしの base64。passlib の ab64 の . も読む） */
    private fun phc64(s: String): ByteArray = try {
        Base64.getDecoder().decode(s.replace('.', '+'))
    } catch (e: IllegalArgumentException) {
        throw SaoriException.bad("invalid parameter")
    }

    private fun intParam(map: Map<String, String>, key: String): Int =
        map[key]?.toIntOrNull() ?: throw SaoriException.bad("invalid parameter")

    private fun verifyArgon2(password: ByteArray, s: String): Boolean {
        val parts = s.split('$').drop(2) // "", "argon2id" を除く
        val (version, rest) = if (parts.firstOrNull()?.startsWith("v=") == true) {
            when (parts[0]) {
                "v=19" -> Argon2Parameters.ARGON2_VERSION_13
                "v=16" -> Argon2Parameters.ARGON2_VERSION_10
                else -> throw SaoriException.bad("invalid parameter")
            } to parts.drop(1)
        } else {
            Argon2Parameters.ARGON2_VERSION_10 to parts
        }
        if (rest.size != 3) throw SaoriException.bad("invalid parameter")
        val p = params(rest[0])
        val salt = phc64(rest[1])
        val expected = phc64(rest[2])
        val actual = CryptoModule.argon2id(password, salt, intParam(p, "t"), intParam(p, "m"), intParam(p, "p"), expected.size, version)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun verifyScrypt(password: ByteArray, s: String): Boolean {
        val parts = s.split('$').drop(2)
        if (parts.size != 3) throw SaoriException.bad("invalid parameter")
        val p = params(parts[0])
        val ln = intParam(p, "ln")
        if (ln !in 1..30) throw SaoriException.bad("out of range")
        val expected = phc64(parts[2])
        val actual = CryptoModule.scrypt(password, phc64(parts[1]), 1 shl ln, intParam(p, "r"), intParam(p, "p"), expected.size)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun verifyPbkdf2(password: ByteArray, s: String): Boolean {
        val parts = s.split('$').drop(1)
        if (parts.size != 4) throw SaoriException.bad("invalid parameter")
        val digest: Digest = when (parts[0]) {
            "pbkdf2-sha1" -> CryptoModule.digest("sha1")
            "pbkdf2-sha256" -> SHA256Digest()
            "pbkdf2-sha512" -> SHA512Digest()
            else -> throw SaoriException.bad("unsupported algorithm")
        }
        // passlib の形（$pbkdf2-sha256$29000$…）と、i=…,l=… の形
        val iterations = parts[1].toIntOrNull() ?: intParam(params(parts[1]), "i")
        if (iterations !in 1..10_000_000) throw SaoriException.bad("out of range")
        val expected = phc64(parts[3])
        if (expected.isEmpty() || expected.size > 1024) throw SaoriException.bad("invalid parameter")
        return MessageDigest.isEqual(CryptoModule.pbkdf2(digest, password, phc64(parts[2]), iterations, expected.size), expected)
    }

    // ---- SHA-crypt（$5$ / $6$。Ulrich Drepper の仕様） ----

    private val SHA256_ORDER = intArrayOf(0, 10, 20, 21, 1, 11, 12, 22, 2, 3, 13, 23, 24, 4, 14, 15, 25, 5, 6, 16, 26, 27, 7, 17, 18, 28, 8, 9, 19, 29)
    private val SHA512_ORDER = intArrayOf(
        0, 21, 42, 22, 43, 1, 44, 2, 23, 3, 24, 45, 25, 46, 4, 47, 5, 26, 6, 27, 48, 28, 49, 7, 50, 8, 29, 9, 30, 51, 31, 52, 10,
        53, 11, 32, 12, 33, 54, 34, 55, 13, 56, 14, 35, 15, 36, 57, 37, 58, 16, 59, 17, 38, 18, 39, 60, 40, 61, 19, 62, 20, 41,
    )

    private fun hashOf(d: Digest, vararg parts: ByteArray): ByteArray {
        d.reset()
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    private fun repeatTo(src: ByteArray, len: Int) = ByteArray(len) { src[it % src.size] }

    fun shaCrypt(pw: ByteArray, v: String, saltRaw: String, rounds: Int, explicitRounds: Boolean): String {
        val d: Digest = if (v == "6") SHA512Digest() else SHA256Digest()
        val saltStr = saltRaw.take(16)
        val salt = saltStr.toByteArray(Charsets.ISO_8859_1)
        val n = d.digestSize
        val b = hashOf(d, pw, salt, pw)
        d.reset()
        d.update(pw, 0, pw.size)
        d.update(salt, 0, salt.size)
        var left = pw.size
        while (left > n) {
            d.update(b, 0, n)
            left -= n
        }
        d.update(b, 0, left)
        var i = pw.size
        while (i > 0) {
            if (i and 1 != 0) d.update(b, 0, n) else d.update(pw, 0, pw.size)
            i = i shr 1
        }
        val a = ByteArray(n).also { d.doFinal(it, 0) }
        d.reset()
        repeat(pw.size) { d.update(pw, 0, pw.size) }
        val dp = ByteArray(n).also { d.doFinal(it, 0) }
        val p = if (pw.isEmpty()) ByteArray(0) else repeatTo(dp, pw.size)
        d.reset()
        repeat(16 + (a[0].toInt() and 0xff)) { d.update(salt, 0, salt.size) }
        val ds = ByteArray(n).also { d.doFinal(it, 0) }
        val sBytes = repeatTo(ds, salt.size)
        var c = a
        for (r in 0 until rounds) {
            d.reset()
            if (r and 1 != 0) d.update(p, 0, p.size) else d.update(c, 0, n)
            if (r % 3 != 0) d.update(sBytes, 0, sBytes.size)
            if (r % 7 != 0) d.update(p, 0, p.size)
            if (r and 1 != 0) d.update(c, 0, n) else d.update(p, 0, p.size)
            c = ByteArray(n).also { d.doFinal(it, 0) }
        }
        val sb = StringBuilder("\$$v\$")
        if (explicitRounds) sb.append("rounds=$rounds").append('$')
        sb.append(saltStr).append('$')
        val order = if (v == "6") SHA512_ORDER else SHA256_ORDER
        var k = 0
        while (k + 2 < order.size) {
            to64(sb, ((c[order[k]].toInt() and 0xff) shl 16) or ((c[order[k + 1]].toInt() and 0xff) shl 8) or (c[order[k + 2]].toInt() and 0xff), 4)
            k += 3
        }
        if (v == "6") to64(sb, c[63].toInt() and 0xff, 2) else to64(sb, ((c[31].toInt() and 0xff) shl 8) or (c[30].toInt() and 0xff), 3)
        return sb.toString()
    }

    private fun to64(sb: StringBuilder, value: Int, n: Int) {
        var v = value
        repeat(n) {
            sb.append(ITOA64[v and 0x3f])
            v = v ushr 6
        }
    }

    // ---- MD5-crypt（$1$ と Apache の $apr1$） ----

    fun md5Crypt(pw: ByteArray, magic: String, saltRaw: String): String {
        val saltStr = saltRaw.take(8)
        val salt = saltStr.toByteArray(Charsets.ISO_8859_1)
        val magicBytes = magic.toByteArray(Charsets.US_ASCII)
        val d = MD5Digest()
        val alt = hashOf(d, pw, salt, pw)
        d.reset()
        d.update(pw, 0, pw.size)
        d.update(magicBytes, 0, magicBytes.size)
        d.update(salt, 0, salt.size)
        var pl = pw.size
        while (pl > 0) {
            d.update(alt, 0, minOf(pl, 16))
            pl -= 16
        }
        var i = pw.size
        while (i != 0) {
            if (i and 1 != 0) d.update(0) else d.update(pw[0])
            i = i shr 1
        }
        var fin = ByteArray(16).also { d.doFinal(it, 0) }
        for (r in 0 until 1000) {
            d.reset()
            if (r and 1 != 0) d.update(pw, 0, pw.size) else d.update(fin, 0, 16)
            if (r % 3 != 0) d.update(salt, 0, salt.size)
            if (r % 7 != 0) d.update(pw, 0, pw.size)
            if (r and 1 != 0) d.update(fin, 0, 16) else d.update(pw, 0, pw.size)
            fin = ByteArray(16).also { d.doFinal(it, 0) }
        }
        val sb = StringBuilder(magic).append(saltStr).append('$')
        fun b(k: Int) = fin[k].toInt() and 0xff
        for ((x, y, z) in listOf(Triple(0, 6, 12), Triple(1, 7, 13), Triple(2, 8, 14), Triple(3, 9, 15), Triple(4, 10, 5))) {
            to64(sb, (b(x) shl 16) or (b(y) shl 8) or b(z), 4)
        }
        to64(sb, b(11), 2)
        return sb.toString()
    }
}

/**
 * crypt(3) の DES（伝統的な DES と BSDi の拡張 DES）。ソルトで E の拡大の出力のビットを入れ替える DES を、ビットごとに組む
 */
object DesCrypt {
    private const val ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val IP = intArrayOf(
        58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
    )
    private val FP = intArrayOf(
        40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
        36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25,
    )
    private val E = intArrayOf(
        32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11, 12, 13, 12, 13, 14, 15, 16, 17,
        16, 17, 18, 19, 20, 21, 20, 21, 22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1,
    )
    private val P = intArrayOf(16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10, 2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25)
    private val PC1 = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
        63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4,
    )
    private val PC2 = intArrayOf(
        14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10, 23, 19, 12, 4, 26, 8, 16, 7, 27, 20, 13, 2,
        41, 52, 31, 37, 47, 55, 30, 40, 51, 45, 33, 48, 44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32,
    )
    private val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val S = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    /** [input]（上位から [bits] ビット）を [table]（1 から数えた入力の位置）で並べ替える */
    private fun permute(input: Long, bits: Int, table: IntArray): Long {
        var out = 0L
        for (pos in table) out = (out shl 1) or ((input ushr (bits - pos)) and 1L)
        return out
    }

    /** 64 ビットの鍵から 16 個の 48 ビットの副鍵 */
    fun schedule(key: Long): LongArray {
        val k56 = permute(key, 64, PC1)
        var c = (k56 ushr 28) and 0xFFFFFFF
        var d = k56 and 0xFFFFFFF
        return LongArray(16) { i ->
            repeat(SHIFTS[i]) {
                c = ((c shl 1) or (c ushr 27)) and 0xFFFFFFF
                d = ((d shl 1) or (d ushr 27)) and 0xFFFFFFF
            }
            permute((c shl 28) or d, 56, PC2)
        }
    }

    /** [block] を [count] 回暗号にする。[saltBits] は E の出力の左右 24 ビットのどこを入れ替えるか */
    fun encrypt(block: Long, subkeys: LongArray, saltBits: Int, count: Int): Long {
        var b = block
        repeat(count) {
            val ip = permute(b, 64, IP)
            var l = (ip ushr 32) and 0xFFFFFFFFL
            var r = ip and 0xFFFFFFFFL
            for (k in subkeys) {
                var e = permute(r, 32, E)
                val el = (e ushr 24) and 0xFFFFFF
                val er = e and 0xFFFFFF
                val f = (el xor er) and saltBits.toLong()
                e = ((el xor f) shl 24) or (er xor f)
                e = e xor k
                var s = 0L
                for (box in 0 until 8) {
                    val six = ((e ushr (42 - 6 * box)) and 0x3F).toInt()
                    val row = ((six ushr 4) and 2) or (six and 1)
                    val col = (six ushr 1) and 0xF
                    s = (s shl 4) or S[box][row * 16 + col].toLong()
                }
                val next = l xor permute(s, 32, P)
                l = r
                r = next
            }
            b = permute((r shl 32) or l, 64, FP)
        }
        return b
    }

    /** ソルト（24 ビットまで）を、E の出力のビットの入れ替えの印にする（ソルトの i ビット目が左右の i 番目） */
    private fun saltBits(salt: Int): Int {
        var bits = 0
        for (i in 0 until 24) if ((salt ushr i) and 1 != 0) bits = bits or (0x800000 ushr i)
        return bits
    }

    private fun bin(c: Char): Int = ITOA64.indexOf(c).also { if (it < 0) throw SaoriException.bad("invalid parameter") }

    /** 4 文字（下位の 6 ビットから）の数 */
    fun decode24(s: String): Int = s.foldIndexed(0) { i, acc, ch -> acc or (bin(ch) shl (6 * i)) }

    private fun encode24(v: Int) = String(CharArray(4) { ITOA64[(v ushr (6 * it)) and 0x3f] })

    private fun keyBlock(bytes: ByteArray): Long = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }

    private fun output(v: Long): String {
        val sb = StringBuilder()
        for (i in 0 until 11) {
            val shift = 58 - 6 * i
            val six = if (shift >= 0) (v ushr shift) and 0x3f else (v shl -shift) and 0x3f
            sb.append(ITOA64[six.toInt()])
        }
        return sb.toString()
    }

    /** 伝統的な DES の crypt（パスワードは先頭の 8 文字） */
    fun traditional(pw: ByteArray, salt: String): String {
        val s = (bin(salt[1]) shl 6) or bin(salt[0])
        val key = ByteArray(8) { i -> if (i < pw.size) ((pw[i].toInt() shl 1) and 0xff).toByte() else 0 }
        return salt.substring(0, 2) + output(encrypt(0L, schedule(keyBlock(key)), saltBits(s), 25))
    }

    /** BSDi の拡張 DES（_ ＋回数 4 文字＋ソルト 4 文字。パスワードの長さに制限なし） */
    fun extended(pw: ByteArray, count: Int, salt: String): String {
        if (count <= 0) throw SaoriException.bad("invalid parameter")
        val keyBuf = ByteArray(8)
        var p = 0
        for (i in 0 until 8) {
            keyBuf[i] = ((if (p < pw.size) pw[p].toInt() else 0) shl 1).toByte()
            if (p < pw.size) p++
        }
        var subkeys = schedule(keyBlock(keyBuf))
        while (p < pw.size) {
            // 鍵をその鍵で暗号にして、続きの 8 文字と xor する
            val enc = encrypt(keyBlock(keyBuf), subkeys, 0, 1)
            for (i in 0 until 8) keyBuf[i] = (enc ushr (56 - 8 * i)).toByte()
            var q = 0
            while (q < 8 && p < pw.size) {
                keyBuf[q] = (keyBuf[q].toInt() xor (pw[p].toInt() shl 1)).toByte()
                q++
                p++
            }
            subkeys = schedule(keyBlock(keyBuf))
        }
        return "_" + encode24(count) + salt + output(encrypt(0L, subkeys, saltBits(decode24(salt)), count))
    }
}
