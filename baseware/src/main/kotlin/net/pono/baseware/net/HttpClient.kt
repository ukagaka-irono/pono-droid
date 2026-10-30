package net.pono.baseware.net

import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.HttpsURLConnection

/** \![execute,http-*] の1回の通信。[body] は POST / PUT / PATCH で送る中身 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
    /** 応答を待つ時間（ミリ秒） */
    val timeoutMs: Int = 30_000,
) {
    override fun equals(other: Any?) = other is HttpRequest && method == other.method && url == other.url &&
        headers == other.headers && body.contentEquals(other.body) && timeoutMs == other.timeoutMs

    override fun hashCode() = (method + url).hashCode()
}

/** TLS でつないだときの様子（OnExecuteHTTPSSLInfo）。日時は「年,月,日,時,分,秒」 */
data class TlsInfo(
    val protocol: String,
    val cipher: String,
    val subject: String,
    val notBefore: String,
    val notAfter: String,
    val issuer: String,
)

sealed interface HttpOutcome {
    /** 応答が来た（[code] が 200 番台でなくても）。中身は sink に流した */
    data class Response(val code: Int, val headers: List<Pair<String, String>>, val tls: TlsInfo?, val bytes: Long) : HttpOutcome

    /** 応答が来なかった・途中で切れた。[reason] は SSP の失敗理由（timeout / fileio など）か、読める言葉 */
    data class Failed(val reason: String, val tls: TlsInfo? = null) : HttpOutcome
}

/**
 * 通信する側。中身は [sink] に流し、[maxBytes] を超えたら途中でやめる。[onResponse] は応答の頭が来たとき（中身を受け取る前）、
 * [onProgress] は受け取った量が増えるたびに呼ぶ
 */
interface HttpClient {
    fun execute(
        request: HttpRequest,
        sink: OutputStream,
        maxBytes: Long,
        onResponse: (code: Int, headers: List<Pair<String, String>>) -> Unit = { _, _ -> },
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): HttpOutcome
}

/** HttpURLConnection を使う実装。証明書は必ず確かめる（\![execute] の --insecure は使わない） */
class UrlConnectionHttpClient(private val userAgent: String) : HttpClient {
    override fun execute(
        request: HttpRequest,
        sink: OutputStream,
        maxBytes: Long,
        onResponse: (code: Int, headers: List<Pair<String, String>>) -> Unit,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): HttpOutcome {
        val conn = try {
            // 範囲外のポートは、つなぐときに IllegalArgumentException になる（JVM）ので、先に断る
            URI(request.url).takeIf { it.port <= 65535 }!!.toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return HttpOutcome.Failed("invalid url")
        }
        var tls: TlsInfo? = null
        return try {
            try {
                conn.requestMethod = request.method
            } catch (e: ProtocolException) {
                // Android の HttpURLConnection は PATCH も使えるが、使えない環境では送れない
                return HttpOutcome.Failed("unsupported method")
            }
            conn.connectTimeout = 15_000
            conn.readTimeout = request.timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in request.headers) conn.setRequestProperty(k, v)
            val body = request.body
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            tls = (conn as? HttpsURLConnection)?.let(::tlsInfo)
            val headers = conn.headerFields.orEmpty().flatMap { (k, values) -> if (k == null) emptyList() else values.map { k to it } }
            onResponse(code, headers)
            val total = conn.contentLengthLong
            if (total > maxBytes) return HttpOutcome.Failed("file too large", tls)
            val input = if (code >= 400) conn.errorStream else conn.inputStream
            var received = 0L
            if (request.method != "HEAD") {
                input?.use { stream ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        received += n
                        if (received > maxBytes) return HttpOutcome.Failed("file too large", tls)
                        sink.write(buf, 0, n)
                        onProgress(received, total)
                    }
                }
            }
            HttpOutcome.Response(code, headers, tls, received)
        } catch (e: SocketTimeoutException) {
            HttpOutcome.Failed("timeout", tls)
        } catch (e: IOException) {
            HttpOutcome.Failed(
                when (e) {
                    is javax.net.ssl.SSLException -> "ssl"
                    is java.net.UnknownHostException -> "unknown host"
                    is java.net.ConnectException -> "connection refused"
                    else -> "fileio"
                },
                tls,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun tlsInfo(conn: HttpsURLConnection): TlsInfo? = runCatching {
        val cert = conn.serverCertificates.firstOrNull() as? X509Certificate ?: return null
        TlsInfo(
            // SSP は OpenSSL の版に関係なく "TLSv1/SSLv3" と伝える
            protocol = "TLSv1/SSLv3",
            cipher = conn.cipherSuite.orEmpty(),
            subject = oneline(cert.subjectX500Principal.name),
            notBefore = dateTime(cert.notBefore.time),
            notAfter = dateTime(cert.notAfter.time),
            issuer = oneline(cert.issuerX500Principal.name),
        )
    }.getOrNull()

    companion object {
        /** RFC 2253 の名前（CN=a,O=b,C=JP）を、OpenSSL の X509_NAME_oneline の形（/C=JP/O=b/CN=a）にする */
        fun oneline(rfc2253: String): String {
            val parts = mutableListOf<String>()
            val cur = StringBuilder()
            var escaped = false
            for (ch in rfc2253) {
                when {
                    escaped -> {
                        cur.append(ch)
                        escaped = false
                    }
                    ch == '\\' -> escaped = true
                    ch == ',' -> {
                        parts += cur.toString()
                        cur.clear()
                    }
                    else -> cur.append(ch)
                }
            }
            if (cur.isNotEmpty()) parts += cur.toString()
            return parts.asReversed().joinToString("") { "/" + it.trim() }
        }

        fun dateTime(millis: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            return "%d,%d,%d,%d,%d,%d".format(java.util.Locale.ROOT,
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
            )
        }
    }
}

/** [HttpFetcher]（GET だけ）を [HttpClient] として使う。テストと、通信を差し替えたいとき向け */
class FetcherHttpClient(private val fetcher: HttpFetcher) : HttpClient {
    override fun execute(
        request: HttpRequest,
        sink: OutputStream,
        maxBytes: Long,
        onResponse: (code: Int, headers: List<Pair<String, String>>) -> Unit,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): HttpOutcome {
        var count = 0L
        val counting = object : OutputStream() {
            override fun write(b: Int) {
                sink.write(b)
                count++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                sink.write(b, off, len)
                count += len
                onProgress(count, -1)
            }
        }
        return when (val r = fetcher.fetch(request.url, counting, maxBytes)) {
            FetchResult.Ok -> {
                onResponse(200, emptyList())
                HttpOutcome.Response(200, emptyList(), null, count)
            }
            is FetchResult.HttpError -> HttpOutcome.Response(r.code, emptyList(), null, 0)
            FetchResult.Timeout -> HttpOutcome.Failed("timeout")
            is FetchResult.Failed -> HttpOutcome.Failed(r.message)
        }
    }
}
