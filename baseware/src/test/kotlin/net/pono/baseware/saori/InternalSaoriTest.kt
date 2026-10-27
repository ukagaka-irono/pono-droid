package net.pono.baseware.saori

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.time.ZoneId

/** 内蔵 SAORI（SAORI over DirectSSTP の internal_*）。仕様書の記述例とテストベクタで確かめる */
class InternalSaoriTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Env(override val ghostMaster: File?, override val ghostRoot: File?) : SaoriEnv {
        override val random = SecureRandom()
        var nowMs = 1_800_000_000_000L
        override fun now() = nowMs
        override val localZone: ZoneId = ZoneId.of("Asia/Tokyo")
        val events = mutableListOf<Pair<String, List<String>>>()
        var media: MediaInfo? = null

        override fun windowsZone(name: String) = if (name.equals("Tokyo Standard Time", ignoreCase = true)) "Asia/Tokyo" else null

        // テストでは、鍵の代わりに印を付けて逆さにするだけ
        override fun protect(data: ByteArray) = byteArrayOf(0x50) + data.reversedArray()

        override fun unprotect(data: ByteArray) = if (data.firstOrNull() == 0x50.toByte()) data.copyOfRange(1, data.size).reversedArray() else null

        override fun mediaInfo(file: File) = media

        override fun async(work: () -> Pair<String, List<String>>) {
            events += work()
        }
    }

    private val ghostRoot by lazy { tmp.newFolder("ghost1") }
    private val master by lazy { File(ghostRoot, "ghost/master").also { it.mkdirs() } }
    private val env by lazy { Env(master, ghostRoot) }

    private fun call(module: String, vararg args: String, e: SaoriEnv = env): SaoriResponse {
        val sb = StringBuilder("EXECUTE SAORI/1.0\r\nModule: $module\r\nCharset: UTF-8\r\nSender: test\r\nSecurityLevel: Local\r\n")
        args.forEachIndexed { i, a -> sb.append("Argument$i: $a\r\n") }
        sb.append("\r\n")
        return InternalSaori(e).answer(SaoriRequest.parse(sb.toString().toByteArray())!!)
    }

    private fun result(module: String, vararg args: String): String {
        val r = call(module, *args)
        assertEquals("${r.error}", 200, r.code)
        return r.result!!
    }

    private fun error(module: String, vararg args: String): String {
        val r = call(module, *args)
        assertTrue("expected an error but got ${r.code} ${r.result}", r.code == 400 || r.code == 500)
        return "${r.code} ${r.error}"
    }

    // ---- 共通の決まり ----

    @Test
    fun protocolAndCommonErrors() {
        assertEquals("400 unknown module", error("internal_nothing", "Foo"))
        assertEquals("400 unknown function", error("internal_crypto", "Foo"))
        assertEquals("400 invalid parameter", error("internal_crypto", "Digest", "sha256"))
        assertEquals("400 invalid parameter", error("internal_crypto", "Digest", "sha256", "abc", "extra"))
        assertEquals("400 invalid parameter", error("internal_crypto", "Digest", "sha256", "abc", "--nosuch=1"))
        // 位置引数の位置にある -- で始まる値はデータ
        assertEquals(result("internal_crypto", "Digest", "sha256", "--out=HEX"), result("internal_crypto", "Digest", "sha256", "--out=HEX", "--out=hex"))
        // 関数名とオプションの名前は大文字小文字を見ない。同じオプションは後のものが有効
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", result("internal_crypto", "digest", "SHA-256", "abc", "--OUT=hex", "--out=HEX"))
        // GET Version
        val v = InternalSaori(env).answer(SaoriRequest.parse("GET Version SAORI/1.0\r\nModule: internal_time\r\nCharset: UTF-8\r\n\r\n".toByteArray())!!)
        assertEquals(200, v.code)
        // 結果が空なら 204
        assertEquals(204, call("internal_data", "Convert", "").code)
    }

    @Test
    fun wireFormatFollowsTheRequestCharset() {
        val req = "EXECUTE SAORI/1.0\r\nModule: internal_text\r\nCharset: Shift_JIS\r\nArgument0: Normalize\r\nArgument1: NFKC\r\nArgument2: ｶﾞｲﾄﾞ①\r\n\r\n"
        val bytes = InternalSaori(env).handle(req.toByteArray(charset("windows-31j")))
        val text = String(bytes, charset("windows-31j"))
        assertTrue(text, text.startsWith("SAORI/1.0 200 OK\r\nCharset: Shift_JIS\r\nResult: ガイド1\r\n"))
        // Module ヘッダが無ければ、YAYA が読んだ名前を使う
        val noModule = "EXECUTE SAORI/1.0\r\nCharset: UTF-8\r\nArgument0: ListFunctions\r\nArgument1: internal_jwt\r\n\r\n"
        val r = String(InternalSaori(env).handle(noModule.toByteArray(), "internal_system"))
        assertTrue(r, "Result: Sign,Verify\r\nValue0: Sign\r\nValue1: Verify\r\n" in r)
        val noName = String(InternalSaori(env).handle(noModule.toByteArray()))
        assertTrue(noName, "ErrorDescription: invalid parameter" in noName)
    }

    @Test
    fun asyncCallsDeliverEvents() {
        assertEquals(204, call("internal_crypto", "Digest", "sha256", "abc", "--async=OnHashDone").code)
        assertEquals(204, call("internal_crypto", "Digest", "sha256", "zz", "--data-in=hex", "--async=job1").code)
        assertEquals(204, call("internal_random", "UUID", "--async=OnId").code)
        assertEquals(
            "OnHashDone" to listOf("OnHashDone", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "internal_crypto", "Digest"),
            env.events[0],
        )
        // 中身の誤りは失敗のイベントで
        assertEquals("OnInternalSaoriFailure" to listOf("job1", "invalid encoding", "internal_crypto", "Digest"), env.events[1])
        assertEquals("OnId", env.events[2].first)
        // 受付の誤り・空の識別子・ゴーストなし
        assertEquals("400 invalid parameter", error("internal_crypto", "Digest", "sha256", "abc", "--async="))
        assertEquals(400, call("internal_crypto", "Digest", "sha256", "abc", "--async=OnX", e = Env(null, null)).code)
        assertEquals("ghost required", call("internal_crypto", "Digest", "sha256", "abc", "--async=OnX", e = Env(null, null)).error)
        // 複数の値は Reference4 以降
        assertEquals(204, call("internal_system", "ListFunctions", "internal_jwt", "--async=OnList").code)
        assertEquals(listOf("OnList", "Sign,Verify", "internal_system", "ListFunctions", "Sign", "Verify"), env.events[3].second)
    }

    // ---- internal_system・random・text ----

    @Test
    fun systemRandomText() {
        val modules = call("internal_system", "ListModules")
        assertEquals(8, modules.values.size)
        assertTrue("internal_system" in modules.values)
        assertEquals("400 unknown module", error("internal_system", "ListFunctions", "string"))

        assertEquals(32, result("internal_random", "Bytes", "16").length)
        assertEquals(24, result("internal_random", "Bytes", "16", "--out=base64").length)
        assertEquals("400 out of range", error("internal_random", "Bytes", "0"))
        assertEquals("400 out of range", error("internal_random", "Bytes", "1025"))
        repeat(50) { assertTrue(result("internal_random", "Number", "1", "6").toInt() in 1..6) }
        assertEquals("5", result("internal_random", "Number", "5", "5"))
        result("internal_random", "Number", Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString())
        assertEquals("400 invalid parameter", error("internal_random", "Number", "6", "1"))
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(result("internal_random", "UUID")))
        val v7 = result("internal_random", "UUID", "--version=7")
        assertTrue(v7, Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(v7))
        assertEquals(env.nowMs, v7.replace("-", "").substring(0, 12).toLong(16))

        assertEquals("ガイド1", result("internal_text", "Normalize", "NFKC", "ｶﾞｲﾄﾞ①"))
        assertEquals("400 unsupported algorithm", error("internal_text", "Normalize", "NFX", "a"))
    }

    // ---- internal_data ----

    @Test
    fun dataConvertAndCompression() {
        assertEquals("SGVsbG8=", result("internal_data", "Convert", "48656c6c6f", "--data-in=hex", "--out=base64"))
        assertEquals(
            "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg",
            result("internal_data", "Convert", "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e", "--data-in=hex", "--out=bech32", "--hrp=npub"),
        )
        assertEquals(
            "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e",
            result("internal_data", "Convert", "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg", "--data-in=bech32", "--out=hex"),
        )
        assertEquals("400 invalid encoding", error("internal_data", "Convert", "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjpth", "--data-in=bech32"))
        assertEquals("400 invalid parameter", error("internal_data", "Convert", "a", "--out=bech32"))
        assertEquals("400 invalid parameter", error("internal_crypto", "Digest", "sha256", "a", "--out=bech32", "--hrp=x"))
        // base64 と base64url の文字はどちらでも、パディングはあってもなくても
        assertEquals("fbff", result("internal_data", "Convert", "-_8", "--data-in=base64", "--out=hex"))
        assertEquals("fbff", result("internal_data", "Convert", "+/8=", "--data-in=base64url", "--out=hex"))
        assertEquals("400 invalid encoding", error("internal_data", "Convert", "+/8==", "--data-in=base64"))
        assertEquals("400 invalid encoding", error("internal_data", "Convert", "abc", "--data-in=hex"))
        assertEquals("foobar", result("internal_data", "Convert", "mzxw6ytboi", "--data-in=base32"))
        assertEquals("MZXW6YTBOI======", result("internal_data", "Convert", "foobar", "--out=base32"))
        assertEquals("a+b c", result("internal_data", "Convert", "a+b%20c", "--data-in=url"))
        assertEquals("a%2Bb%20c~", result("internal_data", "Convert", "a+b c~", "--out=url"))
        assertEquals("400 invalid encoding", error("internal_data", "Convert", "%2", "--data-in=url"))
        assertEquals("500 not text", error("internal_data", "Convert", "ff", "--data-in=hex"))
        assertEquals("500 not text", error("internal_data", "Convert", "610a62", "--data-in=hex"))
        assertEquals("82a0", result("internal_data", "Convert", "あ", "--charset=Shift_JIS", "--out=hex"))
        assertEquals("400 invalid parameter", error("internal_data", "Convert", "a", "--charset=UTF-16"))
        // 対になっていないサロゲート（要求のバイト列には書けないので、直接）
        assertEquals("invalid encoding", runCatching { SaoriCodec.encodeText("\uD83D", Charsets.UTF_8) }.exceptionOrNull()?.message)

        assertEquals("Hello", result("internal_data", "Decompress", "gzip", "H4sIAAAAAAAACvNIzcnJBwCCidH3BQAAAA=="))
        for (m in listOf("gzip", "zlib", "deflate")) {
            val packed = result("internal_data", "Compress", m, "Hello, Hello, Hello", "--level=9")
            assertEquals("Hello, Hello, Hello", result("internal_data", "Decompress", m, packed))
        }
        // gzip は複数のメンバーをつないだものも読む。後ろに余計なデータがあればだめ
        val a = result("internal_data", "Compress", "gzip", "ab", "--out=hex")
        val b = result("internal_data", "Compress", "gzip", "cd", "--out=hex")
        assertEquals("abcd", result("internal_data", "Decompress", "gzip", a + b, "--data-in=hex"))
        assertEquals("400 invalid encoding", error("internal_data", "Decompress", "gzip", a + "00", "--data-in=hex"))
        assertEquals("400 invalid encoding", error("internal_data", "Decompress", "zlib", "AAAA"))
        assertEquals("400 out of range", error("internal_data", "Compress", "zlib", "a", "--level=10"))
        assertEquals("400 unsupported algorithm", error("internal_data", "Compress", "brotli", "a"))
    }

    // ---- internal_time ----

    @Test
    fun timeFormatAddDiff() {
        assertEquals("1970/01/01 09:00 (Thu)", result("internal_time", "Format", "0", "%Y/%m/%d %H:%M (%a)", "--tz=+09:00"))
        assertEquals("1970-01-01T09:00:00+09:00", result("internal_time", "Format", "0"))
        assertEquals("1970-01-01T00:00:00Z", result("internal_time", "Format", "0", "", "--tz=UTC"))
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", result("internal_time", "Format", "0", "http", "--tz=Asia/Tokyo"))
        assertEquals("1970-01-01T09:00:00+09:00", result("internal_time", "Format", "0", "iso8601", "--tz=tokyo standard time"))
        assertEquals("+0900 +09:00 001 4 4 % 0", result("internal_time", "Format", "0", "%z %:z %j %u %w %% %s"))
        assertEquals("400 invalid parameter", error("internal_time", "Format", "0", "%Q"))
        assertEquals("400 invalid parameter", error("internal_time", "Format", "0", "nosuchformat"))
        assertEquals("400 invalid parameter", error("internal_time", "Format", "0", "", "--tz=Mars/Base"))
        // 日時の書き方
        assertEquals("1791720000", result("internal_time", "Format", "2026-10-11T12:00:00Z", "epoch"))
        assertEquals("1791720000", result("internal_time", "Format", "2026-10-11T21:00:00+09:00", "epoch"))
        assertEquals("1791720000", result("internal_time", "Format", "20261011T120000Z", "epoch"))
        assertEquals("1791720000", result("internal_time", "Format", "2026-10-11 21:00", "epoch"))
        assertEquals("1791720000", result("internal_time", "Format", "Sun, 11 Oct 2026 12:00:00 GMT", "epoch"))
        assertEquals("1791720000000", result("internal_time", "Format", "2026-10-11T12:00:00.5Z", "epochms"))
        assertEquals((env.nowMs / 1000).toString(), result("internal_time", "Format", "now", "epoch"))
        assertEquals("400 invalid parameter", error("internal_time", "Format", "2026-02-30", "epoch"))
        assertEquals("400 invalid parameter", error("internal_time", "Format", "1500-01-01T00:00:00Z", "epoch"))
        assertEquals("400 out of range", error("internal_time", "Format", "-62135596801", "epoch", "--tz=UTC"))
        assertEquals("0001-01-01T00:00:00Z", result("internal_time", "Format", "-62135596800", "", "--tz=UTC"))

        // 月末は丸める。日以上はゾーンの暦で
        val jan31 = result("internal_time", "Format", "2026-01-31T10:00", "epoch")
        assertEquals("2026-02-28T10:00:00+09:00", result("internal_time", "Format", result("internal_time", "Add", jan31, "1", "month"), ""))
        assertEquals("2026-01-30T10:00:00+09:00", result("internal_time", "Format", result("internal_time", "Add", jan31, "-1", "days"), ""))
        assertEquals((jan31.toLong() + 90).toString(), result("internal_time", "Add", jan31, "90", "second"))
        assertEquals("400 invalid parameter", error("internal_time", "Add", jan31, "1", "fortnight"))

        assertEquals("3", result("internal_time", "Diff", "2026-12-20T10:00", "2026-12-24", "day"))
        assertEquals("-3", result("internal_time", "Diff", "2026-12-24", "2026-12-20T10:00", "day"))
        assertEquals("1", result("internal_time", "Diff", "2026-01-31T10:00", "2026-02-28T10:00", "month"))
        assertEquals("0", result("internal_time", "Diff", "2026-01-31T10:00", "2026-02-28T09:59", "month"))
        assertEquals("1", result("internal_time", "Diff", "2024-02-29", "2025-02-28", "year"))
        assertEquals("86", result("internal_time", "Diff", "2026-12-20T10:00", "2026-12-24", "hours"))
    }

    // ---- internal_crypto ----

    @Test
    fun digestHmacCompare() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result("internal_crypto", "Digest", "sha256", "abc"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", result("internal_crypto", "Digest", "md5", "abc"))
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", result("internal_crypto", "Digest", "sha3_256", "abc"))
        assertEquals("53048e2681941ef99b2e29b76b4c7dabe4c2d0c634fc6d46e0e2f13107e7af23", result("internal_crypto", "Digest", "sha512-256", "abc"))
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            result("internal_crypto", "Digest", "BLAKE2b512", "abc"),
        )
        assertEquals("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982", result("internal_crypto", "Digest", "blake2s256", "abc"))
        assertEquals("400 unsupported algorithm", error("internal_crypto", "Digest", "sha0", "abc"))
        // 文字コード
        assertEquals(result("internal_crypto", "Digest", "sha1", "82a0", "--data-in=hex"), result("internal_crypto", "Digest", "sha1", "あ", "--charset=Shift_JIS"))
        // ファイルは ghost/master が基準。無ければ file not found、ゴーストなしの相対パスは ghost required
        File(master, "data.txt").writeText("abc")
        assertEquals(result("internal_crypto", "Digest", "sha256", "abc"), result("internal_crypto", "Digest", "sha256", "data.txt", "--data-in=file"))
        assertEquals("400 file not found", error("internal_crypto", "Digest", "sha256", "none.txt", "--data-in=file"))
        assertEquals("ghost required", call("internal_crypto", "Digest", "sha256", "data.txt", "--data-in=file", e = Env(null, null)).error)
        assertEquals("400 invalid parameter", error("internal_data", "Convert", "data.txt", "--data-in=file"))

        // RFC 4231 のテストケース 2
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            result("internal_crypto", "HMAC", "sha256", "Jefe", "what do ya want for nothing?"),
        )
        assertEquals("1", result("internal_crypto", "Compare", "ABCD", "abcd", "--a-in=hex", "--b-in=hex"))
        assertEquals("0", result("internal_crypto", "Compare", "abc", "abcd"))
    }

    @Test
    fun ciphers() {
        val key32 = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val key16 = "000102030405060708090a0b0c0d0e0f"
        for ((alg, key) in listOf(
            "aes-256-gcm" to key32, "aes-128-gcm" to key16, "chacha20-poly1305" to key32, "aes-256-cbc" to key32,
            "aes-128-cbc" to key16, "aes-256-ctr" to key32, "chacha20" to key32,
        )) {
            val ct = result("internal_crypto", "Encrypt", alg, key, "secret message", "--key-in=hex")
            assertNotEquals(ct, result("internal_crypto", "Encrypt", alg, key, "secret message", "--key-in=hex"))
            assertEquals(alg, "secret message", result("internal_crypto", "Decrypt", alg, key, ct, "--key-in=hex"))
        }
        val ct = result("internal_crypto", "Encrypt", "aes-256-gcm", key32, "secret", "--key-in=hex", "--aad=hdr")
        assertEquals("secret", result("internal_crypto", "Decrypt", "AES_256_GCM", key32, ct, "--key-in=hex", "--aad=hdr"))
        assertEquals("500 authentication failed", error("internal_crypto", "Decrypt", "aes-256-gcm", key32, ct, "--key-in=hex", "--aad=other"))
        assertEquals("400 invalid key", error("internal_crypto", "Encrypt", "aes-256-gcm", key16, "x", "--key-in=hex"))
        assertEquals("400 invalid parameter", error("internal_crypto", "Encrypt", "aes-256-cbc", key32, "x", "--key-in=hex", "--aad=a"))
        assertEquals("400 unsupported algorithm", error("internal_crypto", "Encrypt", "des", key32, "x"))
        // RFC 8439 の ChaCha20 のテストベクタ（2.4.2）の nonce と鍵で、--pack=none と --iv=
        val nonce = "000000000000004a00000000"
        val keySeq = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val sunscreen = result(
            "internal_crypto", "Encrypt", "chacha20", keySeq, "Ladies and Gentlemen", "--key-in=hex", "--iv=$nonce", "--pack=none", "--out=hex",
        )
        // カウンタ 0 から（RFC は 1 からなので、1 ブロック目の鍵ストリームの分だけ違う）。往復できることを確かめる
        assertEquals(
            "Ladies and Gentlemen",
            result("internal_crypto", "Decrypt", "chacha20", keySeq, sunscreen, "--key-in=hex", "--iv=$nonce", "--pack=none", "--ciphertext-in=hex"),
        )
        assertEquals("400 invalid parameter", error("internal_crypto", "Decrypt", "chacha20", keySeq, sunscreen, "--key-in=hex", "--pack=none"))
        assertEquals("400 invalid parameter", error("internal_crypto", "Decrypt", "aes-256-gcm", key32, ct, "--key-in=hex", "--iv=$nonce"))
    }

    @Test
    fun deriveKeyAndTotp() {
        assertEquals(
            "4b007901b765489abead49d926f721d065a429c1",
            result("internal_crypto", "DeriveKey", "pbkdf2", "password", "salt", "20", "--digest=sha1", "--iterations=4096"),
        )
        // RFC 5869 のテストケース 1
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            result(
                "internal_crypto", "DeriveKey", "hkdf", "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b", "000102030405060708090a0b0c", "42",
                "--password-in=hex", "--salt-in=hex", "--info=f0f1f2f3f4f5f6f7f8f9", "--info-in=hex",
            ),
        )
        assertEquals(
            "3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1",
            result("internal_crypto", "DeriveKey", "hkdf-extract", "5ad01c0fc57ed5f92a0518b6440ca876eb1f247ddbb237ba1caaabbf4cebbdce", "nip44-v2", "32", "--password-in=hex"),
        )
        assertEquals("400 out of range", error("internal_crypto", "DeriveKey", "hkdf-extract", "aa", "s", "16", "--password-in=hex"))
        assertEquals("400 invalid parameter", error("internal_crypto", "DeriveKey", "hkdf-expand", "aa", "s", "16", "--password-in=hex"))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            result(
                "internal_crypto", "DeriveKey", "hkdf-expand", "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", "", "42",
                "--password-in=hex", "--info=f0f1f2f3f4f5f6f7f8f9", "--info-in=hex",
            ),
        )
        assertEquals("400 invalid parameter", error("internal_crypto", "DeriveKey", "pbkdf2", "p", "s", "16", "--n=2"))
        // RFC 7914 の scrypt のテストベクタ（N=16）
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            result("internal_crypto", "DeriveKey", "scrypt", "", "", "64", "--n=16", "--r=1", "--p=1"),
        )
        assertEquals("400 out of range", error("internal_crypto", "DeriveKey", "scrypt", "p", "s", "16", "--n=1000"))
        assertEquals("400 out of range", error("internal_crypto", "DeriveKey", "scrypt", "p", "s", "16", "--n=16384"))
        assertEquals(64, result("internal_crypto", "DeriveKey", "argon2id", "password", "somesalt", "32", "--memory=64", "--iterations=1").length)
        assertEquals("400 out of range", error("internal_crypto", "DeriveKey", "argon2id", "password", "short", "32"))
        assertEquals("400 out of range", error("internal_crypto", "DeriveKey", "argon2id", "password", "somesalt", "32", "--memory=65536"))

        // RFC 6238 のテストベクタ
        assertEquals("94287082", result("internal_crypto", "TOTP", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "--time=59", "--digits=8"))
        assertEquals("07081804", result("internal_crypto", "TOTP", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "--time=1111111109", "--digits=8"))
        assertEquals(6, result("internal_crypto", "TOTP", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").length)
        assertEquals("400 unsupported algorithm", error("internal_crypto", "TOTP", "GEZDGNBVGY3TQOJQ", "--digest=md5"))
    }

    @Test
    fun passwords() {
        for (m in listOf("argon2id", "scrypt", "pbkdf2-sha256", "bcrypt", "sha512-crypt", "sha256_crypt", "md5crypt", "apr1", "bsdi-crypt", "des-crypt")) {
            val h = result("internal_crypto", "HashPassword", "correct horse", "--method=$m")
            assertEquals(m, "1", result("internal_crypto", "VerifyPassword", "correct horse", h))
            assertEquals(m, "0", result("internal_crypto", "VerifyPassword", "wrong horse", h))
        }
        assertTrue(result("internal_crypto", "HashPassword", "pw").startsWith("\$argon2id\$v=19\$m=12288,t=3,p=1\$"))
        // OpenSSL と Perl（crypt(3)）が作ったもの
        for ((pw, hash) in listOf(
            "Hello world!" to "\$1\$saltsalt\$le8lFSqqnPaRFOlmAZpvH1",
            "Hello world!" to "\$apr1\$saltsalt\$6BwcdpRros16.J9J/tHRr/",
            "Hello world!" to "\$5\$saltstring\$5B8vYYiY.CVt1RlTTf8KbXBH3hsxY/GNooZaBBGWEc5",
            "Hello world!" to "\$6\$saltstring\$svn8UoSVapNtMuq1ukKS4tPQd8iKwSMHWjl/O817G3uBnIFNjnQJuesI68u4OTLiBFdcbYEdFCoEOfaS35inz1",
            "Hello world!" to "\$6\$rounds=10000\$saltstringsaltst\$OW1/O6BYHV6BcXZu8QVeXbDWra3Oeqh0sbHbbMCVNSnCM/UrjmM0Dp8vOuZeHBy/YTBmSK6H9qs/y3RnOaw5v.",
            "This is just a test" to "\$5\$rounds=5000\$toolongsaltstrin\$Un/5jzAHMgOGZ5.mWJpuVolil07guHPvOW8mGRcvxa5",
            "U*U*U*U*" to "CCNf8Sbh3HDfQ",
            "" to "SDbsugeBiC58A",
            "abc" to "abFZSxKKdq5s6",
            "passwordlonger" to "pwYf3wMZWxWvY",
            "U*U*U*U*" to "_J9..CCCCXBrJUJV154M",
            "U*U***U*" to "_J9..CCCC4gQ.mB/PffM",
            "correct horse battery staple" to "_K1..crsmJE.zX77FQjw",
        )) {
            assertEquals("$pw $hash", "1", result("internal_crypto", "VerifyPassword", pw, hash))
        }
        assertEquals("400 unsupported algorithm", error("internal_crypto", "VerifyPassword", "x", "\$2x\$10\$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234"))
        assertEquals("400 unsupported algorithm", error("internal_crypto", "HashPassword", "x", "--method=md4"))
        assertEquals("400 invalid parameter", error("internal_crypto", "HashPassword", "00", "--password-in=hex", "--method=md5-crypt"))
        assertEquals("400 out of range", error("internal_crypto", "VerifyPassword", "x", "\$argon2id\$v=19\$m=65536,t=3,p=4\$c29tZXNhbHQ\$RdescudvJCsgt3ub+b+dWRWJTmaaJObG"))
    }

    @Test
    fun protectAndUnprotect() {
        val wrapped = result("internal_crypto", "Protect", "token")
        assertEquals("token", result("internal_crypto", "Unprotect", wrapped))
        assertEquals("500 authentication failed", error("internal_crypto", "Unprotect", "AAAA"))
        assertEquals("400 invalid parameter", error("internal_crypto", "Protect", "x", "--scope=everyone"))
    }

    @Test
    fun publicKeySignatures() {
        // RFC 8037 の Ed25519 の例
        val edKey = "MC4CAQAwBQYDK2VwBCIEIJ1hsZ3v/VpguoRK9JLsLMREScVpezJpGXA7rAMcrn9g"
        val edData = "eyJhbGciOiJFZERTQSJ9.RXhhbXBsZSBvZiBFZDI1NTE5IHNpZ25pbmc"
        val edSig = "hgyY0il_MGCjP0JzlnLWG1PPOt7-09PGcvMg3AIbQR6dWbhijcNR4ki4iylGjg5BhVsPt9g7sVvpAr_MuM0KAg"
        assertEquals(edSig, result("internal_crypto", "Sign", "ed25519", edKey, edData))
        assertEquals("1", result("internal_crypto", "Verify", "Ed25519", edKey, edData, edSig))
        val pem = "-----BEGIN PRIVATE KEY----- $edKey -----END PRIVATE KEY-----"
        assertEquals("1", result("internal_crypto", "Verify", "ed25519", pem, edData, edSig))
        assertEquals("0", result("internal_crypto", "Verify", "ed25519", edKey, edData + "x", edSig))
        assertEquals("0", result("internal_crypto", "Verify", "ed25519", edKey, edData, "AAAA"))
        assertEquals("400 invalid key", error("internal_crypto", "Sign", "es256", edKey, "x"))
        assertEquals("400 invalid key", error("internal_crypto", "Sign", "ed25519", "not a key", "x"))
        assertEquals("400 invalid parameter", error("internal_crypto", "Sign", "ed25519", edKey, "x", "--key-in=hex"))

        for (m in listOf("ed25519", "es256", "rsa-2048")) {
            val pair = call("internal_crypto", "GenerateKeyPair", m)
            assertEquals(pair.result, pair.values[0])
            for (alg in if (m == "rsa-2048") listOf("rs256", "ps256") else listOf(m)) {
                val sig = result("internal_crypto", "Sign", alg, pair.values[0], "hello")
                assertEquals(alg, "1", result("internal_crypto", "Verify", alg, pair.values[1], "hello", sig))
                assertEquals(alg, "1", result("internal_crypto", "Verify", alg, pair.values[0], "hello", sig))
                assertEquals(alg, "0", result("internal_crypto", "Verify", alg, pair.values[1], "hellO", sig))
            }
        }

        // BIP-340 のテストベクタ 1
        val sk = "b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfef"
        val msg = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89"
        val sig = "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a"
        assertEquals(sig, result("internal_crypto", "Sign", "bip340", sk, msg, "--data-in=hex", "--aux=0000000000000000000000000000000000000000000000000000000000000001"))
        val pk = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659"
        assertEquals("1", result("internal_crypto", "Verify", "bip340", pk, msg, sig, "--data-in=hex"))
        assertEquals("0", result("internal_crypto", "Verify", "bip340", pk, msg, sig.replaceFirst('6', '7'), "--data-in=hex"))
        assertEquals("400 invalid key", error("internal_crypto", "Verify", "bip340", pk + "00", msg, sig, "--data-in=hex"))
        val gen = call("internal_crypto", "GenerateKeyPair", "bip340")
        val s2 = result("internal_crypto", "Sign", "bip340", gen.values[0], "note")
        assertEquals("1", result("internal_crypto", "Verify", "bip340", gen.values[1], "note", s2))
        // nsec / npub をそのまま
        val nsec = result("internal_data", "Convert", gen.values[0], "--data-in=hex", "--out=bech32", "--hrp=nsec")
        val npub = result("internal_data", "Convert", gen.values[1], "--data-in=hex", "--out=bech32", "--hrp=npub")
        val s3 = result("internal_crypto", "Sign", "bip340", nsec, "note", "--key-in=bech32")
        assertEquals("1", result("internal_crypto", "Verify", "bip340", npub, "note", s3, "--key-in=bech32"))

        // NIP-44 の会話鍵のテストベクタ
        assertEquals(
            "5ad01c0fc57ed5f92a0518b6440ca876eb1f247ddbb237ba1caaabbf4cebbdce",
            result("internal_crypto", "KeyAgreement", "secp256k1", "315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268", "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133"),
        )
        assertEquals("400 invalid key", error("internal_crypto", "KeyAgreement", "secp256k1", "00".repeat(32), "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133"))
    }

    // ---- internal_jwt ----

    @Test
    fun jwt() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzYWt1cmEiLCJleHAiOjQxMDI0NDQ4MDB9.eJKcQzLKm4FjTltXWvPqonVpfwhVaBgpvf9nZw0szuM"
        val key = "0123456789abcdef0123456789abcdef"
        assertEquals(token, result("internal_jwt", "Sign", "HS256", key, "{\"sub\":\"sakura\",\"exp\":4102444800}"))
        assertEquals("{\"sub\":\"sakura\",\"exp\":4102444800}", result("internal_jwt", "Verify", token, key, "--alg=hs256"))
        assertEquals("500 expired", error("internal_jwt", "Verify", token, key, "--alg=HS256", "--time=4102444800"))
        assertEquals("{\"sub\":\"sakura\",\"exp\":4102444800}", result("internal_jwt", "Verify", token, key, "--alg=HS256", "--time=4102444799"))
        // HS384 には短い鍵。長い鍵でもヘッダの alg と違えば失敗
        assertEquals("400 invalid key", error("internal_jwt", "Verify", token, key, "--alg=HS384"))
        assertEquals("500 authentication failed", error("internal_jwt", "Verify", token, key + key, "--alg=HS512"))
        assertEquals("500 authentication failed", error("internal_jwt", "Verify", token, key.reversed(), "--alg=HS256"))
        assertEquals("400 invalid parameter", error("internal_jwt", "Verify", token, key))
        assertEquals("400 invalid key", error("internal_jwt", "Sign", "HS256", "short", "{}"))
        assertEquals("400 invalid parameter", error("internal_jwt", "Sign", "HS256", key, "[1]"))
        assertEquals("400 unsupported algorithm", error("internal_jwt", "Sign", "none", key, "{}"))

        val nbf = result("internal_jwt", "Sign", "HS256", key, "{\"nbf\":2000000000}", "--header={\"kid\":\"k1\"}")
        assertEquals("500 not yet valid", error("internal_jwt", "Verify", nbf, key, "--alg=HS256", "--time=1999999999"))
        assertEquals("{\"nbf\":2000000000}", result("internal_jwt", "Verify", nbf, key, "--alg=HS256", "--time=1999999999", "--leeway=1"))
        assertEquals("400 invalid parameter", error("internal_jwt", "Sign", "HS256", key, "{}", "--header={\"alg\":\"HS512\"}"))

        val pair = call("internal_crypto", "GenerateKeyPair", "ed25519")
        val signed = result("internal_jwt", "Sign", "EdDSA", pair.values[0], "{\"a\":1}")
        assertEquals("{\"a\":1}", result("internal_jwt", "Verify", signed, pair.values[1], "--alg=eddsa"))
        // 鍵の種類が alg に合わない
        assertEquals("400 invalid key", error("internal_jwt", "Verify", signed, pair.values[1], "--alg=ES256"))
    }

    // ---- internal_file ----

    @Test
    fun mediaInfoAndArchives() {
        File(master, "song.mp3").writeText("not really")
        env.media = MediaInfo("audio", mapOf("title" to "line1\r\nline2\nx\u0000", "duration" to "215000"))
        val r = call("internal_file", "MediaInfo", "song.mp3", "Title", "nosuch", "duration")
        assertEquals("audio", r.result)
        assertEquals(listOf("line1\u0001line2\u0001x", "", "215000"), r.values)
        env.media = null
        assertEquals(listOf("", ""), call("internal_file", "MediaInfo", "song.mp3", "title", "width").values)
        assertEquals("400 file not found", error("internal_file", "MediaInfo", "none.mp3", "title"))
        assertEquals("400 invalid parameter", error("internal_file", "MediaInfo", "song.mp3"))
        assertEquals("400 invalid parameter", error("internal_file", "MediaInfo", "song.mp3", *Array(12) { "title" }))
        assertEquals(204, call("internal_file", "MediaInfo", "song.mp3", "title", "--async=OnMedia").code)
        assertEquals(listOf("OnMedia", "", "internal_file", "MediaInfo", ""), env.events.last().second)

        File(master, "save/data").mkdirs()
        File(master, "save/data/a.txt").writeText("hello")
        File(master, "save/data/b.txt").writeText("world!")
        val c = call("internal_file", "CompressArchive", "save/backup.zip", "save\\data")
        assertEquals("2", c.result)
        assertEquals("11", c.values[1])
        assertEquals("400 file exists", error("internal_file", "CompressArchive", "save/backup.zip", "save/data"))
        assertEquals(200, call("internal_file", "CompressArchive", "save/backup.zip", "save/data", "--option=overwrite").code)
        assertEquals("500 unknown method", error("internal_file", "CompressArchive", "save/x.zip", "save/data", "--option=lzma"))
        assertEquals("400 file not found", error("internal_file", "CompressArchive", "save/x.zip", "save/none"))
        assertEquals("400 path not allowed", error("internal_file", "CompressArchive", "../../../x.zip", "save/data"))
        val x = call("internal_file", "ExtractArchive", "save/backup.zip", "save/restore")
        assertEquals("2", x.result)
        assertEquals("world!", File(master, "save/restore/b.txt").readText())
        assertEquals("400 file not found", error("internal_file", "ExtractArchive", "save/none.zip", "save/restore"))
        assertEquals(200, call("internal_file", "CompressArchive", "save/secret.zip", "save/data", "--password=pw").code)
        assertEquals("500 password mismatch", error("internal_file", "ExtractArchive", "save/secret.zip", "save/r2"))
        assertEquals("500 password mismatch", error("internal_file", "ExtractArchive", "save/secret.zip", "save/r2", "--password=no"))
        assertEquals(200, call("internal_file", "ExtractArchive", "save/secret.zip", "save/r2", "--password=pw").code)
    }
}
