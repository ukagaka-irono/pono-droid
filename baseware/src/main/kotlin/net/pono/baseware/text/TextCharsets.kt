package net.pono.baseware.text

import java.io.File
import java.nio.charset.Charset

/** 伺かの設定ファイルで使われる文字コード名を Java の Charset に解決する。 */
object TextCharsets {
    /** Shift_JIS と書かれていても実際は Windows の CP932 なので、そちらを使う。 */
    val CP932: Charset = runCatching { Charset.forName("windows-31j") }
        .getOrElse { Charset.forName("Shift_JIS") }

    fun resolve(name: String?): Charset? {
        val n = name?.trim()?.lowercase() ?: return null
        return when (n) {
            "shift_jis", "sjis", "x-sjis", "cp932", "windows-31j", "ms932" -> CP932
            "utf-8", "utf8" -> Charsets.UTF_8
            else -> runCatching { Charset.forName(n) }.getOrNull()
        }
    }

    /**
     * `charset,XXX` 行を含みうる伺かのテキストファイルを読む。
     * charset 行が無ければ、UTF-8 として正しく読めるかで判定し、だめなら CP932 とする。
     */
    fun readLines(file: File): List<String> = decode(file.readBytes()).lines()

    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val declared = declaredCharset(body)
        if (declared != null) return String(body, declared)
        return if (isValidUtf8(body)) String(body, Charsets.UTF_8) else String(body, CP932)
    }

    private val charsetLine = Regex("""^\s*charset\s*,\s*([A-Za-z0-9_\-]+)""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

    private fun declaredCharset(bytes: ByteArray): Charset? {
        // charset 行は ASCII なので、どの文字コードでも ISO-8859-1 で読めば見つかる
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return charsetLine.find(head)?.let { resolve(it.groupValues[1]) }
    }

    fun isValidUtf8(bytes: ByteArray): Boolean = runCatching {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
    }.isSuccess
}
