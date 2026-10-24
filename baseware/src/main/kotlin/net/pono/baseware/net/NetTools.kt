package net.pono.baseware.net

import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** \![execute,ping] / \![execute,nslookup] / \7（SNTP）など、通信の小さな道具 */
object NetTools {
    /** SNTP の結果。[offsetMs] はサーバーの時刻 - 端末の時刻（正なら端末が遅れている） */
    data class SntpResult(val serverTime: Long, val localTime: Long, val offsetMs: Long)

    /** SNTP（RFC 4330）でサーバーの時刻を聞く。端末の時計は直さない（Android のアプリには直せない） */
    fun sntp(server: String, timeoutMs: Int = 5_000, port: Int = 123): SntpResult {
        val address = InetAddress.getByName(server)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val buf = ByteArray(48)
            // LI = 0, VN = 3, Mode = 3（クライアント）
            buf[0] = 0x1B
            val sent = System.currentTimeMillis()
            writeTimestamp(buf, 40, sent)
            socket.send(DatagramPacket(buf, buf.size, address, port))
            val reply = DatagramPacket(ByteArray(48), 48)
            socket.receive(reply)
            val received = System.currentTimeMillis()
            val data = reply.data
            val receiveTime = readTimestamp(data, 32)
            val transmitTime = readTimestamp(data, 40)
            if (transmitTime == 0L) throw IOException("invalid reply")
            // 行き帰りの時間を差し引いたずれ
            val offset = ((receiveTime - sent) + (transmitTime - received)) / 2
            return SntpResult(received + offset, received, offset)
        }
    }

    private const val NTP_EPOCH_OFFSET = 2_208_988_800L

    private fun readTimestamp(b: ByteArray, off: Int): Long {
        fun u32(o: Int) = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        val seconds = u32(off)
        val fraction = u32(off + 4)
        if (seconds == 0L) return 0
        return (seconds - NTP_EPOCH_OFFSET) * 1000 + fraction * 1000 / 0x1_0000_0000L
    }

    private fun writeTimestamp(b: ByteArray, off: Int, millis: Long) {
        val seconds = millis / 1000 + NTP_EPOCH_OFFSET
        val fraction = (millis % 1000) * 0x1_0000_0000L / 1000
        for (i in 0..3) b[off + i] = (seconds shr (24 - 8 * i)).toByte()
        for (i in 0..3) b[off + 4 + i] = (fraction shr (24 - 8 * i)).toByte()
    }

    /** ping の1回分。[ok] なら [rttMs] が往復にかかった時間 */
    data class PingReply(val ok: Boolean, val address: String, val rttMs: Long, val error: String = "")

    /**
     * ping を1回打つ。Android のアプリは ICMP を直に送れないので、InetAddress.isReachable（送れれば ICMP、だめなら TCP の 7 番）で代わりにする
     */
    fun ping(address: InetAddress, timeoutMs: Int): PingReply {
        val started = System.nanoTime()
        val ok = runCatching { address.isReachable(timeoutMs) }.getOrDefault(false)
        val rtt = (System.nanoTime() - started) / 1_000_000
        return PingReply(ok, address.hostAddress.orEmpty(), if (ok) rtt else 0, if (ok) "" else "timeout")
    }

    /** IP アドレスの書き方なら true（逆引きにする） */
    fun isAddress(host: String): Boolean =
        host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) || (':' in host && host.all { it.isLetterOrDigit() || it == ':' || it == '.' })

    // ---- ZIP（\![execute,extractarchive / compressarchive]） ----

    data class ArchiveResult(val files: Int, val compressed: Long, val uncompressed: Long)

    /** 展開するときの上限（細工した ZIP で端末を埋めさせない） */
    const val MAX_ARCHIVE_FILES = 10_000
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024

    /** [zip] を [dest] に展開する。[dest] の外に出るパス（zip slip）は失敗にする */
    fun extract(zip: File, dest: File): ArchiveResult {
        val root = dest.canonicalFile
        root.mkdirs()
        var files = 0
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val target = File(root, entry.name.replace('\\', '/')).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) throw IOException("invalid path")
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                if (++files > MAX_ARCHIVE_FILES) throw IOException("too many files")
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ARCHIVE_BYTES) throw IOException("too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        return ArchiveResult(files, zip.length(), total)
    }

    /** [source]（ファイルかフォルダ）を [zip] にまとめる。[zip] 自身は中に入れない */
    fun compress(source: File, zip: File): ArchiveResult {
        val base = source.canonicalFile
        val out = zip.canonicalFile
        val files = if (base.isDirectory) base.walkTopDown().filter { it.isFile && it.canonicalFile != out }.toList() else listOf(base)
        if (files.size > MAX_ARCHIVE_FILES) throw IOException("too many files")
        var total = 0L
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zout ->
            for (f in files) {
                val name = if (base.isDirectory) f.canonicalFile.relativeTo(base).invariantSeparatorsPath else f.name
                zout.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(zout) }
                zout.closeEntry()
                total += f.length()
                if (total > MAX_ARCHIVE_BYTES) throw IOException("too large")
            }
        }
        return ArchiveResult(files.size, out.length(), total)
    }
}

/**
 * \![execute,filewatch,パス]。ファイルかフォルダの変化を見張る（数百ミリ秒ごとに見比べる）。
 * 変化が止んでから [debounceMs] 待ってまとめて1回知らせる（変化が続いても、最初の変化から3倍たてば知らせる）
 */
class FileWatch(val target: File, private val debounceMs: Long) {
    private val directory = target.isDirectory
    private var last = snapshot()
    private var firstChange = 0L
    private var lastChange = 0L
    private var pending: String? = null

    /** フォルダなら直下の名前と更新時刻と大きさ、ファイルならその3つ（無ければ null） */
    private fun snapshot(): Any? = if (directory) {
        target.listFiles()?.associate { it.name to (it.lastModified() to it.length()) }
    } else {
        if (target.exists()) target.lastModified() to target.length() else null
    }

    /**
     * 見比べて、知らせる変化があれば返す。ファイルは modified / created / deleted、フォルダは changed / deleted。
     * 見張れなくなったら（フォルダが無くなった・ファイルを置くフォルダが無くなった）"failed"
     */
    fun poll(now: Long): String? {
        if (directory && !target.isDirectory) return "deleted"
        if (!directory && target.parentFile?.isDirectory != true) return "failed"
        val current = snapshot()
        if (current != last) {
            val kind = when {
                directory -> "changed"
                last == null -> "created"
                current == null -> "deleted"
                else -> "modified"
            }
            last = current
            // まとめる間は最初の種類を残す（作ってすぐ書いたら created）
            if (pending == null) {
                pending = kind
                firstChange = now
            } else if (kind == "deleted") {
                pending = kind
            }
            lastChange = now
        }
        val p = pending ?: return null
        if (now - lastChange >= debounceMs || now - firstChange >= debounceMs * 3) {
            pending = null
            return p
        }
        return null
    }
}
