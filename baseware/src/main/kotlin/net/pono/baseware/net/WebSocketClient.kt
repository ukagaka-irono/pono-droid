package net.pono.baseware.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** WebSocket で受け取ったもの。[binary] なら [data] はそのままのバイト列 */
class WebSocketMessage(val binary: Boolean, val data: ByteArray)

/** WebSocket の1つのつながり。[open] でつなぎ、[read] で1つずつ受け取る（RFC 6455。クライアントからは必ずマスクする） */
interface WebSocketSession {
    /** つながったときの TLS の様子（ws:// なら null） */
    val tls: TlsInfo?

    /** 次のメッセージ。相手が閉じたら null（[closeCode] に理由） */
    fun read(): WebSocketMessage?
    fun sendText(text: String)
    fun sendBinary(data: ByteArray)

    /** Close を送って閉じる（相手の Close を待つのは [read] の側） */
    fun close(code: Int)

    /** Close を送らずに切る */
    fun abort()
    val closeCode: Int?
}

fun interface WebSocketConnector {
    /** つなぐ。だめなら IOException（メッセージは SSP の Failure の Reference2 にそのまま入れる） */
    fun open(url: String, headers: List<Pair<String, String>>, protocol: String?): WebSocketSession
}

/** Socket の上に作った WebSocket。証明書と名前は HTTPS と同じく確かめる */
class SocketWebSocketConnector(private val userAgent: String) : WebSocketConnector {
    override fun open(url: String, headers: List<Pair<String, String>>, protocol: String?): WebSocketSession {
        val uri = runCatching { URI(url) }.getOrNull() ?: throw IOException("invalid url")
        val secure = when (uri.scheme?.lowercase()) {
            "wss" -> true
            "ws" -> false
            else -> throw IOException("invalid url")
        }
        val host = uri.host ?: throw IOException("invalid url")
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        // 範囲外のポートは InetSocketAddress が IllegalArgumentException を出す。約束どおり IOException にする
        if (port !in 1..65535) throw IOException("invalid url")
        val raw = Socket()
        // つなぐ・ハンドシェイクのどこで失敗しても、ソケットを閉じてから IOException で返す
        return try {
            handshake(raw, uri, host, port, secure, headers, protocol)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handshake(
        raw: Socket,
        uri: URI,
        host: String,
        port: Int,
        secure: Boolean,
        headers: List<Pair<String, String>>,
        protocol: String?,
    ): WebSocketSession {
        var socket: Socket = raw.apply {
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            soTimeout = CONNECT_TIMEOUT_MS
        }
        var tls: TlsInfo? = null
        if (secure) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket
            runCatching { ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(host)) } }
            ssl.startHandshake()
            // SSLSocket だけでは名前を確かめないので、HTTPS と同じ確かめ方をする
            socket = ssl
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                ssl.close()
                throw IOException("ssl")
            }
            val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
            tls = cert?.let {
                TlsInfo(
                    "TLSv1/SSLv3", ssl.session.cipherSuite, UrlConnectionHttpClient.oneline(it.subjectX500Principal.name),
                    UrlConnectionHttpClient.dateTime(it.notBefore.time), UrlConnectionHttpClient.dateTime(it.notAfter.time),
                    UrlConnectionHttpClient.oneline(it.issuerX500Principal.name),
                )
            }
        }
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
        val request = buildString {
            append("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: $userAgent\r\n")
            if (!protocol.isNullOrEmpty()) append("Sec-WebSocket-Protocol: $protocol\r\n")
            for ((k, v) in headers) append("$k: $v\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(request.toByteArray(Charsets.UTF_8))
        out.flush()
        val input = BufferedInputStream(socket.getInputStream())
        val status = readLine(input) ?: throw IOException("no response")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        val responseHeaders = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }.toList()
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        if (code != 101) {
            socket.close()
            throw IOException("HTTP ${code ?: 0}")
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
        )
        if (responseHeaders["sec-websocket-accept"] != expected) {
            socket.close()
            throw IOException("invalid handshake")
        }
        // つながった後は、来るまで待ち続ける
        socket.soTimeout = 0
        return FrameSession(socket, input, out, tls)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").removeSuffix("\r")
            if (buf.size() > MAX_HEADER_LINE) throw IOException("header too long")
            buf.write(b)
        }
    }

    private class FrameSession(
        private val socket: Socket,
        private val input: InputStream,
        private val out: OutputStream,
        override val tls: TlsInfo?,
    ) : WebSocketSession {
        private val random = SecureRandom()
        override var closeCode: Int? = null
        private var closeSent = false

        override fun read(): WebSocketMessage? {
            val message = ByteArrayOutputStream()
            var binary = false
            while (true) {
                val b0 = input.read()
                if (b0 < 0) {
                    if (closeCode == null) closeCode = 1006
                    return null
                }
                val b1 = input.read()
                if (b1 < 0) throw IOException("closed")
                val fin = b0 and 0x80 != 0
                val opcode = b0 and 0x0F
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
                else if (length == 127L) length = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or input.read().toLong() }
                val masked = b1 and 0x80 != 0
                val mask = if (masked) ByteArray(4).also { readFully(it) } else null
                if (length < 0 || length > MAX_MESSAGE || message.size() + length > MAX_MESSAGE) throw IOException("message too large")
                val payload = ByteArray(length.toInt()).also { readFully(it) }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    // 制御フレーム: ping には pong、close なら閉じる
                    0x9 -> send(0xA, payload)
                    0xA -> {}
                    0x8 -> {
                        closeCode = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1000
                        if (!closeSent) runCatching { send(0x8, payload.take(2).toByteArray()) }
                        runCatching { socket.close() }
                        return null
                    }
                    else -> {
                        if (opcode == 0x1 || opcode == 0x2) binary = opcode == 0x2
                        message.write(payload)
                        if (fin) return WebSocketMessage(binary, message.toByteArray())
                    }
                }
            }
        }

        private fun readFully(buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) throw IOException("closed")
                off += n
            }
        }

        override fun sendText(text: String) = send(0x1, text.toByteArray(Charsets.UTF_8))
        override fun sendBinary(data: ByteArray) = send(0x2, data)

        override fun close(code: Int) {
            if (closeSent) return
            closeSent = true
            runCatching { send(0x8, byteArrayOf((code shr 8).toByte(), code.toByte())) }
        }

        override fun abort() {
            runCatching { socket.close() }
        }

        @Synchronized
        private fun send(opcode: Int, payload: ByteArray) {
            val header = ByteArrayOutputStream()
            header.write(0x80 or opcode)
            when {
                payload.size < 126 -> header.write(0x80 or payload.size)
                payload.size <= 0xFFFF -> {
                    header.write(0x80 or 126)
                    header.write(payload.size shr 8)
                    header.write(payload.size and 0xFF)
                }
                else -> {
                    header.write(0x80 or 127)
                    for (i in 7 downTo 0) header.write(((payload.size.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
            val mask = ByteArray(4).also { random.nextBytes(it) }
            header.write(mask)
            val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
            out.write(header.toByteArray())
            out.write(masked)
            out.flush()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val MAX_HEADER_LINE = 8 * 1024

        /** 1つのメッセージの上限（大きなものを送りつけてメモリを使い切らせない） */
        const val MAX_MESSAGE = 4L * 1024 * 1024
    }
}
