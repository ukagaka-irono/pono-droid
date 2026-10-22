package net.pono.baseware.shiori

import net.pono.baseware.text.TextCharsets
import java.nio.charset.Charset

enum class ShioriMethod { GET, NOTIFY }

data class ShioriRequest(
    val method: ShioriMethod,
    val id: String,
    val references: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
) {
    fun encode(charset: Charset, sender: String): ByteArray {
        val sb = StringBuilder()
        sb.append(method.name).append(" SHIORI/3.0\r\n")
        sb.append("Charset: ").append(charsetHeaderName(charset)).append("\r\n")
        sb.append("Sender: ").append(sanitize(sender)).append("\r\n")
        sb.append("SenderType: internal\r\n")
        sb.append("SecurityLevel: local\r\n")
        sb.append("ID: ").append(sanitize(id)).append("\r\n")
        headers.forEach { (k, v) -> sb.append(sanitize(k)).append(": ").append(sanitize(v)).append("\r\n") }
        references.forEachIndexed { i, v -> sb.append("Reference").append(i).append(": ").append(sanitize(v)).append("\r\n") }
        sb.append("\r\n")
        return sb.toString().toByteArray(charset)
    }

    /** 改行はヘッダを壊す（別のヘッダを差し込める）し、NUL は C の文字列をそこで切るので、どちらも通さない */
    private fun sanitize(v: String) =
        v.replace("\r\n", "\u0001").replace('\r', '\u0001').replace('\n', '\u0001').replace('\u0000', '\u0001')

    companion object {
        fun charsetHeaderName(charset: Charset): String =
            if (charset == TextCharsets.CP932) "Shift_JIS" else charset.name()
    }
}

data class ShioriResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
) {
    val value: String? get() = headers["Value"]?.takeIf { it.isNotEmpty() }

    /** Reference0 などの追加ヘッダ。 */
    fun reference(i: Int): String? = headers["Reference$i"]

    companion object {
        private val statusLine = Regex("""^SHIORI/\d\.\d\s+(\d{3})""")

        /**
         * 生のレスポンスを読む。文字コードは `Charset` ヘッダ（ASCII なのでどの文字コードでも読める）で決め、
         * 無ければ [fallback] を使う。
         */
        fun parse(bytes: ByteArray, fallback: Charset): ShioriResponse? {
            val latin = String(bytes, Charsets.ISO_8859_1)
            val declared = latin.lineSequence()
                .map { it.trimEnd('\r') }
                .firstOrNull { it.startsWith("Charset:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
                ?.let(TextCharsets::resolve)
            val text = String(bytes, declared ?: fallback)
            val lines = text.split("\r\n", "\n")
            val code = statusLine.find(lines.firstOrNull() ?: return null)?.groupValues?.get(1)?.toInt() ?: return null
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) break
                val colon = line.indexOf(": ").takeIf { it > 0 } ?: line.indexOf(':').takeIf { it > 0 } ?: continue
                val key = line.substring(0, colon)
                val value = line.substring(colon + 1).removePrefix(" ")
                headers[key] = value
            }
            return ShioriResponse(code, headers)
        }
    }
}
