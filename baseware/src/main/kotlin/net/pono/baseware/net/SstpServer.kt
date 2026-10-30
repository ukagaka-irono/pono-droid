package net.pono.baseware.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.pono.baseware.saori.SaoriRequest
import net.pono.baseware.saori.SaoriResponse
import net.pono.baseware.text.TextCharsets
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger

/**
 * SSTP の要求の1つ。[method] は SEND / NOTIFY / COMMUNICATE / EXECUTE / GIVE、[headers] は書かれた順（同じ名前が何度も出てよい。
 * IfGhost と Script の組のため）
 */
data class SstpRequest(val method: String, val version: String, val headers: List<Pair<String, String>>, val charset: Charset) {
    /** その名前の最初の値（名前の大文字小文字は見ない） */
    operator fun get(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Reference0, Reference1, ... を順に（途中が抜けたらそこまで） */
    val references: List<String>
        get() = generateSequence(0) { it + 1 }.map { get("Reference$it") }.takeWhile { it != null }.filterNotNull().toList()

    /** Option ヘッダ（カンマ区切り）の中身 */
    val options: Set<String> get() = get("Option").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}

/** SSTP の答え。[data] は EXECUTE などの追加データ */
data class SstpResponse(val code: Int, val headers: List<Pair<String, String>> = emptyList(), val data: String? = null) {
    companion object {
        val MESSAGES = mapOf(
            200 to "OK", 204 to "No Content", 210 to "Break", 400 to "Bad Request", 404 to "Not Found", 408 to "Request Timeout",
            409 to "Conflict", 413 to "Payload Too Large", 420 to "Refuse", 500 to "Internal Server Error", 501 to "Not Implemented",
            503 to "Service Unavailable", 505 to "Version Not Supported", 512 to "Invisible",
        )
    }
}

/**
 * SSTP サーバー。この端末の中（127.0.0.1）からだけ受ける。SSTP/1.x の要求と SSTP over HTTP（POST /api/sstp/v1）を読み、
 * [handler] の答えを返す。Connection: keep-alive なら続けて受ける。要求の大きさと、つないでいられる数には上限を置く
 */
class SstpServer(
    private val scope: CoroutineScope,
    private val handler: suspend (SstpRequest) -> SstpResponse,
    private val port: Int = DEFAULT_PORT,
    private val log: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val connections = AtomicInteger(0)

    /** 実際に待っているポート（[port] に 0 を渡したときなど）。始めていなければ -1 */
    val localPort: Int get() = server?.localPort ?: -1

    /** 待ち受けを始める。ポートが使われていれば false */
    fun start(): Boolean {
        if (server != null) return true
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG)
            }
        } catch (e: IOException) {
            log("SSTP のポート $port で待てません: ${e.message}")
            return false
        }
        server = s
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val socket = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                // この端末の外からは受けない（loopback に bind しているが、念のため）
                if (!socket.inetAddress.isLoopbackAddress || connections.get() >= MAX_CONNECTIONS) {
                    runCatching { socket.close() }
                    continue
                }
                connections.incrementAndGet()
                launch(Dispatchers.IO) {
                    try {
                        serve(socket)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 途中で切られた（RST・Broken pipe）、読み終わる前の時間切れなど。この接続だけ閉じ、待ち受けは続ける
                        // （ここで抜けると待ち受けのコルーチンごと止まり、アプリでは例外で落ちる）
                        log("SSTP の接続を閉じました: ${e.javaClass.simpleName} ${e.message}")
                    } finally {
                        connections.decrementAndGet()
                        runCatching { socket.close() }
                    }
                }
            }
        }
        return true
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { server?.close() }
        server = null
    }

    private suspend fun serve(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        // 1 バイトずつゆっくり送られても接続の枠を持ち続けられないよう、1 つの要求を読み終えるまでの時間にも上限を置く
        val deadline = DeadlineInput(socket.getInputStream())
        val input = deadline.buffered()
        val output = socket.getOutputStream()
        while (true) {
            deadline.until = System.currentTimeMillis() + REQUEST_DEADLINE_MS
            val head = try {
                readUntilBlankLine(input) ?: return
            } catch (e: SocketTimeoutException) {
                return
            } catch (e: TooLarge) {
                write(output, "SSTP/1.4", SstpResponse(413), Charsets.UTF_8, keepAlive = false)
                return
            }
            if (head.isEmpty()) return
            val firstLine = head.decodeToString().substringBefore("\r\n")
            // SAORI over DirectSSTP は Direct SSTP だけ（ソケットでは受けない）
            directSstpOnly(head)?.let {
                output.write(it)
                output.flush()
                return
            }
            if (firstLine.startsWith("POST ") || firstLine.startsWith("GET ") || firstLine.startsWith("OPTIONS ")) {
                if (!serveHttp(head, input, output)) return
                continue
            }
            val request = parse(head)
            if (request == null) {
                write(output, "SSTP/1.4", SstpResponse(400), Charsets.UTF_8, keepAlive = false)
                return
            }
            val keepAlive = request["Connection"]?.equals("keep-alive", ignoreCase = true) == true && request.version.startsWith("SSTP/1.")
            val response = answer(request)
            write(output, request.version, response, request.charset, keepAlive, explicit = request["Connection"] != null)
            if (!keepAlive) return
        }
    }

    private suspend fun answer(request: SstpRequest): SstpResponse {
        val v = request.version.removePrefix("SSTP/").toDoubleOrNull()
        if (v == null || v < 1.0 || v >= 3.0) return SstpResponse(505)
        return withTimeoutOrNull(HANDLER_TIMEOUT_MS) { runCatching { handler(request) }.getOrElse { SstpResponse(500) } } ?: SstpResponse(408)
    }

    /** SSTP over HTTP。中身の SSTP をそのまま扱い、HTTP はいつも 200。続けて受けるなら true */
    private suspend fun serveHttp(head: ByteArray, input: InputStream, output: OutputStream): Boolean {
        val text = head.decodeToString()
        val lines = text.split("\r\n")
        val (method, path) = lines[0].split(' ').let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        val headers = lines.drop(1).filter { ':' in it }.map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }.toMap()
        val http11 = lines[0].endsWith("HTTP/1.1")
        val keepAlive = if (http11) headers["connection"]?.equals("close", ignoreCase = true) != true else headers["connection"]?.equals("keep-alive", ignoreCase = true) == true
        fun reply(status: String, body: ByteArray, type: String = "text/plain; charset=UTF-8") {
            val h = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n"
            output.write(h.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
        val length = headers["content-length"]?.toIntOrNull()?.takeIf { it >= 0 }
        if (method != "POST" || path.substringBefore('?') != "/api/sstp/v1" || length == null || headers.containsKey("transfer-encoding")) {
            reply("404 Not Found", ByteArray(0))
            return false
        }
        if (length > MAX_REQUEST_BYTES) {
            reply("413 Payload Too Large", ByteArray(0))
            return false
        }
        val body = readExactly(input, length) ?: return false
        // localhost のページでなければ（Origin が別のサイト）、そのページのスクリプトがゴーストを操ることになるので受けない。
        // Origin: null（sandbox 付きの iframe・ファイルのページなど）も、どのサイトからでも作れるので受けない
        val origin = headers["origin"]
        if (origin != null && !isLocalOrigin(origin)) {
            reply("403 Forbidden", ByteArray(0))
            return false
        }
        directSstpOnly(body)?.let {
            reply("200 OK", it)
            return keepAlive
        }
        val request = parse(body)
        val out = ByteArrayOutputStream()
        if (request == null) {
            writeTo(out, "SSTP/1.4", SstpResponse(400), Charsets.UTF_8, keepAlive = false, explicit = false)
        } else {
            writeTo(out, request.version, answer(request), request.charset, keepAlive = false, explicit = false)
        }
        reply("200 OK", out.toByteArray())
        return keepAlive
    }

    /** SAORI/1.0 の要求なら、400（direct sstp only）の SAORI/1.0 の応答。SSTP なら null */
    private fun directSstpOnly(bytes: ByteArray): ByteArray? {
        val req = SaoriRequest.parse(bytes) ?: return null
        return SaoriResponse.error(400, "direct sstp only").encode(req.charset)
    }

    private fun isLocalOrigin(origin: String): Boolean {
        // http://[::1]:8080 や http://[::1] の : は IPv6 の中にもあるので、URI として読む
        val host = runCatching { java.net.URI(origin.trim()).host }.getOrNull()?.trim('[', ']')?.lowercase() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }

    private fun write(output: OutputStream, version: String, r: SstpResponse, charset: Charset, keepAlive: Boolean, explicit: Boolean = false) {
        val out = ByteArrayOutputStream()
        writeTo(out, version, r, charset, keepAlive, explicit)
        output.write(out.toByteArray())
        output.flush()
    }

    private fun writeTo(out: OutputStream, version: String, r: SstpResponse, charset: Charset, keepAlive: Boolean, explicit: Boolean) {
        val v = version.takeIf { it.startsWith("SSTP/") } ?: "SSTP/1.4"
        val data = r.data?.toByteArray(charset)
        val sb = StringBuilder("$v ${r.code} ${SstpResponse.MESSAGES[r.code] ?: ""}\r\n")
        sb.append("Charset: ${charsetLabel(charset)}\r\n")
        for ((k, value) in r.headers) sb.append("$k: ${value.replace("\r", "").replace("\n", "")}\r\n")
        if (explicit) {
            sb.append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
            if (keepAlive) sb.append("Keep-Alive: timeout=${READ_TIMEOUT_MS / 1000}\r\n")
        }
        if (keepAlive && data != null) sb.append("Content-Length: ${data.size}\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(charset))
        if (data != null) {
            out.write(data)
            // 切る答えは、追加データの後にも空行（SSP と同じ）
            if (!keepAlive) out.write("\r\n".toByteArray(charset))
        }
    }

    /** 答えの Charset ヘッダに書く名前（Windows の Shift_JIS は Shift_JIS と書く） */
    private fun charsetLabel(c: Charset): String = if (c == TextCharsets.CP932) "Shift_JIS" else c.name()

    private class TooLarge : IOException()

    /** [until]（時刻）を過ぎてから読もうとしたら、時間切れにする */
    private class DeadlineInput(input: InputStream) : java.io.FilterInputStream(input) {
        @Volatile var until = Long.MAX_VALUE

        private fun check() {
            if (System.currentTimeMillis() > until) throw SocketTimeoutException("request too slow")
        }

        override fun read(): Int {
            check()
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check()
            return super.read(b, off, len)
        }
    }

    /** CR LF CR LF まで読む。つながったまま何も来なければ null */
    private fun readUntilBlankLine(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val pattern = byteArrayOf(13, 10, 13, 10)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toByteArray()
            // 前の要求の後ろに付いたゼロ終端などは飛ばす
            if (buf.size() == 0 && (b == 0 || b == 13 || b == 10)) continue
            buf.write(b)
            if (buf.size() > MAX_REQUEST_BYTES) throw TooLarge()
            matched = if (b.toByte() == pattern[matched]) matched + 1 else if (b == 13) 1 else 0
            if (matched == 4) return buf.toByteArray()
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val k = input.read(out, read, n - read)
            if (k < 0) return null
            read += k
        }
        return out
    }

    companion object {
        const val DEFAULT_PORT = 9801

        /** SSP だけが待つもう1つのポート（spec/sstp/sstp.md） */
        const val SSP_PORT = 9821
        private const val BACKLOG = 8
        private const val MAX_CONNECTIONS = 8
        const val MAX_REQUEST_BYTES = 256 * 1024
        private const val READ_TIMEOUT_MS = 5_000

        /** 1 つの要求（頭と本文）を読み終えるまでの上限 */
        private const val REQUEST_DEADLINE_MS = 15_000L
        private const val HANDLER_TIMEOUT_MS = 30_000L

        /** 要求を読む。Charset ヘッダの文字コードで読み直す（無ければ UTF-8）。読めなければ null */
        fun parse(bytes: ByteArray): SstpRequest? {
            val ascii = String(bytes, Charsets.ISO_8859_1)
            val charsetName = Regex("""(?im)^Charset:\s*([^\r\n]+)""").find(ascii)?.groupValues?.get(1)?.trim()
            val charset = TextCharsets.resolve(charsetName) ?: Charsets.UTF_8
            val text = String(bytes, charset)
            val lines = text.split("\r\n").filter { it.isNotEmpty() }
            val first = lines.firstOrNull()?.split(' ') ?: return null
            if (first.size < 2) return null
            val method = first[0].uppercase()
            if (method !in METHODS) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, i).trim() to line.substring(i + 1).trimStart()
            }
            return SstpRequest(method, first[1].trim(), headers, charset)
        }

        private val METHODS = setOf("SEND", "NOTIFY", "COMMUNICATE", "EXECUTE", "GIVE")

        /** テストやほかのアプリのための小さなクライアント。[request] を送って答えの全部を返す */
        fun send(port: Int, request: String, charset: Charset = Charsets.UTF_8): String = Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().apply {
                write(request.toByteArray(charset))
                flush()
            }
            s.shutdownOutput()
            String(s.getInputStream().readBytes(), charset)
        }
    }
}
