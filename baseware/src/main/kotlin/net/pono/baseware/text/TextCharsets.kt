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

    /**
     * readme などを読む。[charsetName]（descript.txt の readme.charset）が分かればその文字コードで、
     * 無ければ [decode] と同じく charset 行か中身から決める
     */
    fun decode(bytes: ByteArray, charsetName: String?): String {
        val charset = resolve(charsetName) ?: return decode(bytes)
        val bom = charset == Charsets.UTF_8 && bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        return String(if (bom) bytes.copyOfRange(3, bytes.size) else bytes, charset)
    }

    /** [dir] の直下の [name] のファイル（thumbnail.png など。大文字小文字は問わない）。無ければ null */
    fun fileInIgnoringCase(dir: File, name: String): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }

    /** [dir] の中の [name] のファイル（readme など）。置き場の外を指していたり、無かったりすれば null */
    fun fileIn(dir: File, name: String): File? {
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(root, name.replace('\\', '/')).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
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
