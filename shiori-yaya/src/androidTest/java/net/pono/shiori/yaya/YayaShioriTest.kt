package net.pono.shiori.yaya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.pono.baseware.shiori.ShioriSession
import net.pono.baseware.text.TextCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

/**
 * NDK でビルドした YAYA を端末上で動かす。テスト用の小さな辞書をその場で書き、
 * load → GET → unload が通ること、文字コード（UTF-8 / Shift_JIS）の変換が往復で正しいことを確かめる。
 */
@RunWith(AndroidJUnit4::class)
class YayaShioriTest {
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "yaya-test")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /**
     * request は Reference0 を取り出して「<Reference0>さん、こんにちは」を返す。
     * 辞書は [charset] で書き、SHIORI のやりとりも同じ文字コードにする。
     */
    private fun writeGhost(name: String, charsetName: String, charset: Charset): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "yaya.txt").writeText("charset, $charsetName\r\ndic, test.dic\r\n", Charsets.US_ASCII)
        val dic = """
            request
            {
            	_crlf = CHR(13) + CHR(10)
            	_ref = ''
            	_lines = SPLIT(_argv[0], _crlf)
            	foreach _lines; _l {
            		if SUBSTR(_l, 0, 12) == 'Reference0: ' {
            			_ref = ERASE(_l, 0, 12)
            		}
            	}
            	'SHIORI/3.0 200 OK' + _crlf + 'Charset: $charsetName' + _crlf + 'Value: \0' + _ref + 'さん、こんにちは' + _crlf + _crlf
            }
        """.trimIndent().replace("\n", "\r\n")
        File(dir, "test.dic").writeBytes(dic.toByteArray(charset))
        return dir
    }

    private fun roundTrip(dir: File, charset: Charset, name: String): String? = runBlocking {
        val session = ShioriSession(YayaShiori(), charset)
        try {
            assertTrue("load", session.load(dir))
            session.get("OnBoot", name)?.value
        } finally {
            session.unload()
        }
    }

    @Test
    fun utf8Dictionary() {
        val dir = writeGhost("utf8", "UTF-8", Charsets.UTF_8)
        assertEquals("\\0ぽの♪さん、こんにちは", roundTrip(dir, Charsets.UTF_8, "ぽの♪"))
    }

    @Test
    fun shiftJisDictionary() {
        // CP932 にしかない文字（①、髙）と半角カナも通す
        val dir = writeGhost("sjis", "Shift_JIS", TextCharsets.CP932)
        assertEquals("\\0ぽの①髙ｶﾅさん、こんにちは", roundTrip(dir, TextCharsets.CP932, "ぽの①髙ｶﾅ"))
    }

    @Test
    fun remoteProcessAndCrashDetection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shiori = RemoteYayaShiori(context)
        val crashed = java.util.concurrent.CountDownLatch(1)
        shiori.setOnCrash { crashed.countDown() }
        val session = ShioriSession(shiori, Charsets.UTF_8)
        try {
            assertTrue(session.load(writeGhost("remote", "UTF-8", Charsets.UTF_8)))
            assertEquals("\\0別プロセスさん、こんにちは", session.get("OnBoot", "別プロセス")?.value)

            // YAYA が落ちたことにする（:yaya のプロセスだけを止める）。テストのプロセスは生き残り、知らせが届く
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val yaya = am.runningAppProcesses.first { it.processName.endsWith(":yaya") }
            assertTrue(yaya.pid != android.os.Process.myPid())
            android.os.Process.killProcess(yaya.pid)
            assertTrue("crash notified", crashed.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // 落ちた後のリクエストは例外にならず、応答なしになる
            assertEquals(null, session.get("OnBoot", "x"))
        } finally {
            session.unload()
        }
    }

    @Test
    fun twoGhostsAtOnce() = runBlocking {
        // multi_load で別々の VM になること（片方を閉じてももう片方は動く）
        val a = ShioriSession(YayaShiori(), Charsets.UTF_8)
        val b = ShioriSession(YayaShiori(), Charsets.UTF_8)
        try {
            assertTrue(a.load(writeGhost("a", "UTF-8", Charsets.UTF_8)))
            assertTrue(b.load(writeGhost("b", "UTF-8", Charsets.UTF_8)))
            a.unload()
            assertEquals("\\0Bさん、こんにちは", b.get("OnBoot", "B")?.value)
        } finally {
            b.unload()
        }
    }
}
