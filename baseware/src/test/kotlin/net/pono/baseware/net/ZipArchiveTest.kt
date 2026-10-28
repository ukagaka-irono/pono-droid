package net.pono.baseware.net

import net.lingala.zip4j.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/** LZMA の ZIP（zip4j では扱えないので自分で書いて読む）と、その AES */
class ZipArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val text = (1..2000).joinToString("") { "ぽの LZMA のテスト $it\n" }.toByteArray()
    private val noise = Random(7).nextBytes(70_000)

    private fun source(): File = tmp.newFolder("src").also { d ->
        File(d, "a.txt").writeBytes(text)
        File(d, "sub").mkdirs()
        File(d, "sub/noise.bin").writeBytes(noise)
        File(d, "empty.txt").writeBytes(ByteArray(0))
    }

    private fun assertSame(dir: File) {
        assertArrayEquals(text, File(dir, "a.txt").readBytes())
        assertArrayEquals(noise, File(dir, "sub/noise.bin").readBytes())
        assertEquals(0L, File(dir, "empty.txt").length())
    }

    private fun failure(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? NetTools.ArchiveException)?.code

    @Test
    fun lzmaRoundTrip() {
        val zip = File(tmp.root, "out.zip")
        val r = NetTools.compress(source(), zip, lzma = true)
        assertEquals(3, r.files)
        assertEquals(zip.length(), r.compressed)
        assertEquals((text.size + noise.size).toLong(), r.uncompressed)
        // 繰り返しの多い文は小さくなる
        assertTrue(zip.length() < text.size / 4 + noise.size + 2000)
        assertTrue(ZipArchive.needsOwnReader(zip))
        val x = NetTools.extract(zip, File(tmp.root, "x"))
        assertEquals(3, x.files)
        assertSame(File(tmp.root, "x"))
    }

    @Test
    fun lzmaWithPassword() {
        val zip = File(tmp.root, "secret.zip")
        NetTools.compress(source(), zip, password = "ぱすわーど", lzma = true)
        assertEquals("password required", failure { NetTools.extract(zip, File(tmp.root, "x")) })
        assertEquals("password mismatch", failure { NetTools.extract(zip, File(tmp.root, "x"), "違う") })
        NetTools.extract(zip, File(tmp.root, "ok"), "ぱすわーど")
        assertSame(File(tmp.root, "ok"))

        // 暗号文を1バイト変えると、認証で気付いて中身を残さない
        val damaged = File(tmp.root, "damaged.zip").also { zip.copyTo(it) }
        RandomAccessFile(damaged, "rw").use { f ->
            f.seek(200)
            val b = f.read()
            f.seek(200)
            f.write(b xor 0x55)
        }
        val code = failure { NetTools.extract(damaged, File(tmp.root, "bad"), "ぱすわーど") }
        assertTrue(code, code == "crc" || code == "corrupted")
    }

    @Test
    fun appendsToZip4jArchivesBothWays() {
        val src = source()
        // zip4j で作った AES の書庫に LZMA で足し、全部を自分で読む（zip4j の AES も読める）
        val zip = File(tmp.root, "mixed.zip")
        NetTools.compress(File(src, "a.txt"), zip, password = "pw")
        val added = NetTools.compress(File(src, "sub"), zip, password = "pw", append = true, lzma = true)
        assertEquals(1, added.files)
        val x = File(tmp.root, "x")
        assertEquals(2, NetTools.extract(zip, x, "pw").files)
        assertArrayEquals(text, File(x, "a.txt").readBytes())
        assertArrayEquals(noise, File(x, "noise.bin").readBytes())

        // LZMA の入った書庫に、lzma なしで足す（zip4j は読めないので自分で deflate で書く）
        val plain = File(tmp.root, "plain.zip")
        NetTools.compress(File(src, "sub"), plain, lzma = true)
        NetTools.compress(File(src, "a.txt"), plain, append = true)
        val y = File(tmp.root, "y")
        assertEquals(2, NetTools.extract(plain, y).files)
        assertArrayEquals(text, File(y, "a.txt").readBytes())
    }

    @Test
    fun deflateEntriesAreReadableByZip4j() {
        // 自分で書いた deflate と AES は zip4j でも読める（書き方が正しいことの確かめ）
        val src = source()
        val zip = File(tmp.root, "lz.zip")
        NetTools.compress(File(src, "sub"), zip, lzma = true)
        File(tmp.root, "plain.zip").let { plain ->
            NetTools.compress(File(src, "a.txt"), plain)
            NetTools.compress(File(src, "empty.txt"), plain, append = true)
            assertFalse(ZipArchive.needsOwnReader(plain))
        }
        val own = File(tmp.root, "own.zip")
        ZipArchive.write(listOf(File(src, "a.txt")), File(src, "a.txt"), own, "pw", append = false, lzma = false)
        val out = File(tmp.root, "z4")
        ZipFile(own, "pw".toCharArray()).use { it.extractAll(out.path) }
        assertArrayEquals(text, File(out, "a.txt").readBytes())
    }

    @Test
    fun namesOutsideTheFolderAreRefused() {
        val src = tmp.newFolder("evil")
        File(src, "a.txt").writeText("x")
        val zip = File(tmp.root, "evil.zip")
        NetTools.compress(src, zip, lzma = true)
        // 見出しと中央ディレクトリの名前 a.txt を、同じ長さの ../ab に書き換える
        val bytes = zip.readBytes()
        val name = "a.txt".toByteArray()
        val evil = "../ab".toByteArray()
        for (i in 0..bytes.size - name.size) {
            if (bytes.copyOfRange(i, i + name.size).contentEquals(name)) evil.copyInto(bytes, i)
        }
        zip.writeBytes(bytes)
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        assertEquals("invalid parameter", failure { NetTools.extract(zip, dest) })
    }
}
