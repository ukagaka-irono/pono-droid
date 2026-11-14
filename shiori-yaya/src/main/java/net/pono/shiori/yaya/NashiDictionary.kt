package net.pono.shiori.yaya

import net.pono.baseware.saori.MiniJson
import net.pono.baseware.text.TextCharsets
import java.io.ByteArrayOutputStream
import java.io.File

/** ghost.json は他の栞でも使える名前なので、なし栞の形式まで確認する。 */
internal object NashiDictionary {
    private const val MAX_BYTES = 16 * 1024 * 1024 // nashi の辞書上限と同じ

    fun matches(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() > MAX_BYTES) return false
        val bytes = file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) return false
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val root = MiniJson.parse(withoutComments(TextCharsets.decode(bytes))) as? Map<*, *> ?: return false
        root["format"] == "nashi/1" && root["scripts"] is List<*>
    }.getOrDefault(false)

    /** nashi の JSON はコメントを許す。文字列中の URL、引用符、エスケープには触れない。 */
    private fun withoutComments(text: String): String = buildString {
        var i = 0
        var quoted = false
        while (i < text.length) {
            val ch = text[i++]
            if (quoted) {
                append(ch)
                if (ch == '\\' && i < text.length) append(text[i++])
                else if (ch == '"') quoted = false
            } else if (ch == '"') {
                quoted = true
                append(ch)
            } else if (ch == '/' && text.getOrNull(i) == '/') {
                while (i < text.length && text[i] != '\n') i++
                append(' ')
            } else if (ch == '/' && text.getOrNull(i) == '*') {
                val end = text.indexOf("*/", i + 1)
                require(end >= 0) { "Unterminated comment" }
                i = end + 2
                append(' ')
            } else append(ch)
        }
    }
}
