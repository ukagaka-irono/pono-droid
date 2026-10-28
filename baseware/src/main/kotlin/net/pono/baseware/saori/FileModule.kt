package net.pono.baseware.saori

import net.pono.baseware.net.NetTools

/** internal_file: ファイルそのものを対象にする処理 */
object FileModule {
    /** MediaInfo のキー（これ以外は空にする） */
    private val MEDIA_KEYS = setOf("title", "artist", "albumartist", "album", "genre", "comment", "year", "track", "duration", "width", "height")

    val module = SaoriModule(
        "internal_file",
        listOf(
            SaoriFunction("MediaInfo", 2, SaoriFunction.VARIADIC, emptyList()) { c -> mediaInfo(c) },
            SaoriFunction("CompressArchive", 2, 2, listOf("password", "option")) { c -> archive(c, compress = true) },
            SaoriFunction("ExtractArchive", 2, 2, listOf("password")) { c -> archive(c, compress = false) },
        ),
    )

    private fun mediaInfo(c: SaoriCall): SaoriResult {
        val keys = c.args.drop(1)
        if (keys.isEmpty() || keys.size > 11) throw SaoriException.bad("invalid parameter")
        val path = c.arg(0)
        if ("://" in path) throw SaoriException.bad("invalid parameter")
        val file = c.ghostFile(path)
        if (!file.isFile) throw SaoriException.bad("file not found")
        val info = runCatching { c.env.mediaInfo(file) }.getOrNull()
        val values = keys.map { k ->
            val key = k.trim().lowercase()
            if (key !in MEDIA_KEYS) "" else clean(info?.values?.get(key).orEmpty())
        }
        return SaoriResult(info?.kind.orEmpty(), values)
    }

    /** 値の中の改行は 0x01 に（CRLF も 1 つ）、NUL は除く */
    private fun clean(s: String) = s.replace("\r\n", "\u0001").replace('\r', '\u0001').replace('\n', '\u0001').replace("\u0000", "")

    private fun archive(c: SaoriCall, compress: Boolean): SaoriResult {
        if (c.arg(0).isBlank() || c.arg(1).isBlank()) throw SaoriException.bad("invalid parameter")
        // 書き込む先も読む元も、ゴーストのフォルダの中だけ
        val first = c.ghostFile(c.arg(0), outside = "path not allowed")
        val second = c.ghostFile(c.arg(1), outside = "path not allowed")
        val password = c.options["password"]?.takeIf { it.isNotEmpty() }
        val r = try {
            if (compress) {
                val opts = c.options["option"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()
                if (opts.any { it !in setOf("overwrite", "append", "lzma", "headerencrypt") }) throw SaoriException.bad("invalid parameter")
                if ("headerencrypt" in opts && password == null) throw SaoriException.bad("invalid parameter")
                if (!second.exists()) throw SaoriException.bad("file not found")
                if (!second.isDirectory) throw SaoriException.fail("file not found")
                if (first.exists() && "overwrite" !in opts && "append" !in opts) throw SaoriException.bad("file exists")
                // ヘッダ（中央ディレクトリ）の暗号化は PKWARE だけの仕様で、作れない
                if ("headerencrypt" in opts) throw SaoriException.fail("unknown method")
                NetTools.compress(second, first, password, append = "append" in opts && "overwrite" !in opts, lzma = "lzma" in opts)
            } else {
                if (!first.isFile) throw SaoriException.bad("file not found")
                NetTools.extract(first, second, password)
            }
        } catch (e: NetTools.ArchiveException) {
            throw when (e.code) {
                "file not found", "directory not found" -> SaoriException.bad("file not found")
                "invalid parameter" -> SaoriException.bad("invalid parameter")
                "password required" -> SaoriException.fail("password mismatch")
                else -> SaoriException.fail(e.code)
            }
        }
        return SaoriResult(r.files.toString(), listOf(r.compressed.toString(), r.uncompressed.toString()))
    }
}
