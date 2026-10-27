package net.pono.baseware.saori

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.digests.MD5Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.digests.SHA512tDigest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest

/** internal_crypto: ハッシュ・MAC・共通鍵暗号・鍵の導出・パスワードのハッシュ・データの保護・TOTP・公開鍵暗号 */
object CryptoModule {
    private val BYTES_OUT = listOf("out", "charset")

    val module = SaoriModule(
        "internal_crypto",
        listOf(
            SaoriFunction("Digest", 2, 2, listOf("data-in") + BYTES_OUT) { c ->
                val d = digest(c.arg(0))
                c.feed(1, "data", "text") { b, off, len -> d.update(b, off, len) }
                SaoriResult(c.output(ByteArray(d.digestSize).also { d.doFinal(it, 0) }, "hex"))
            },
            SaoriFunction("HMAC", 3, 3, listOf("key-in", "data-in") + BYTES_OUT) { c ->
                val mac = HMac(digest(c.arg(0)))
                mac.init(KeyParameter(c.bytes(1, "key", "text")))
                c.feed(2, "data", "text") { b, off, len -> mac.update(b, off, len) }
                SaoriResult(c.output(ByteArray(mac.macSize).also { mac.doFinal(it, 0) }, "hex"))
            },
            SaoriFunction("Compare", 2, 2, listOf("a-in", "b-in", "charset")) { c ->
                SaoriResult.bool(MessageDigest.isEqual(c.bytes(0, "a", "text"), c.bytes(1, "b", "text")))
            },
            SaoriFunction("Encrypt", 3, 3, listOf("key-in", "data-in", "aad", "aad-in", "iv", "iv-in", "pack") + BYTES_OUT) { c ->
                SaoriResult(c.output(encrypt(c), "base64"))
            },
            SaoriFunction("Decrypt", 3, 3, listOf("key-in", "ciphertext-in", "aad", "aad-in", "iv", "iv-in", "pack") + BYTES_OUT) { c ->
                SaoriResult(c.output(decrypt(c), "text"))
            },
            SaoriFunction(
                "DeriveKey", 4, 4,
                listOf("password-in", "salt-in", "digest", "iterations", "info", "info-in", "n", "r", "p", "memory", "lanes") + BYTES_OUT,
            ) { c -> SaoriResult(c.output(deriveKey(c), "hex")) },
            SaoriFunction("HashPassword", 1, 1, listOf("method", "password-in", "charset")) { c ->
                SaoriResult(PasswordHash.hash(c.bytes(0, "password", "text"), c.options["method"] ?: "argon2id", c.env.random))
            },
            SaoriFunction("VerifyPassword", 2, 2, listOf("password-in", "charset")) { c ->
                SaoriResult.bool(PasswordHash.verify(c.bytes(0, "password", "text"), c.arg(1).trim()))
            },
            SaoriFunction("Protect", 1, 1, listOf("data-in", "scope") + BYTES_OUT) { c ->
                val scope = c.options["scope"]?.trim()?.lowercase() ?: "user"
                if (scope != "user" && scope != "machine") throw SaoriException.bad("invalid parameter")
                // Android には PC の全ユーザーという区切りが無いので、どちらもこの端末のこのアプリの鍵で包む
                SaoriResult(c.output(c.env.protect(c.bytes(0, "data", "text")), "base64"))
            },
            SaoriFunction("Unprotect", 1, 1, listOf("data-in") + BYTES_OUT) { c ->
                val plain = c.env.unprotect(c.bytes(0, "data", "base64")) ?: throw SaoriException.fail("authentication failed")
                SaoriResult(c.output(plain, "text"))
            },
            SaoriFunction("TOTP", 1, 1, listOf("key-in", "digits", "period", "digest", "time", "charset")) { c -> SaoriResult(totp(c)) },
            SaoriFunction("GenerateKeyPair", 1, 1, emptyList()) { c -> PublicKeys.generate(c.arg(0), c.env.random) },
            SaoriFunction("Sign", 3, 3, listOf("data-in", "out", "charset", "key-in", "aux", "aux-in")) { c -> SaoriResult(PublicKeys.sign(c)) },
            SaoriFunction("Verify", 4, 4, listOf("data-in", "signature-in", "charset", "key-in")) { c -> SaoriResult.bool(PublicKeys.verify(c)) },
            SaoriFunction("KeyAgreement", 3, 3, listOf("key-in", "peer-in", "out")) { c -> SaoriResult(PublicKeys.agree(c)) },
        ),
    )

    /** アルゴリズム名（ハイフン・アンダースコアと大文字小文字は見ない）のハッシュ */
    fun digest(name: String): Digest = when (normalize(name)) {
        "md5" -> MD5Digest()
        "sha1" -> SHA1Digest()
        "sha224" -> SHA224Digest()
        "sha256" -> SHA256Digest()
        "sha384" -> SHA384Digest()
        "sha512" -> SHA512Digest()
        "sha512256" -> SHA512tDigest(256)
        "sha3224" -> SHA3Digest(224)
        "sha3256" -> SHA3Digest(256)
        "sha3384" -> SHA3Digest(384)
        "sha3512" -> SHA3Digest(512)
        "blake2b512" -> Blake2bDigest(512)
        "blake2s256" -> Blake2sDigest(256)
        else -> throw SaoriException.bad("unsupported algorithm")
    }

    fun normalize(name: String) = name.trim().lowercase().replace("-", "").replace("_", "")

    // ---- 共通鍵暗号 ----

    private class Cipher(val keySize: Int, val nonceSize: Int, val aead: Boolean)

    private val CIPHERS = mapOf(
        "aes256gcm" to Cipher(32, 12, true),
        "aes128gcm" to Cipher(16, 12, true),
        "chacha20poly1305" to Cipher(32, 12, true),
        "aes256cbc" to Cipher(32, 16, false),
        "aes128cbc" to Cipher(16, 16, false),
        "aes256ctr" to Cipher(32, 16, false),
        "chacha20" to Cipher(32, 12, false),
    )

    private fun cipherOf(c: SaoriCall): Pair<String, Cipher> {
        val name = normalize(c.arg(0))
        val cipher = CIPHERS[name] ?: throw SaoriException.bad("unsupported algorithm")
        val pack = c.options["pack"]
        if (pack != null && !pack.equals("none", ignoreCase = true)) throw SaoriException.bad("invalid parameter")
        if (!cipher.aead && c.options.containsKey("aad")) throw SaoriException.bad("invalid parameter")
        return name to cipher
    }

    private fun key(c: SaoriCall, cipher: Cipher): ByteArray =
        c.bytes(1, "key", "text").also { if (it.size != cipher.keySize) throw SaoriException.bad("invalid key") }

    private fun iv(c: SaoriCall, cipher: Cipher): ByteArray? =
        c.optionBytes("iv", "hex")?.also { if (it.size != cipher.nonceSize) throw SaoriException.bad("invalid parameter") }

    private fun encrypt(c: SaoriCall): ByteArray {
        val (name, cipher) = cipherOf(c)
        val key = key(c, cipher)
        val packed = c.options["pack"] == null
        val nonce = iv(c, cipher) ?: if (packed) ByteArray(cipher.nonceSize).also(c.env.random::nextBytes) else throw SaoriException.bad("invalid parameter")
        val body = run(name, true, key, nonce, c.optionBytes("aad", "text"), c.bytes(2, "data", "text"))
        return if (packed) nonce + body else body
    }

    private fun decrypt(c: SaoriCall): ByteArray {
        val (name, cipher) = cipherOf(c)
        val key = key(c, cipher)
        val data = c.bytes(2, "ciphertext", "base64")
        val packed = c.options["pack"] == null
        val given = iv(c, cipher)
        val (nonce, body) = if (packed) {
            if (given != null) throw SaoriException.bad("invalid parameter")
            if (data.size < cipher.nonceSize) throw SaoriException.fail("authentication failed")
            data.copyOfRange(0, cipher.nonceSize) to data.copyOfRange(cipher.nonceSize, data.size)
        } else {
            (given ?: throw SaoriException.bad("invalid parameter")) to data
        }
        return run(name, false, key, nonce, c.optionBytes("aad", "text"), body)
    }

    private fun run(name: String, encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray?, input: ByteArray): ByteArray = try {
        when (name) {
            "aes256gcm", "aes128gcm", "chacha20poly1305" -> {
                val c = if (name == "chacha20poly1305") ChaCha20Poly1305() else GCMBlockCipher.newInstance(AESEngine.newInstance())
                c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
                val out = ByteArray(c.getOutputSize(input.size))
                val n = c.processBytes(input, 0, input.size, out, 0)
                val total = n + c.doFinal(out, n)
                out.copyOf(total)
            }
            "aes256cbc", "aes128cbc" -> {
                val c = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(AESEngine.newInstance()), PKCS7Padding())
                c.init(encrypt, ParametersWithIV(KeyParameter(key), nonce))
                val out = ByteArray(c.getOutputSize(input.size))
                val n = c.processBytes(input, 0, input.size, out, 0)
                out.copyOf(n + c.doFinal(out, n))
            }
            "aes256ctr" -> {
                val c = SICBlockCipher.newInstance(AESEngine.newInstance())
                c.init(encrypt, ParametersWithIV(KeyParameter(key), nonce))
                ByteArray(input.size).also { c.processBytes(input, 0, input.size, it, 0) }
            }
            else -> {
                val c = ChaCha7539Engine()
                c.init(encrypt, ParametersWithIV(KeyParameter(key), nonce))
                ByteArray(input.size).also { c.processBytes(input, 0, input.size, it, 0) }
            }
        }
    } catch (e: InvalidCipherTextException) {
        throw SaoriException.fail("authentication failed")
    } catch (e: org.bouncycastle.crypto.DataLengthException) {
        if (encrypt) throw SaoriException.fail("internal error") else throw SaoriException.fail("authentication failed")
    }

    // ---- 鍵の導出 ----

    private val KDF_OPTIONS = mapOf(
        "pbkdf2" to setOf("digest", "iterations"),
        "hkdf" to setOf("digest", "info", "info-in"),
        "hkdfextract" to setOf("digest"),
        "hkdfexpand" to setOf("digest", "info", "info-in"),
        "scrypt" to setOf("n", "r", "p"),
        "argon2id" to setOf("iterations", "memory", "lanes"),
    )
    private val KDF_ALL = KDF_OPTIONS.values.flatten().toSet()

    /** メモリの上限（scrypt・argon2id。KiB） */
    const val MAX_MEMORY_KIB = 12288

    private fun deriveKey(c: SaoriCall): ByteArray {
        val method = normalize(c.arg(0))
        val allowed = KDF_OPTIONS[method] ?: throw SaoriException.bad("unsupported algorithm")
        if (c.options.keys.any { it in KDF_ALL && it !in allowed }) throw SaoriException.bad("invalid parameter")
        val password = c.bytes(1, "password", "text")
        val salt = c.bytes(2, "salt", "text")
        val length = c.intArg(3, 1..1024)
        return when (method) {
            "pbkdf2" -> pbkdf2(digest(c.options["digest"] ?: "sha256"), password, salt, c.intOption("iterations", 600_000, 1..10_000_000), length)
            "hkdf", "hkdfexpand" -> {
                val d = digest(c.options["digest"] ?: "sha256")
                if (password.isEmpty()) throw SaoriException.bad("invalid parameter")
                if (length > d.digestSize * 255) throw SaoriException.bad("out of range")
                val info = c.optionBytes("info", "text") ?: ByteArray(0)
                val params = if (method == "hkdf") {
                    HKDFParameters(password, salt, info)
                } else {
                    if (salt.isNotEmpty()) throw SaoriException.bad("invalid parameter")
                    HKDFParameters.skipExtractParameters(password, info)
                }
                ByteArray(length).also { HKDFBytesGenerator(d).apply { init(params) }.generateBytes(it, 0, length) }
            }
            "hkdfextract" -> {
                val d = digest(c.options["digest"] ?: "sha256")
                if (password.isEmpty()) throw SaoriException.bad("invalid parameter")
                if (length != d.digestSize) throw SaoriException.bad("out of range")
                hkdfExtract(d, salt, password)
            }
            "scrypt" -> {
                val n = c.intOption("n", 8192, 2..Int.MAX_VALUE)
                val r = c.intOption("r", 8, 1..Int.MAX_VALUE)
                val p = c.intOption("p", 10, 1..16)
                if (n and (n - 1) != 0) throw SaoriException.bad("out of range")
                scrypt(password, salt, n, r, p, length)
            }
            else -> {
                val lanes = c.intOption("lanes", 1, 1..16)
                val memory = c.intOption("memory", MAX_MEMORY_KIB, lanes * 8..MAX_MEMORY_KIB)
                argon2id(password, salt, c.intOption("iterations", 3, 1..1000), memory, lanes, length, Argon2Parameters.ARGON2_VERSION_13)
            }
        }
    }

    fun pbkdf2(d: Digest, password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val gen = PKCS5S2ParametersGenerator(d)
        gen.init(password, salt, iterations)
        return (gen.generateDerivedParameters(length * 8) as KeyParameter).key
    }

    fun hkdfExtract(d: Digest, salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = HMac(d)
        mac.init(KeyParameter(if (salt.isEmpty()) ByteArray(d.digestSize) else salt))
        mac.update(ikm, 0, ikm.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /** N×r×128 バイトが上限（12MiB）まで */
    fun scrypt(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, length: Int): ByteArray {
        if (n.toLong() * r * 128 > MAX_MEMORY_KIB * 1024L || p !in 1..16) throw SaoriException.bad("out of range")
        return SCrypt.generate(password, salt, n, r, p, length)
    }

    fun argon2id(password: ByteArray, salt: ByteArray, iterations: Int, memoryKib: Int, lanes: Int, length: Int, version: Int): ByteArray {
        if (salt.size < 8 || length < 4) throw SaoriException.bad("out of range")
        if (iterations !in 1..1000 || lanes !in 1..16 || memoryKib !in lanes * 8..MAX_MEMORY_KIB) throw SaoriException.bad("out of range")
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(version)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKib)
            .withParallelism(lanes)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        return ByteArray(length).also { gen.generateBytes(password, it) }
    }

    // ---- TOTP（RFC 6238） ----

    private fun totp(c: SaoriCall): String {
        val key = c.bytes(0, "key", "base32")
        val digits = c.intOption("digits", 6, 6..10)
        val period = c.intOption("period", 30, 1..86400)
        val digestName = c.options["digest"] ?: "sha1"
        if (normalize(digestName) == "md5") throw SaoriException.bad("unsupported algorithm")
        val mac = HMac(digest(digestName))
        val time = c.options["time"]?.let { TimeModule.parse(it, TimeModule.zone(null, c.env), c) } ?: Math.floorDiv(c.env.now(), 1000L)
        val counter = Math.floorDiv(time, period.toLong())
        val msg = ByteArray(8) { (counter ushr (8 * (7 - it))).toByte() }
        mac.init(KeyParameter(key))
        mac.update(msg, 0, 8)
        val h = ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
        val off = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[off].toInt() and 0x7f) shl 24) or ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or (h[off + 3].toInt() and 0xff)
        var mod = 1L
        repeat(digits) { mod *= 10 }
        return (bin.toLong() % mod).toString().padStart(digits, '0')
    }
}
