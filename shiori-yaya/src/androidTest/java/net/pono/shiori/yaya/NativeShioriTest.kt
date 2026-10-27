package net.pono.shiori.yaya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.pono.baseware.saori.InternalSaori
import net.pono.baseware.saori.SaoriEnv
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
class NativeShioriTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "yaya-test")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun sandbox(dir: File) = NativeShiori.Sandbox(dir, context.dataDir)

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
        val session = ShioriSession(NativeShiori(ShioriEngine.YAYA, sandbox(dir)), charset)
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
        val shiori = RemoteShiori(context, ShioriEngine.YAYA)
        val crashed = CountDownLatch(1)
        shiori.setOnCrash { crashed.countDown() }
        val session = ShioriSession(shiori, Charsets.UTF_8)
        try {
            assertTrue(session.load(writeGhost("remote", "UTF-8", Charsets.UTF_8)))
            assertEquals("\\0別プロセスさん、こんにちは", session.get("OnBoot", "別プロセス")?.value)

            // YAYA が落ちたことにする（:shiori のプロセスだけを止める）。テストのプロセスは生き残り、知らせが届く
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val yaya = am.runningAppProcesses.first { it.processName.endsWith(":shiori") }
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
        val shiori = RemoteShiori(context, ShioriEngine.YAYA)
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
        val session = ShioriSession(NativeShiori(ShioriEngine.YAYA, sandbox(dir)), Charsets.UTF_8)
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

    private fun request(engine: ShioriEngine, dir: File, charset: Charset, vararg refs: String): String? = runBlocking {
        val session = ShioriSession(NativeShiori(engine, sandbox(dir)), charset)
        try {
            assertTrue("load $engine", session.load(dir))
            session.get("OnBoot", *refs)?.value
        } finally {
            session.unload()
        }
    }

    /** 内蔵 SAORI（SAORI over DirectSSTP）。辞書が internal_* を呼ぶと、setSaoriHandler の先に要求が届いて答えが戻る */
    @Test
    fun internalSaoriFromDictionaries() {
        val modules = java.util.Collections.synchronizedList(mutableListOf<String>())
        val saori = InternalSaori(object : SaoriEnv {
            override val ghostMaster: File? = null
            override val ghostRoot: File? = null
            override val random = java.security.SecureRandom()
            override fun protect(data: ByteArray) = data
            override fun unprotect(data: ByteArray) = data
            override fun async(work: () -> Pair<String, List<String>>) {}
        })
        fun ask(engine: ShioriEngine, dir: File, charset: Charset): String? = runBlocking {
            val shiori = NativeShiori(engine, sandbox(dir))
            shiori.setSaoriHandler { req, module -> modules += module ?: "(header)"; saori.handle(req, module) }
            val session = ShioriSession(shiori, charset)
            try {
                assertTrue("load $engine", session.load(dir))
                session.get("OnBoot")?.value
            } finally {
                session.unload()
            }
        }
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        // YAYA: システム辞書の FUNCTIONEX が呼ぶ LOADLIB / REQUESTLIB。拡張子なしでも .dll 付きでも
        val yaya = File(root, "yaya-saori").apply { mkdirs() }
        File(yaya, "yaya.txt").writeText("charset, UTF-8\r\ndic, test.dic\r\n", Charsets.US_ASCII)
        val dic = """
            request
            {
            	_crlf = CHR(13) + CHR(10)
            	_head = 'EXECUTE SAORI/1.0' + _crlf + 'Charset: UTF-8' + _crlf + 'Sender: test' + _crlf + 'SecurityLevel: Local' + _crlf
            	LOADLIB('internal_crypto')
            	LOADLIB('internal_system.dll')
            	_h = saoriresult(REQUESTLIB('internal_crypto', _head + 'Argument0: Digest' + _crlf + 'Argument1: sha256' + _crlf + 'Argument2: abc' + _crlf + _crlf))
            	_l = saoriresult(REQUESTLIB('internal_system.dll', _head + 'Argument0: ListFunctions' + _crlf + 'Argument1: internal_jwt' + _crlf + _crlf))
            	'SHIORI/3.0 200 OK' + _crlf + 'Charset: UTF-8' + _crlf + 'Value: ' + _h + '|' + _l + _crlf + _crlf
            }

            saoriresult
            {
            	_r = ''
            	_lines = SPLIT(_argv[0], CHR(13) + CHR(10))
            	foreach _lines; _x {
            		if SUBSTR(_x, 0, 8) == 'Result: ' {
            			_r = ERASE(_x, 0, 8)
            		}
            	}
            	_r
            }
        """.trimIndent().replace("\n", "\r\n")
        File(yaya, "test.dic").writeText(dic, Charsets.UTF_8)
        assertEquals("$sha|Sign,Verify", ask(ShioriEngine.YAYA, yaya, Charsets.UTF_8))
        assertTrue("$modules", "internal_crypto" in modules && "internal_system" in modules)

        // 里々: ＠SAORI に書かずに（internal_…）で呼べる
        val satori = File(root, "satori-saori").apply { mkdirs() }
        File(satori, "dic_test.txt").writeBytes("＊OnBoot\r\n：（internal_crypto、Digest、sha256、abc）\r\n".toByteArray(TextCharsets.CP932))
        val value = ask(ShioriEngine.SATORI, satori, TextCharsets.CP932)
        assertTrue("$value", value?.contains(sha) == true)
    }

    @Test
    fun satoriDictionary() {
        // 里々の辞書は Shift_JIS。＊の行がイベント名、：の行が話す文
        val dir = File(root, "satori").apply { mkdirs() }
        File(dir, "dic_test.txt").writeBytes("＊OnBoot\r\n：里々です①\r\n".toByteArray(TextCharsets.CP932))
        val value = request(ShioriEngine.SATORI, dir, TextCharsets.CP932)
        assertTrue("$value", value?.contains("里々です①") == true)
    }

    @Test
    fun kawariDictionary() {
        // 華和梨は kawarirc.kis を読み、GET のたびに System.Callback.OnGET を返す
        val dir = File(root, "kawari").apply { mkdirs() }
        File(dir, "kawarirc.kis").writeBytes(
            "System.Callback.OnGET : \\0\${System.Request.Reference0}さん、華和梨です\r\n".toByteArray(TextCharsets.CP932),
        )
        val value = request(ShioriEngine.KAWARI, dir, TextCharsets.CP932, "ぽの")
        assertEquals("\\0ぽのさん、華和梨です", value)
    }

    @Test
    fun kawariSurvivesErrorsInLoadedDictionaries() {
        // 読み込んだ辞書の中で知らないコマンドを使うと、華和梨はエラーを記録するために文を文字列に戻す。
        // 64bit では string::npos の扱いの不具合でここで落ちていた（ビルド時に直している）
        val dir = File(root, "kawari-errors").apply { mkdirs() }
        File(dir, "kawarirc.kis").writeBytes(
            ("=kis\r\n\tload sub.kis;\r\n=end\r\nSystem.Callback.OnGET : \\0ok\r\n").toByteArray(TextCharsets.CP932),
        )
        File(dir, "sub.kis").writeBytes(
            ("=kis\r\n\tnosuchcommand \"a\\\"b\" \"c\";\r\n\tset x \"y\";\r\n=end\r\n").toByteArray(TextCharsets.CP932),
        )
        assertEquals("\\0ok", request(ShioriEngine.KAWARI, dir, TextCharsets.CP932))
    }

    private fun writeLuaGhost(name: String, body: String): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "index.lua").writeText(
            """
            local M = {}
            function M.load(path) return true end
            function M.unload() return true end
            function M.request(req)
              local ref = req:match("Reference0: ([^\r\n]*)") or ""
              local value = (function() $body end)()
              return "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\nValue: " .. value .. "\r\n\r\n"
            end
            return M
            """.trimIndent(),
            Charsets.UTF_8,
        )
        return dir
    }

    @Test
    fun kagariDictionary() {
        val dir = writeLuaGhost("kagari", """return "\\0" .. ref .. "さん、Kagari です"""")
        assertEquals("\\0ぽのさん、Kagari です", request(ShioriEngine.KAGARI, dir, Charsets.UTF_8, "ぽの"))
    }

    @Test
    fun kagariSandboxBlocksFilesShellAndNativeModules() {
        val secret = File(context.filesDir, "kagari-secret.txt").apply { writeText("secret") }
        val marker = File(root, "kagari-sandbox/marker.txt")
        // アプリの領域を読む・シェルを動かす・C モジュールを読む、を順に試し、結果を返す
        val dir = writeLuaGhost(
            "kagari-sandbox",
            """
            local r = {}
            r[#r+1] = io.open("${secret.path}", "r") and "read" or "noread"
            local w = io.open("inside.txt", "w")
            r[#r+1] = w and "write" or "nowrite"
            if w then w:close() end
            os.execute("touch ${marker.path}")
            local f = io.popen("id")
            r[#r+1] = f and "popen" or "nopopen"
            r[#r+1] = package.loadlib("${File(context.applicationInfo.nativeLibraryDir, "libyaya.so").path}", "luaopen_x") and "loadlib" or "noloadlib"
            return table.concat(r, ",")
            """.trimIndent(),
        )
        try {
            // 相対パスは ghost/master 基準ではなく作業ディレクトリ基準なので、書き込み先は書かずに結果だけ見る
            val value = request(ShioriEngine.KAGARI, dir, Charsets.UTF_8)
            assertTrue("$value", value?.startsWith("noread,") == true)
            assertTrue("$value", value?.endsWith(",nopopen,noloadlib") == true)
            Thread.sleep(300)
            assertFalse(marker.exists())
        } finally {
            secret.delete()
        }
    }

    @Test
    fun enginesAreChosenFromDescriptAndFiles() {
        val dir = File(root, "detect").apply { mkdirs() }
        assertEquals(ShioriEngine.YAYA, ShioriEngine.forGhost("yaya.dll", dir))
        assertEquals(ShioriEngine.SATORI, ShioriEngine.forGhost("Satori.dll", dir))
        assertEquals(ShioriEngine.KAGARI, ShioriEngine.forGhost("kagari.dll", dir))
        assertEquals(null, ShioriEngine.forGhost("shiori.dll", dir))
        // shiori.dll とだけ書いてあるゴーストは、辞書のファイルで見分ける
        File(dir, "kawarirc.kis").writeText("")
        assertEquals(ShioriEngine.KAWARI, ShioriEngine.forGhost("shiori.dll", dir))
    }

    @Test
    fun twoGhostsAtOnce() = runBlocking {
        // multi_load で別々の VM になること（片方を閉じてももう片方は動く）
        val dirA = writeGhost("a", "UTF-8", Charsets.UTF_8)
        val dirB = writeGhost("b", "UTF-8", Charsets.UTF_8)
        val a = ShioriSession(NativeShiori(ShioriEngine.YAYA, sandbox(dirA)), Charsets.UTF_8)
        val b = ShioriSession(NativeShiori(ShioriEngine.YAYA, sandbox(dirB)), Charsets.UTF_8)
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
