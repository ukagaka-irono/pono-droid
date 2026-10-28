package net.pono.baseware.net

import net.pono.baseware.net.NetTools.ArchiveException
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * zip4j が扱えない ZIP（LZMA で圧縮したもの。方式 14）を書き、読む。--password は WinZip の AES-256（AE-2）で、自分で掛ける。
 * ファイルは流しながら圧縮・展開する（大きなファイルでもメモリに全部は載せない）。
 * ZIP64 は使わない（大きさは [NetTools.MAX_ARCHIVE_BYTES] で 4GB より十分小さい）
 */
internal object ZipArchive {
    private const val STORED = 0
    private const val DEFLATED = 8
    private const val LZMA = 14
    private const val AES = 99

    private const val LOCAL_SIG = 0x04034b50L
    private const val CENTRAL_SIG = 0x02014b50L
    private const val END_SIG = 0x06054b50L
    private const val AES_EXTRA = 0x9901

    /** 暗号（bit 0）・LZMA の終わりの印あり（bit 1）・名前が UTF-8（bit 11） */
    private const val FLAG_ENCRYPTED = 1
    private const val FLAG_LZMA_EOS = 2
    private const val FLAG_UTF8 = 0x800

    /** PBKDF2 の回数と、認証の長さ（WinZip の決まり） */
    private const val AES_ITERATIONS = 1000
    private const val AES_MAC_LENGTH = 10

    /** LZMA の辞書の大きさ。小さいファイルではファイルの大きさまで縮める（携帯でメモリを使いすぎない） */
    private const val MAX_DICT = 1 shl 20
    private const val MIN_DICT = 4096

    /** 展開するときに許す辞書の大きさの上限（細工した書庫で大きなメモリを取らせない） */
    private const val MAX_READ_DICT = 64L shl 20

    /** 中央ディレクトリの1件（[raw] はそのままのバイト列。足すときに書き直さずに使う） */
    private class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressed: Long,
        val uncompressed: Long,
        val offset: Long,
        val extra: ByteArray,
        val raw: ByteArray,
    ) {
        val isDirectory get() = name.endsWith("/")

        /** AES なら中の方式（AES の追加の欄）と鍵の強さ（1〜3）。AES でなければ null */
        val aes: Pair<Int, Int>? by lazy {
            if (method != AES) return@lazy null
            var p = 0
            while (p + 4 <= extra.size) {
                val id = u16(extra, p)
                val size = u16(extra, p + 2)
                if (id == AES_EXTRA && size >= 7 && p + 4 + size <= extra.size) return@lazy u16(extra, p + 9) to (extra[p + 8].toInt() and 0xff)
                p += 4 + size
            }
            null
        }

        /** 実際の圧縮の方式（AES なら中のもの） */
        val actualMethod get() = aes?.first ?: method
    }

    /** [zip] に、zip4j では読めない（LZMA の）ファイルがあるか。ZIP として読めなければ false（zip4j に任せる） */
    fun needsOwnReader(zip: File): Boolean = runCatching { readCentral(zip).any { it.actualMethod == LZMA } }.getOrDefault(false)

    /**
     * [files] を [base] からの相対パスで [zip] に書く（[lzma] なら LZMA、でなければ deflate）。[password] があれば AES-256。
     * [append] なら、今ある [zip] の後ろに足す（前からのファイルはそのまま）
     */
    fun write(files: List<File>, base: File, zip: File, password: String?, append: Boolean, lzma: Boolean) {
        val old = if (append && zip.isFile) readCentral(zip) else emptyList()
        val start = if (append && zip.isFile) centralOffset(zip) else 0L
        if (start == 0L) zip.delete()
        if (old.size + files.size > 0xffff) throw ArchiveException("invalid parameter")
        RandomAccessFile(zip, "rw").use { out ->
            // 前の中央ディレクトリと終わりの記録は消し、後ろに書き直す
            out.setLength(start)
            out.seek(start)
            val central = ByteArrayOutputStream()
            old.forEach { central.write(it.raw) }
            for (f in files) {
                val name = if (base.isDirectory) f.canonicalFile.relativeTo(base).invariantSeparatorsPath else f.name
                central.write(writeEntry(out, f, name, password, lzma))
            }
            val centralStart = out.filePointer
            out.write(central.toByteArray())
            if (out.filePointer > 0xffffffffL) throw ArchiveException("invalid parameter")
            val count = old.size + files.size
            out.write(Bytes().u32(END_SIG).u16(0).u16(0).u16(count).u16(count).u32(central.size().toLong()).u32(centralStart).u16(0).bytes())
        }
    }

    /** 1つのファイルを書き、その中央ディレクトリの1件を返す。大きさと CRC は書き終えてから見出しに書き戻す */
    private fun writeEntry(out: RandomAccessFile, f: File, name: String, password: String?, lzma: Boolean): ByteArray {
        val method = if (lzma) LZMA else DEFLATED
        val flags = FLAG_UTF8 or (if (lzma) FLAG_LZMA_EOS else 0) or (if (password != null) FLAG_ENCRYPTED else 0)
        val extra = if (password != null) aesExtra(method) else ByteArray(0)
        val headerMethod = if (password != null) AES else method
        val version = if (lzma) 63 else if (password != null) 51 else 20
        val (time, date) = dosTime(f.lastModified())
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val offset = out.filePointer
        out.write(Bytes().u32(LOCAL_SIG).u16(version).u16(flags).u16(headerMethod).u16(time).u16(date).u32(0).u32(0).u32(0).u16(nameBytes.size).u16(extra.size).bytes())
        out.write(nameBytes)
        out.write(extra)
        val dataStart = out.filePointer

        val crc = CRC32()
        var size = 0L
        val file = RafOutput(out)
        val sink: OutputStream = if (password != null) AesOutput(file, password) else file
        // 自分で渡した Deflater は閉じても片付かないので、終わりに end する
        val deflater = if (lzma) null else Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val compressor: OutputStream = if (deflater == null) {
            val options = LZMA2Options().apply { dictSize = f.length().coerceIn(MIN_DICT.toLong(), MAX_DICT.toLong()).toInt() }
            // ZIP の LZMA：版（2バイト）・性質の長さ（2バイト、5）・性質（lc/lp/pb と辞書の大きさ）・終わりの印つきの生の LZMA
            sink.write(Bytes().u8(9).u8(20).u16(5).u8((options.pb * 5 + options.lp) * 9 + options.lc).u32(options.dictSize.toLong()).bytes())
            LZMAOutputStream(sink, options, true)
        } else {
            DeflaterOutputStream(sink, deflater, 64 * 1024)
        }
        try {
            compressor.use { c ->
                f.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                        size += n
                        c.write(buf, 0, n)
                    }
                }
            }
        } finally {
            deflater?.end()
        }
        val compressed = out.filePointer - dataStart
        // AE-2 では CRC を書かない（暗号の認証で確かめる）
        val headerCrc = if (password != null) 0L else crc.value
        val end = out.filePointer
        out.seek(offset + 14)
        out.write(Bytes().u32(headerCrc).u32(compressed).u32(size).bytes())
        out.seek(end)
        // 作った側は Unix（3）、属性は無し
        return Bytes().u32(CENTRAL_SIG).u16((3 shl 8) or version).u16(version).u16(flags).u16(headerMethod).u16(time).u16(date)
            .u32(headerCrc).u32(compressed).u32(size).u16(nameBytes.size).u16(extra.size).u16(0).u16(0).u16(0).u32(0).u32(offset)
            .bytes() + nameBytes + extra
    }

    /**
     * [zip] を [root] に展開する（[root] は作ってあること）。LZMA・deflate・無圧縮と、AES（AE-1 / AE-2）を読む。
     * 戻り値は展開したファイルの数と大きさ。フォルダの外を指すファイル名は invalid parameter
     */
    fun extract(zip: File, root: File, password: String?): Pair<Int, Long> {
        val entries = readCentral(zip)
        if (password == null && entries.any { it.flags and FLAG_ENCRYPTED != 0 }) throw ArchiveException("password required")
        var files = 0
        var total = 0L
        RandomAccessFile(zip, "r").use { input ->
            for (e in entries) {
                val target = File(root, e.name.replace('\\', '/')).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) throw ArchiveException("invalid parameter")
                if (e.isDirectory) {
                    target.mkdirs()
                    continue
                }
                if (++files > NetTools.MAX_ARCHIVE_FILES) throw ArchiveException("invalid parameter")
                total += e.uncompressed
                if (total > NetTools.MAX_ARCHIVE_BYTES) throw ArchiveException("invalid parameter")
                target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw ArchiveException("create directory failed") }
                try {
                    readEntry(input, e, password, target)
                } catch (x: Exception) {
                    // 確かめられなかった中身は残さない
                    target.delete()
                    throw if (x is ArchiveException) x else ArchiveException("corrupted")
                }
            }
        }
        return files to total
    }

    // ---- 読む ----

    private fun readEntry(input: RandomAccessFile, e: Entry, password: String?, target: File) {
        input.seek(e.offset)
        val head = ByteArray(30)
        input.readFully(head)
        if (u32(head, 0) != LOCAL_SIG) throw ArchiveException("corrupted")
        val dataStart = e.offset + 30 + u16(head, 26) + u16(head, 28)
        if (dataStart + e.compressed > input.length()) throw ArchiveException("corrupted")
        val aes = e.aes
        var body: InputStream = RafInput(input, dataStart, e.compressed)
        var auth: AesInput? = null
        if (e.flags and FLAG_ENCRYPTED != 0) {
            // AES でない昔の暗号（ZipCrypto）は zip4j が読むので、ここに来るのは方式の分からないもの
            if (aes == null) throw ArchiveException("unknown method")
            auth = AesInput(input, dataStart, e.compressed, password ?: throw ArchiveException("password required"), aes.second)
            body = auth
        }
        // 自分で渡した Inflater は閉じても片付かないので、終わりに end する
        val inflater = if (e.actualMethod == DEFLATED) Inflater(true) else null
        val crc = CRC32()
        var size = 0L
        try {
            val data: InputStream = when (e.actualMethod) {
                STORED -> body
                DEFLATED -> InflaterInputStream(body, inflater, 64 * 1024)
                LZMA -> lzmaInput(body, e.uncompressed, e.flags and FLAG_LZMA_EOS != 0)
                else -> throw ArchiveException("unknown method")
            }
            data.use { stream ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > e.uncompressed) throw ArchiveException("corrupted")
                        crc.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            inflater?.end()
        }
        if (size != e.uncompressed) throw ArchiveException("corrupted")
        auth?.verify()
        // AE-2 は CRC が 0（認証で確かめ済み）。それ以外は CRC を見る
        if ((aes == null || e.crc != 0L) && crc.value != e.crc) throw ArchiveException("crc")
    }

    private fun lzmaInput(body: InputStream, size: Long, endMarker: Boolean): InputStream {
        val head = ByteArray(9)
        if (readFully(body, head) < 9 || u16(head, 2) != 5) throw ArchiveException("corrupted")
        val dict = u32(head, 5)
        if (dict > MAX_READ_DICT) throw ArchiveException("corrupted")
        // 辞書はファイルの大きさより大きくなくてよい
        val dictSize = maxOf(MIN_DICT.toLong(), minOf(dict, size)).toInt()
        return LZMAInputStream(body, if (endMarker) -1 else size, head[4], dictSize)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var p = 0
        while (p < buf.size) {
            val n = input.read(buf, p, buf.size - p)
            if (n < 0) break
            p += n
        }
        return p
    }

    private fun readCentral(zip: File): List<Entry> = RandomAccessFile(zip, "r").use { f ->
        val (count, size, offset) = endRecord(f)
        if (offset + size > f.length()) throw ArchiveException("corrupted")
        val cd = ByteArray(size.toInt())
        f.seek(offset)
        f.readFully(cd)
        var p = 0
        List(count) {
            if (p + 46 > cd.size || u32(cd, p) != CENTRAL_SIG) throw ArchiveException("corrupted")
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val end = p + 46 + nameLen + extraLen + u16(cd, p + 32)
            if (end > cd.size) throw ArchiveException("corrupted")
            val flags = u16(cd, p + 8)
            val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
            val name = String(nameBytes, if (flags and FLAG_UTF8 != 0) Charsets.UTF_8 else charset("windows-31j"))
            Entry(
                name, flags, u16(cd, p + 10), u32(cd, p + 16), u32(cd, p + 20), u32(cd, p + 24), u32(cd, p + 42),
                cd.copyOfRange(p + 46 + nameLen, p + 46 + nameLen + extraLen), cd.copyOfRange(p, end),
            ).also { p = end }
        }
    }

    private fun centralOffset(zip: File): Long = RandomAccessFile(zip, "r").use { endRecord(it).third }

    /** 終わりの記録（件数・中央ディレクトリの大きさ・始まり）。後ろのコメントの分（最大 64KB）だけ探す */
    private fun endRecord(f: RandomAccessFile): Triple<Int, Long, Long> {
        val len = f.length()
        val tail = minOf(len, 22L + 0xffff).toInt()
        if (tail < 22) throw ArchiveException("corrupted")
        val buf = ByteArray(tail)
        f.seek(len - tail)
        f.readFully(buf)
        for (p in tail - 22 downTo 0) {
            if (u32(buf, p) == END_SIG) return Triple(u16(buf, p + 10), u32(buf, p + 12), u32(buf, p + 16))
        }
        throw ArchiveException("corrupted")
    }

    // ---- 流れ ----

    /** 書庫のファイルへそのまま書く（閉じても書庫は閉じない） */
    private class RafOutput(private val out: RandomAccessFile) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    }

    /** 書庫の [start] から [length] バイトだけを読む */
    private class RafInput(private val input: RandomAccessFile, start: Long, length: Long) : InputStream() {
        private var pos = start
        private val end = start + length

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= end) return -1
            input.seek(pos)
            val n = input.read(b, off, minOf(len.toLong(), end - pos).toInt())
            if (n > 0) pos += n
            return n
        }
    }

    // ---- AES（WinZip AE-2。書くときの鍵は 256 ビット） ----

    private fun aesExtra(method: Int): ByteArray = Bytes().u16(AES_EXTRA).u16(7).u16(2).u8('A'.code).u8('E'.code).u8(3).u16(method).bytes()

    private fun keyLength(strength: Int) = when (strength) {
        1 -> 16
        2 -> 24
        3 -> 32
        else -> throw ArchiveException("unknown method")
    }

    /** 鍵・認証の鍵・パスワードの確かめ（2バイト）を、パスワードと塩から作る */
    private class AesKeys(password: String, salt: ByteArray, keyLen: Int) {
        private val all = pbkdf2(password.toByteArray(Charsets.UTF_8), salt, keyLen * 2 + 2)
        val key: ByteArray = all.copyOfRange(0, keyLen)
        val macKey: ByteArray = all.copyOfRange(keyLen, keyLen * 2)
        val verifier: ByteArray = all.copyOfRange(keyLen * 2, keyLen * 2 + 2)
    }

    /** WinZip の AES-CTR。数えるのは 1 から、リトルエンディアン（Java の CTR とは向きが違うので自分で回す） */
    private class Ctr(key: ByteArray) {
        private val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        private val counter = ByteArray(16)
        private val stream = ByteArray(16)
        private var n = 0L
        private var used = 16

        fun apply(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) {
                if (used == 16) {
                    n++
                    for (k in 0 until 8) counter[k] = (n ushr (8 * k)).toByte()
                    aes.doFinal(counter, 0, 16, stream, 0)
                    used = 0
                }
                b[i] = (b[i].toInt() xor stream[used++].toInt()).toByte()
            }
        }
    }

    /** 塩とパスワードの確かめを先に書き、暗号にして流す。閉じるときに認証（HMAC-SHA1 の先頭 10 バイト）を書く */
    private class AesOutput(out: OutputStream, password: String) : FilterOutputStream(out) {
        private val keyLen = keyLength(3)
        private val ctr: Ctr
        private val mac = Mac.getInstance("HmacSHA1")

        init {
            val salt = ByteArray(keyLen / 2).also { SecureRandom().nextBytes(it) }
            val keys = AesKeys(password, salt, keyLen)
            ctr = Ctr(keys.key)
            mac.init(SecretKeySpec(keys.macKey, "HmacSHA1"))
            out.write(salt)
            out.write(keys.verifier)
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val chunk = b.copyOfRange(off, off + len)
            ctr.apply(chunk, 0, len)
            mac.update(chunk)
            out.write(chunk)
        }

        override fun close() {
            out.write(mac.doFinal().copyOf(AES_MAC_LENGTH))
            out.flush()
        }
    }

    /** 暗号を解きながら読む。読み終えたら [verify] で認証を確かめる */
    private class AesInput(input: RandomAccessFile, start: Long, length: Long, password: String, strength: Int) : InputStream() {
        private val body: RafInput
        private val ctr: Ctr
        private val mac = Mac.getInstance("HmacSHA1")
        private val stored = ByteArray(AES_MAC_LENGTH)

        init {
            val keyLen = keyLength(strength)
            val saltLen = keyLen / 2
            val cipherLen = length - saltLen - 2 - AES_MAC_LENGTH
            if (cipherLen < 0) throw ArchiveException("corrupted")
            val head = ByteArray(saltLen + 2)
            input.seek(start)
            input.readFully(head)
            val keys = AesKeys(password, head.copyOfRange(0, saltLen), keyLen)
            if (!MessageDigest.isEqual(keys.verifier, head.copyOfRange(saltLen, saltLen + 2))) throw ArchiveException("password mismatch")
            input.seek(start + length - AES_MAC_LENGTH)
            input.readFully(stored)
            ctr = Ctr(keys.key)
            mac.init(SecretKeySpec(keys.macKey, "HmacSHA1"))
            body = RafInput(input, start + saltLen + 2, cipherLen)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = body.read(b, off, len)
            if (n > 0) {
                mac.update(b, off, n)
                ctr.apply(b, off, n)
            }
            return n
        }

        /** 残りも読んで認証を確かめる。合わなければ crc */
        fun verify() {
            val buf = ByteArray(64 * 1024)
            while (body.read(buf, 0, buf.size).also { if (it > 0) mac.update(buf, 0, it) } >= 0) Unit
            if (!MessageDigest.isEqual(mac.doFinal().copyOf(AES_MAC_LENGTH), stored)) throw ArchiveException("crc")
        }
    }

    /** PBKDF2-HMAC-SHA1（パスワードは UTF-8。端末ごとの JCA の違いを避けて自分で計算する） */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(password, "HmacSHA1")) }
        val out = ByteArrayOutputStream()
        var block = 1
        while (out.size() < length) {
            mac.update(salt)
            var u = mac.doFinal(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            repeat(AES_ITERATIONS - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            out.write(t)
            block++
        }
        return out.toByteArray().copyOf(length)
    }

    // ---- バイト列 ----

    private fun dosTime(millis: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val year = c.get(Calendar.YEAR).coerceIn(1980, 2107)
        val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        val date = ((year - 1980) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        return time to date
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xff) shl (8 * i)) }

    /** リトルエンディアンで並べる */
    private class Bytes {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xff) }
        fun u16(v: Int) = apply { u8(v).u8(v ushr 8) }
        fun u32(v: Long) = apply { for (i in 0 until 4) out.write((v ushr (8 * i)).toInt() and 0xff) }
        fun bytes(): ByteArray = out.toByteArray()
    }
}
