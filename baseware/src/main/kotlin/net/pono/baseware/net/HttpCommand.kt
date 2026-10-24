package net.pono.baseware.net

import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * \![execute,http-get / post / head / put / delete / patch / options,URL,オプション...] と rss-get / rss-post、ical-get / ical-post。
 * 古い書き方（\![execute,http-post,URL,パラメータ]）の「--」で始まらない引数は --param と同じ
 */
data class HttpCommand(
    /** http / rss / ical */
    val kind: String,
    /** GET / POST / HEAD / PUT / DELETE / PATCH / OPTIONS */
    val method: String,
    val url: String,
    /** --async / --sync の ID（無ければ空） */
    val id: String = "",
    /** --async なら待たずに続ける。それ以外（--sync も、何も無いのも）は終わるまでスクリプトを止める */
    val async: Boolean = false,
    val file: String? = null,
    /** --nofile。中身を Reference3 に入れる（文字コード） */
    val nofile: Charset? = null,
    val params: List<String> = emptyList(),
    val paramCharset: Charset = Charsets.UTF_8,
    val contentType: String? = null,
    val paramInputFile: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val timeoutSec: Int = 30,
    val progress: Boolean = false,
    /** --streaming の種類（auto / sse / text）。null ならストリーミングしない */
    val streaming: String? = null,
    val nodescript: Boolean = false,
    val insecure: Boolean = false,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val expand: Boolean = false,
    val expandDays: Int? = null,
    val limit: Int? = null,
) {
    /** SSP のイベント名。--async / --sync の ID が On で始まれば、成功は ID、そのほかは ID の後ろに Failure などを付けた名前 */
    fun event(suffix: String): String {
        if (id.startsWith("On")) return id + suffix
        val base = when (kind) {
            "rss" -> "OnExecuteRSS"
            "ical" -> "OnExecuteICal"
            else -> "OnExecuteHTTP"
        }
        return base + suffix.ifEmpty { "Complete" }
    }

    /** GET などでは、パラメータは URL の後ろに付ける */
    fun requestUrl(): String {
        if (method in BODY_METHODS || params.isEmpty()) return url
        return url + (if ('?' in url) "&" else "?") + params.joinToString("&")
    }

    /** POST / PUT / PATCH で送る中身と Content-Type。--param-input-file の中身は [inputFile] で渡す */
    fun body(inputFile: ByteArray?): Pair<ByteArray, String>? {
        if (method !in BODY_METHODS) return null
        val type = contentType ?: "application/x-www-form-urlencoded"
        if (inputFile != null) return inputFile to type
        if (type.startsWith("multipart/form-data", ignoreCase = true)) {
            val boundary = "----pono" + System.nanoTime().toString(16)
            val out = java.io.ByteArrayOutputStream()
            for (p in params) {
                val name = p.substringBefore('=')
                val value = p.substringAfter('=', "")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(paramCharset))
                out.write(value.toByteArray(paramCharset))
                out.write("\r\n".toByteArray())
            }
            out.write("--$boundary--\r\n".toByteArray())
            return out.toByteArray() to "multipart/form-data; boundary=$boundary"
        }
        // フィールド名=データ だけなら & でつなぐ。それ以外（昔の書き方）は改行でつなぐ
        val joined = if (type.startsWith("application/x-www-form-urlencoded", true) && params.all { '=' in it }) {
            params.joinToString("&")
        } else {
            params.joinToString("\r\n")
        }
        return joined.toByteArray(paramCharset) to type
    }

    companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")

        /** --timeout の上限（秒） */
        const val MAX_TIMEOUT_SEC = 300

        private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE

        /** [name] は http-get / rss-post など。扱えなければ null */
        fun parse(name: String, args: List<String>): HttpCommand? {
            val (kind, verb) = name.lowercase().split('-', limit = 2).takeIf { it.size == 2 } ?: return null
            if (kind !in setOf("http", "rss", "ical")) return null
            val method = verb.uppercase()
            if (method !in setOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH", "OPTIONS")) return null
            if (kind != "http" && method !in setOf("GET", "POST")) return null
            val url = args.firstOrNull()?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
            var cmd = HttpCommand(kind, method, url)
            val params = mutableListOf<String>()
            val headers = mutableListOf<Pair<String, String>>()
            for (raw in args.drop(1)) {
                val a = raw.trim()
                if (!a.startsWith("--")) {
                    if (a.isNotEmpty()) params += a
                    continue
                }
                val key = a.substring(2).substringBefore('=').lowercase()
                val value = a.substringAfter('=', "")
                cmd = when (key) {
                    "async" -> cmd.copy(id = value, async = true)
                    "sync" -> cmd.copy(id = value, async = false)
                    "file" -> cmd.copy(file = value.takeIf { it.isNotEmpty() })
                    "nofile" -> cmd.copy(nofile = charset(value))
                    "param" -> cmd.also { params += value }
                    "param-charset" -> cmd.copy(paramCharset = charset(value))
                    "param-input-file" -> cmd.copy(paramInputFile = value)
                    "content-type" -> cmd.copy(contentType = value)
                    "accept" -> cmd.also { headers += "Accept" to value }
                    "accept-language" -> cmd.also { headers += "Accept-Language" to value }
                    "authorization" -> cmd.also { headers += "Authorization" to value }
                    "cookie" -> cmd.also { headers += "Cookie" to value }
                    "no-cache" -> cmd.also { headers += "Cache-Control" to "no-cache" }
                    "header" -> cmd.also {
                        val n = value.substringBefore(':').trim()
                        // 改行で別のヘッダを差し込ませない
                        if (n.isNotEmpty() && ':' in value && n.none { it == '\r' || it == '\n' }) {
                            headers += n to value.substringAfter(':').trim().replace("\r", "").replace("\n", "")
                        }
                    }
                    "timeout" -> cmd.copy(timeoutSec = value.toIntOrNull()?.coerceIn(1, MAX_TIMEOUT_SEC) ?: cmd.timeoutSec)
                    "progress-notify" -> cmd.copy(progress = true)
                    "streaming" -> cmd.copy(streaming = value.lowercase().ifEmpty { "auto" })
                    "nodescript" -> cmd.copy(nodescript = true)
                    "insecure" -> cmd.copy(insecure = true)
                    "from" -> cmd.copy(from = runCatching { LocalDate.parse(value, dateFormat) }.getOrNull())
                    "to" -> cmd.copy(to = runCatching { LocalDate.parse(value, dateFormat) }.getOrNull())
                    "expand" -> cmd.copy(expand = true, expandDays = value.toIntOrNull()?.coerceIn(1, 1830))
                    "limit" -> cmd.copy(limit = value.toIntOrNull()?.coerceAtLeast(0))
                    else -> cmd
                }
            }
            // 改行を含むヘッダの値は落とす
            return cmd.copy(params = params, headers = headers.filter { (_, v) -> '\r' !in v && '\n' !in v })
        }

        /** Shift_JIS は Windows の拡張文字を含む windows-31j で読む。わからなければ UTF-8 */
        fun charset(name: String): Charset {
            val n = name.trim()
            if (n.isEmpty()) return Charsets.UTF_8
            if (n.equals("Shift_JIS", true) || n.equals("SJIS", true)) return Charset.forName("windows-31j")
            return runCatching { Charset.forName(n) }.getOrDefault(Charsets.UTF_8)
        }
    }
}
