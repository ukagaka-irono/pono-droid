package net.pono.baseware.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.text.TextCharsets
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.security.DigestOutputStream
import java.security.MessageDigest

/** updates2.dau / updates.txt の1行。[path] はゴーストのディレクトリからの相対パス（区切りは /）。 */
data class UpdateEntry(val path: String, val md5: String)

object UpdateManifest {
    private val md5Pattern = Regex("^[0-9a-fA-F]{32}$")

    /**
     * 1行1ファイルで、項目は 0x01 で区切られる（パス, MD5, size=... など）。
     * updates.txt は行頭に "file," が付く。読めない行は飛ばす。
     */
    fun parse(text: String): List<UpdateEntry> = text.lineSequence().mapNotNull { raw ->
        val fields = raw.trimEnd('\r').split('\u0001').map { it.trim() }
        val path = fields[0].removePrefix("file,").replace('\\', '/').trim()
        val md5 = fields.drop(1).firstOrNull { md5Pattern.matches(it) }
        if (path.isEmpty() || md5 == null || path.endsWith("/")) null else UpdateEntry(path, md5.lowercase())
    }.toList()
}

sealed interface FetchResult {
    data object Ok : FetchResult
    data class HttpError(val code: Int) : FetchResult
    data object Timeout : FetchResult
    data class Failed(val message: String) : FetchResult
}

/** 取ってきた中身は [sink] に流す（大きなファイルをメモリに溜めないため）。[maxBytes] を超えたら途中でやめて Failed。 */
fun interface HttpFetcher {
    fun fetch(url: String, sink: OutputStream, maxBytes: Long): FetchResult
}

/** 小さなもの（更新の一覧など）向け。中身は Ok のときだけ意味がある */
fun HttpFetcher.fetchBytes(url: String, maxBytes: Long): Pair<FetchResult, ByteArray> {
    val out = ByteArrayOutputStream()
    return fetch(url, out, maxBytes) to out.toByteArray()
}

/** 中身を捨てる（届いたかどうかだけ知りたいとき） */
object NullOutputStream : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}

/** HttpURLConnection を使う実装。Android でもそのまま動く。 */
class UrlConnectionFetcher(private val userAgent: String) : HttpFetcher {
    override fun fetch(url: String, sink: OutputStream, maxBytes: Long): FetchResult {
        val conn = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return FetchResult.Failed("bad url")
        }
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent)
            val code = conn.responseCode
            if (code !in 200..299) return FetchResult.HttpError(code)
            if (conn.contentLengthLong > maxBytes) return FetchResult.Failed("file too large")
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return FetchResult.Failed("file too large")
                    sink.write(buf, 0, n)
                }
            }
            FetchResult.Ok
        } catch (e: SocketTimeoutException) {
            FetchResult.Timeout
        } catch (e: IOException) {
            // ゴーストはこの理由をそのまま話すことがあるので、例外の名前ではなく読める言葉にする
            FetchResult.Failed(
                when (e) {
                    is javax.net.ssl.SSLException -> "サーバーの証明書を確認できませんでした"
                    is java.net.UnknownHostException -> "サーバーが見つかりませんでした"
                    is java.net.ConnectException -> "サーバーに接続できませんでした"
                    else -> "通信に失敗しました"
                },
            )
        } finally {
            conn.disconnect()
        }
    }
}

sealed interface UpdateResult {
    data object NoChange : UpdateResult
    data class Changed(val files: List<String>) : UpdateResult

    /** checkonly で確かめただけ（[files] が空なら更新は無い）。testonly なら落として確かめたが置き換えていない */
    data class Checked(val files: List<String>) : UpdateResult

    /** [reason] は OnUpdateFailure の Reference0 にそのまま渡す（"timeout", "md5 miss", HTTP のステータスなど） */
    data class Failure(val reason: String) : UpdateResult
}

/**
 * SSP 互換のネットワーク更新。homeurl の updates2.dau（無ければ updates.txt）を読み、
 * MD5 が違うファイルだけを落としてくる。全部そろって MD5 が合ってから入れ替えるので、途中で失敗しても元のまま。
 * 更新サーバーの言うことは信用しない: パスはゴーストのディレクトリの中に限り、大きさと数にも上限を置く。
 */
class NetworkUpdater(
    private val ghostDir: File,
    private val fetcher: HttpFetcher,
) {
    private class Manifest(val base: String, val entries: List<UpdateEntry>)

    /** 更新のやり方（SSP の更新オプション）。CHECK は更新があるかだけ、TEST は落として確かめるが置き換えない */
    enum class Mode { NORMAL, CHECK, TEST }

    /** @param onReady 落とすファイル数が決まったとき（0 件なら呼ばない） */
    suspend fun update(homeUrl: String, mode: Mode = Mode.NORMAL, onReady: suspend (count: Int) -> Unit = {}): UpdateResult = withContext(Dispatchers.IO) {
        val declared = normalizeBase(homeUrl) ?: return@withContext UpdateResult.Failure("invalid homeurl")
        val (manifest, fetched) = fetchManifest(declared)
        if (manifest == null) return@withContext failure(fetched)
        val entries = manifest.entries
        if (entries.isEmpty()) return@withContext UpdateResult.Failure("invalid updates list")
        if (entries.size > MAX_FILES) return@withContext UpdateResult.Failure("too many files")

        val root = ghostDir.canonicalFile
        val staging = File(root, "$PONO_DIR/update-staging")
        try {
            val targets = entries.filter { e ->
                val file = safeFile(root, e.path) ?: return@filter false
                !file.isFile || md5(file) != e.md5
            }
            if (mode == Mode.CHECK) return@withContext UpdateResult.Checked(targets.map { it.path })
            if (targets.isEmpty()) return@withContext UpdateResult.NoChange
            onReady(targets.size)

            // いったん作業用ディレクトリに落とし、すべて MD5 が合ってから入れ替える。落としながら MD5 を取る
            staging.deleteRecursively()
            staging.mkdirs()
            var total = 0L
            for (e in targets) {
                val dest = File(staging, e.path).apply { parentFile?.mkdirs() }
                val digest = MessageDigest.getInstance("MD5")
                val result = DigestOutputStream(dest.outputStream(), digest).use {
                    fetcher.fetch(manifest.base + encodePath(e.path), it, MAX_FILE_BYTES)
                }
                if (result != FetchResult.Ok) return@withContext failure(result)
                if (hex(digest.digest()) != e.md5) return@withContext UpdateResult.Failure("md5 miss")
                total += dest.length()
                if (total > MAX_TOTAL_BYTES) return@withContext UpdateResult.Failure("file too large")
            }
            if (mode == Mode.TEST) return@withContext UpdateResult.Checked(targets.map { it.path })
            for (e in targets) {
                val dest = safeFile(root, e.path)!!
                dest.parentFile?.mkdirs()
                File(staging, e.path).copyTo(dest, overwrite = true)
            }
            // 更新で delete.txt が来たら、書かれているファイルを消す（SSP と同じ）
            if (targets.any { it.path.equals(DELETE_LIST, ignoreCase = true) }) applyDeleteList(root)
            UpdateResult.Changed(targets.map { it.path })
        } catch (e: IOException) {
            UpdateResult.Failure("fileio")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 更新の一覧を取ってくる。homeurl が http:// でも、まず https:// で試す
     * （通れば経路上で書き換えられない）。だめなら書かれたとおり http:// を使う。
     * @return 取れなければ null と、その理由
     */
    private fun fetchManifest(declared: String): Pair<Manifest?, FetchResult> {
        val https = declared.takeIf { it.startsWith("http://") }?.let { "https://" + it.removePrefix("http://") }
        if (https != null) {
            val (result, bytes) = fetchManifestAt(https)
            if (result == FetchResult.Ok) {
                val entries = UpdateManifest.parse(TextCharsets.decode(bytes))
                if (entries.isNotEmpty()) return Manifest(https, entries) to result
            }
        }
        val (result, bytes) = fetchManifestAt(declared)
        if (result != FetchResult.Ok) return null to result
        return Manifest(declared, UpdateManifest.parse(TextCharsets.decode(bytes))) to result
    }

    private fun fetchManifestAt(base: String): Pair<FetchResult, ByteArray> {
        val r = fetcher.fetchBytes(base + "updates2.dau", MAX_MANIFEST_BYTES)
        val first = r.first
        return if (first is FetchResult.HttpError && first.code == 404) fetcher.fetchBytes(base + "updates.txt", MAX_MANIFEST_BYTES) else r
    }

    private fun failure(r: FetchResult) = UpdateResult.Failure(describe(r))

    companion object {
        const val DELETE_LIST = "delete.txt"

        /** pono-droid がゴーストごとに覚えておくもの（profile など）。更新で触らせない */
        const val PONO_DIR = "pono"

        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        const val MAX_FILE_BYTES = 256L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
        const val MAX_FILES = 20_000

        /** 失敗の理由。SSP と同じく "timeout" と HTTP のステータスはそのまま、それ以外は読める言葉 */
        fun describe(r: FetchResult): String = when (r) {
            is FetchResult.HttpError -> r.code.toString()
            FetchResult.Timeout -> "timeout"
            is FetchResult.Failed -> r.message
            FetchResult.Ok -> "unknown"
        }

        /**
         * ゴーストのディレクトリ直下の delete.txt（1行に1つ、ゴーストのディレクトリからの相対パス）に
         * 書かれたファイルを消す。外を指すパスと pono/ の中は無視する。.nar のインストールでも使う。
         */
        fun applyDeleteList(root: File) {
            val list = File(root, DELETE_LIST).takeIf { it.isFile } ?: return
            val canonicalRoot = root.canonicalFile
            for (line in TextCharsets.decode(list.readBytes()).lines()) {
                val path = line.trim().replace('\\', '/').trimEnd('/')
                if (path.isEmpty()) continue
                val target = safeFile(canonicalRoot, path) ?: continue
                if (target.isDirectory) target.deleteRecursively() else target.delete()
            }
        }

        /** --file= のようにゴーストから渡されたファイル名。ディレクトリを含むもの・隠しファイルは受け付けない */
        fun safeFileName(name: String): String? =
            name.trim().takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && '\u0000' !in it && !it.startsWith(".") }

        fun normalizeBase(homeUrl: String): String? {
            val url = homeUrl.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            return if (url.endsWith("/")) url else "$url/"
        }

        /**
         * 更新サーバーや delete.txt が言うパスを、[root]（正規化済み）の中のファイルにする。
         * 外を指すもの、管理用の pono/ を指すもの、変な書き方（"./pono/x"、"a//b"、NUL）は受け付けない（null）。
         */
        fun safeFile(root: File, path: String): File? {
            if (path.isEmpty() || path.startsWith("/") || '\u0000' in path) return null
            if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
            val file = try {
                File(root, path).canonicalFile
            } catch (e: IOException) {
                return null
            }
            if (!file.path.startsWith(root.path + File.separator)) return null
            val pono = File(root, PONO_DIR).path
            if (file.path == pono || file.path.startsWith(pono + File.separator)) return null
            return file
        }

        private fun encodePath(path: String) =
            path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        fun md5(bytes: ByteArray): String = hex(MessageDigest.getInstance("MD5").digest(bytes))

        /** ファイルをメモリに全部読まずに MD5 を取る */
        fun md5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
