package net.pono.baseware.net

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * メールの確認（\![biff]）に使うアカウント。いつも TLS（POP3S 995 / IMAPS 993）でつなぐ。パスワードを平文で送る口は使わない。
 * [password] はログに出さない
 */
data class MailAccount(val name: String, val protocol: String, val host: String, val port: Int, val user: String, val password: String) {
    override fun toString() = "MailAccount($name, $protocol, $host:$port, $user)"
}

/** 受信箱に入っている1通。[size] はバイト数 */
data class MailItem(val id: String, val uid: String, val size: Long, val from: String, val subject: String, val headers: List<String>)

sealed interface MailResult {
    data class Ok(val items: List<MailItem>, val count: Int, val bytes: Long) : MailResult

    /** [reason] は timeout / kick（ログインを断られた）/ defect（答えが読めない） */
    data class Failure(val reason: String) : MailResult
}

/** POP3 / IMAP で受信箱の数と、新しいものの差出人と件名を読む（中身は読まない・消さない） */
fun interface MailChecker {
    fun check(account: MailAccount): MailResult
}

class TlsMailChecker(private val timeoutMs: Int = 20_000, private val maxItems: Int = 50) : MailChecker {
    override fun check(account: MailAccount): MailResult = try {
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
        socket.use { s ->
            // 証明書とホスト名をいつも確かめる
            s.sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS" }
            s.connect(InetSocketAddress(account.host, account.port), timeoutMs)
            s.soTimeout = timeoutMs
            s.startHandshake()
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val out = s.getOutputStream()
            when (account.protocol.lowercase()) {
                "imap" -> Imap(reader, out, maxItems).check(account)
                else -> Pop3(reader, out, maxItems).check(account)
            }
        }
    } catch (e: SocketTimeoutException) {
        MailResult.Failure("timeout")
    } catch (e: IOException) {
        MailResult.Failure("defect")
    }

    internal class Kick : IOException()

    internal class Pop3(private val r: BufferedReader, private val out: OutputStream, private val max: Int) {
        private fun send(line: String) {
            out.write("$line\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        private fun ok(): String {
            val line = r.readLine() ?: throw IOException("closed")
            if (!line.startsWith("+OK")) throw IOException("pop3")
            return line
        }

        /** 複数行の答え（. の行まで） */
        private fun multi(): List<String> {
            ok()
            val lines = mutableListOf<String>()
            while (true) {
                val line = r.readLine() ?: throw IOException("closed")
                if (line == ".") return lines
                lines += if (line.startsWith("..")) line.substring(1) else line
                if (lines.size > 100_000) throw IOException("too long")
            }
        }

        fun check(a: MailAccount): MailResult {
            ok()
            send("USER ${a.user}")
            if (r.readLine()?.startsWith("+OK") != true) return MailResult.Failure("kick")
            send("PASS ${a.password}")
            if (r.readLine()?.startsWith("+OK") != true) return MailResult.Failure("kick")
            send("STAT")
            val stat = ok().split(' ')
            val count = stat.getOrNull(1)?.toIntOrNull() ?: 0
            val bytes = stat.getOrNull(2)?.toLongOrNull() ?: 0
            send("LIST")
            val sizes = multi().mapNotNull { l -> l.split(' ').takeIf { it.size >= 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0) } }.toMap()
            send("UIDL")
            val uids = runCatching { multi() }.getOrDefault(emptyList()).mapNotNull { l -> l.split(' ').takeIf { it.size >= 2 }?.let { it[0] to it[1] } }.toMap()
            // 新しいもの（番号の大きいもの）から [max] 通だけ、ヘッダを読む
            val items = sizes.keys.mapNotNull { it.toIntOrNull() }.sortedDescending().take(max).sorted().map { n ->
                send("TOP $n 0")
                val headers = unfold(multi())
                MailItem(n.toString(), uids[n.toString()].orEmpty(), sizes[n.toString()] ?: 0, header(headers, "From"), header(headers, "Subject"), headers)
            }
            send("QUIT")
            return MailResult.Ok(items, count, bytes)
        }
    }

    internal class Imap(private val r: BufferedReader, private val out: OutputStream, private val max: Int) {
        private var tag = 0

        /** 命令を送り、終わりの行までの答えを返す。OK でなければ例外 */
        private fun command(cmd: String, secret: Boolean = false): List<String> {
            val t = "a${++tag}"
            out.write("$t $cmd\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val lines = mutableListOf<String>()
            while (true) {
                var line = r.readLine() ?: throw IOException("closed")
                // {123} で終わる行は、続く 123 バイトをつなぐ（ヘッダの中身）
                val literal = Regex("""\{(\d+)\}$""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                if (literal != null && literal in 0..1_000_000) {
                    val buf = CharArray(literal)
                    var read = 0
                    while (read < literal) read += r.read(buf, read, literal - read).also { if (it < 0) throw IOException("closed") }
                    // literal の後ろ（UID や RFC822.SIZE が続くことがある）は 1 行目に付け、literal の中身（ヘッダ）とは混ぜない
                    val rest = r.readLine().orEmpty()
                    line = line + " " + rest + "\n" + String(buf)
                }
                if (line.startsWith("$t ")) {
                    if (!line.startsWith("$t OK")) throw if (secret) Kick() else IOException("imap")
                    return lines
                }
                lines += line
                if (lines.size > 100_000) throw IOException("too long")
            }
        }

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun check(a: MailAccount): MailResult {
            r.readLine() ?: throw IOException("closed")
            try {
                command("LOGIN ${quote(a.user)} ${quote(a.password)}", secret = true)
            } catch (e: Kick) {
                return MailResult.Failure("kick")
            }
            val selected = command("EXAMINE INBOX")
            val count = selected.firstNotNullOfOrNull { Regex("""^\* (\d+) EXISTS""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            val items = if (count == 0) emptyList() else {
                val from = maxOf(1, count - max + 1)
                command("FETCH $from:$count (UID RFC822.SIZE BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)])").mapNotNull { line ->
                    val m = Regex("""^\* (\d+) FETCH""").find(line) ?: return@mapNotNull null
                    val uid = Regex("""UID (\d+)""").find(line)?.groupValues?.get(1).orEmpty()
                    val size = Regex("""RFC822\.SIZE (\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val headers = unfold(line.substringAfter("\n", "").split("\r\n", "\n"))
                    MailItem(m.groupValues[1], uid, size, header(headers, "From"), header(headers, "Subject"), headers)
                }
            }
            runCatching { command("LOGOUT") }
            return MailResult.Ok(items, count, items.sumOf { it.size })
        }
    }

    companion object {
        /** 折り返したヘッダ（空白で始まる行）をつなぐ */
        fun unfold(lines: List<String>): List<String> {
            val out = mutableListOf<String>()
            for (l in lines) {
                if (l.isEmpty() || l == ")") continue
                if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] = out.last() + " " + l.trim() else out += l
            }
            return out
        }

        fun header(headers: List<String>, name: String): String =
            headers.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()?.let(::decodeMime).orEmpty()

        /** =?UTF-8?B?...?= / =?ISO-2022-JP?Q?...?= のような MIME の符号化を戻す */
        fun decodeMime(s: String): String = Regex("""=\?([^?]+)\?([BbQq])\?([^?]*)\?=""").replace(s.replace(Regex("""\?=\s+=\?"""), "?==?")) { m ->
            val charset = runCatching { java.nio.charset.Charset.forName(m.groupValues[1]) }.getOrNull() ?: return@replace m.value
            val bytes = if (m.groupValues[2].equals("B", true)) {
                runCatching { java.util.Base64.getMimeDecoder().decode(m.groupValues[3]) }.getOrNull() ?: return@replace m.value
            } else {
                val q = m.groupValues[3].replace('_', ' ')
                val buf = java.io.ByteArrayOutputStream()
                var i = 0
                while (i < q.length) {
                    if (q[i] == '=' && i + 2 < q.length) {
                        q.substring(i + 1, i + 3).toIntOrNull(16)?.let { buf.write(it); i += 3; continue }
                    }
                    buf.write(q[i].code)
                    i++
                }
                buf.toByteArray()
            }
            String(bytes, charset)
        }
    }
}
