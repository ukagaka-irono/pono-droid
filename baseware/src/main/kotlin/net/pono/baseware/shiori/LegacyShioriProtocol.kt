package net.pono.baseware.shiori

import java.nio.charset.Charset

/** SHIORI/2.6 の専用メソッド・ヘッダと、本体が使う SHIORI/3.0 のイベントを対応させる。 */
internal object LegacyShioriProtocol {
    fun encode(request: ShioriRequest, charset: Charset, sender: String, escapeUnknown: Boolean): ByteArray? {
        val id = request.id
        val method = when (id) {
            "version" -> "GET Version"
            "OnTeach" -> "TEACH"
            "OnTranslate" -> "TRANSLATE Sentence"
            "ownerghostname" -> "NOTIFY OwnerGhostName"
            "otherghostname" -> "NOTIFY OtherGhostName"
            else -> when {
                request.method == ShioriMethod.NOTIFY -> return null // 2.x に一般の NOTIFY イベントは無い。
                id.firstOrNull() in 'a'..'z' -> "GET String"
                else -> "GET Sentence"
            }
        }
        val headers = linkedMapOf(
            "Charset" to ShioriRequest.charsetHeaderName(charset), "Sender" to sender,
            "SecurityLevel" to (request.headers["SecurityLevel"] ?: "local"),
        )
        // 構造を決めるヘッダは、この変換が責任を持って生成する。
        request.headers.filterKeys {
            it !in setOf("ID", "Charset", "Sender", "Event", "Sentence", "Word", "Ghost", "GhostEx") && !it.startsWith("Reference")
        }.forEach { (k, v) -> headers[k] = v }
        var offset = 0
        when (method) {
            "GET Sentence" -> if (id == "OnCommunicate") {
                headers["Sender"] = request.references.getOrElse(0) { "" }
                headers["Sentence"] = request.references.getOrElse(1) { "" }
                headers["Age"] = request.headers["Age"] ?: "0"
                offset = 2
            } else headers["Event"] = id
            "GET String" -> headers["ID"] = id
            "TEACH", "TRANSLATE Sentence" -> {
                headers[if (method == "TEACH") "Word" else "Sentence"] = request.references.firstOrNull() ?: ""
                offset = 1
            }
            "NOTIFY OwnerGhostName" -> { headers["Ghost"] = request.references.firstOrNull() ?: ""; offset = 1 }
        }
        val text = buildString {
            append(method).append(" SHIORI/").append(if (method == "TEACH") "2.4" else "2.6").append("\r\n")
            fun header(key: String, value: String) { append(clean(key)).append(": ").append(clean(value)).append("\r\n") }
            headers.forEach { (key, value) -> header(key, value) }
            if (method == "NOTIFY OtherGhostName") {
                // 同名のヘッダを複数送る必要があるので Map に入れない。
                request.references.forEach {
                    header("Ghost", it.substringBefore('\u0001'))
                    header("GhostEx", it)
                }
            } else request.references.drop(offset).forEachIndexed { i, v -> header("Reference$i", v) }
            append("\r\n")
        }
        return (if (escapeUnknown) ShioriRequest.escape(text, charset) else text).toByteArray(charset)
    }

    fun convert(request: ShioriRequest, response: ShioriResponse): ShioriResponse {
        val valueHeader = when {
            request.id == "version" -> "Version"
            request.id in setOf("OnTeach", "OnTranslate", "OnCommunicate") -> "Sentence"
            request.id.firstOrNull() in 'a'..'z' -> "String"
            else -> "Sentence"
        }
        val headers = linkedMapOf<String, String>()
        for ((name, value) in response.headers) {
            val index = if (name.startsWith("Reference")) name.removePrefix("Reference").toIntOrNull() else null
            when {
                name.equals(valueHeader, ignoreCase = true) -> headers["Value"] = value
                name == "To" -> headers["Reference0"] = value
                index != null && index in 0 until Int.MAX_VALUE -> headers["Reference${index + 1}"] = value
                name != "Age" -> headers[name] = value
            }
        }
        return response.copy(headers = headers)
    }

    private fun clean(value: String) = value.replace("\r\n", "\u0001")
        .replace('\r', '\u0001').replace('\n', '\u0001').replace('\u0000', '\u0001')
}
