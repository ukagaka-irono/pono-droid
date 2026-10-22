package net.pono.shiori.yaya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.pono.baseware.shiori.ShioriSession
import net.pono.baseware.shiori.ShioriTimeoutException
import net.pono.baseware.text.TextCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * NDK でビルドした YAYA を端末上で動かす。テスト用の小さな辞書をその場で書き、
 * load → GET → unload が通ること、文字コード（UTF-8 / Shift_JIS）の変換が往復で正しいこと、
 * 辞書にできること（ファイル・ネイティブコード・プロセス）が絞られていることを確かめる。
 */
@RunWith(AndroidJUnit4::class)
class YayaShioriTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "yaya-test")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun sandbox(dir: File) = YayaShiori.Sandbox(dir, context.dataDir)

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

    /**
     * Reference0 の指示（fopen-w / fopen-r / loadlib / execute / requestlib / hang）を Reference1 のパスに対して行い、
     * 結果を Value で返す辞書。悪意のあるゴーストのつもり
     */
    private fun writeHostileGhost(name: String): File {
        val dir = File(root, name).apply { mkdirs() }
        // looplimit を 0 にすると無限ループが止まらなくなる（辞書側で決められる）
        File(dir, "yaya.txt").writeText("charset, UTF-8\r\ndic, test.dic\r\nlooplimit, 0\r\n", Charsets.US_ASCII)
        val dic = """
            request
            {
            	_crlf = CHR(13) + CHR(10)
            	_cmd = ''
            	_path = ''
            	_lines = SPLIT(_argv[0], _crlf)
            	foreach _lines; _l {
            		if SUBSTR(_l, 0, 12) == 'Reference0: ' {
            			_cmd = ERASE(_l, 0, 12)
            		}
            		if SUBSTR(_l, 0, 12) == 'Reference1: ' {
            			_path = ERASE(_l, 0, 12)
            		}
            	}
            	_r = ''
            	if _cmd == 'fopen-w' {
            		_r = TOSTR(FOPEN(_path, 'w'))
            		FCLOSE(_path)
            	}
            	elseif _cmd == 'fopen-r' {
            		_r = TOSTR(FOPEN(_path, 'r'))
            		FCLOSE(_path)
            	}
            	elseif _cmd == 'loadlib' {
            		_r = TOSTR(LOADLIB(_path))
            	}
            	elseif _cmd == 'execute' {
            		_r = TOSTR(EXECUTE('/system/bin/sh', '-c "touch ' + _path + '"'))
            	}
            	elseif _cmd == 'requestlib' {
            		LOADLIB('/system/bin/sh')
            		_r = REQUESTLIB('/system/bin/sh', 'EXECUTE SAORI/1.0' + _crlf + 'SecurityLevel: local' + _crlf + 'Argument0: -c' + _crlf + 'Argument1: touch ' + _path + _crlf + _crlf)
            	}
            	elseif _cmd == 'hang' {
            		while 1 {
            			_r = 'x'
            		}
            	}
            	'SHIORI/3.0 200 OK' + _crlf + 'Charset: UTF-8' + _crlf + 'Value: ' + _r + _crlf + _crlf
            }
        """.trimIndent().replace("\n", "\r\n")
        File(dir, "test.dic").writeText(dic, Charsets.UTF_8)
        return dir
    }

    private fun roundTrip(dir: File, charset: Charset, name: String): String? = runBlocking {
        val session = ShioriSession(YayaShiori(sandbox(dir)), charset)
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
        val shiori = RemoteYayaShiori(context)
        val crashed = CountDownLatch(1)
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
            assertTrue("crash notified", crashed.await(10, TimeUnit.SECONDS))
            // 落ちた後のリクエストは例外にならず、応答なしになる
            assertEquals(null, session.get("OnBoot", "x"))
        } finally {
            session.unload()
        }
    }

    @Test
    fun hungDictionaryIsKilledAfterTheTimeLimit() = runBlocking {
        val shiori = RemoteYayaShiori(context)
        val crashed = CountDownLatch(1)
        shiori.setOnCrash { crashed.countDown() }
        val session = ShioriSession(shiori, Charsets.UTF_8, requestTimeoutMs = 3000)
        try {
            assertTrue(session.load(writeHostileGhost("hang")))
            val error = runCatching { session.get("OnTest", "hang", "") }.exceptionOrNull()
            assertTrue("$error", error is ShioriTimeoutException)
            // プロセスが止められ、落ちた扱いになる
            assertTrue("crash notified", crashed.await(10, TimeUnit.SECONDS))
        } finally {
            session.unload()
        }
    }

    @Test
    fun sandboxConfinesFilesAndBlocksNativeCode() = runBlocking {
        val dir = writeHostileGhost("sandbox")
        val secret = File(context.filesDir, "yaya-test-secret.txt").apply { writeText("secret") }
        val session = ShioriSession(YayaShiori(sandbox(dir)), Charsets.UTF_8)
        suspend fun ask(cmd: String, path: String) = session.get("OnTest", cmd, path)?.value
        try {
            assertTrue(session.load(dir))

            // 自分のディレクトリの中は読み書きできる（相対パスは ghost/master 基準）
            assertEquals("1", ask("fopen-w", "inside.txt"))
            assertTrue(File(dir, "inside.txt").isFile)
            // アプリの領域には書けないし、読めもしない
            val evil = File(context.filesDir, "yaya-test-evil.txt")
            assertEquals("0", ask("fopen-w", evil.path))
            assertFalse(evil.exists())
            assertEquals("0", ask("fopen-r", secret.path))
            // システムのファイルは読むだけなら許す
            assertEquals("1", ask("fopen-r", "/system/etc/hosts"))

            // ネイティブコードは読み込めない（本物の .so でも）
            assertEquals("0", ask("loadlib", File(context.applicationInfo.nativeLibraryDir, "libyaya.so").path))
            // シェルも動かせない（EXECUTE と SAORI-basic の両方）
            val marker = File(dir, "marker.txt")
            ask("execute", marker.path)
            ask("requestlib", marker.path)
            Thread.sleep(500)
            assertFalse(marker.exists())
        } finally {
            session.unload()
            secret.delete()
        }
    }

    @Test
    fun twoGhostsAtOnce() = runBlocking {
        // multi_load で別々の VM になること（片方を閉じてももう片方は動く）
        val dirA = writeGhost("a", "UTF-8", Charsets.UTF_8)
        val dirB = writeGhost("b", "UTF-8", Charsets.UTF_8)
        val a = ShioriSession(YayaShiori(sandbox(dirA)), Charsets.UTF_8)
        val b = ShioriSession(YayaShiori(sandbox(dirB)), Charsets.UTF_8)
        try {
            assertTrue(a.load(dirA))
            assertTrue(b.load(dirB))
            a.unload()
            assertEquals("\\0Bさん、こんにちは", b.get("OnBoot", "B")?.value)
        } finally {
            b.unload()
        }
    }
}
