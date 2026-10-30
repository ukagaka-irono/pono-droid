package net.pono.baseware.saori

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.SecureRandom

class BugSweepSaoriTest {
    private val env = object : SaoriEnv {
        override val ghostMaster: File? = null
        override val ghostRoot: File? = null
        override val random = SecureRandom()
        override fun protect(data: ByteArray) = data
        override fun unprotect(data: ByteArray) = data
        override fun async(work: () -> Pair<String, List<String>>) {
            work()
        }
    }
    private val saori = InternalSaori(env)

    private fun call(module: String, vararg args: String): SaoriResponse {
        val sb = StringBuilder("EXECUTE SAORI/1.0\r\nCharset: UTF-8\r\nSender: test\r\n")
        args.forEachIndexed { i, a -> sb.append("Argument$i: $a\r\n") }
        sb.append("\r\n")
        return saori.answer(SaoriRequest.parse(sb.toString().toByteArray())!!, module)
    }

    @Test
    fun a4_perlStyleBracedGroupInReplacement() {
        val r = call("string", "replace", "abc", "s/(b)/[\${1}]/")
        assertEquals(200 to "1", r.code to r.result)
        assertEquals("a[b]c", call("string", "get", "0").result)
    }

    @Test
    fun a5_deeplyNestedJwtPayloadIsAnError() {
        val r = call("internal_jwt", "Sign", "HS256", "k".repeat(32), "[".repeat(200_000))
        assertEquals(400, r.code)
    }

    @Test
    fun a9_scryptCostTooHighForR1IsOutOfRange() {
        val r = call("internal_crypto", "DeriveKey", "scrypt", "p", "s", "16", "--n=65536", "--r=1", "--p=1")
        assertEquals("400 out of range", "${r.code} ${r.error}")
    }
}
