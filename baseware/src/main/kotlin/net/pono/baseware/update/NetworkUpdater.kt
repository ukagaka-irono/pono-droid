package net.pono.baseware.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.text.TextCharsets
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
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
    class Ok(val bytes: ByteArray) : FetchResult
    data class HttpError(val code: Int) : FetchResult
    data object Timeout : FetchResult
    data class Failed(val message: String) : FetchResult
}

fun interface HttpFetcher {
    fun fetch(url: String): FetchResult
}

/** HttpURLConnection を使う実装。Android でもそのまま動く。 */
class UrlConnectionFetcher(
    private val userAgent: String,
    private val maxBytes: Long = 64L * 1024 * 1024,
) : HttpFetcher {
    override fun fetch(url: String): FetchResult {
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
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return FetchResult.Failed("file too large")
                }
                out.toByteArray()
            }
            FetchResult.Ok(bytes)
        } catch (e: SocketTimeoutException) {
            FetchResult.Timeout
        } catch (e: IOException) {
            FetchResult.Failed(e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}

sealed interface UpdateResult {
    data object NoChange : UpdateResult
    data class Changed(val files: List<String>) : UpdateResult

    /** [reason] は OnUpdateFailure の Reference0 にそのまま渡す（"timeout", "md5 miss", HTTP のステータスなど） */
    data class Failure(val reason: String) : UpdateResult
}

/**
 * SSP 互換のネットワーク更新。homeurl の updates2.dau（無ければ updates.txt）を読み、
 * MD5 が違うファイルだけを落としてくる。全部そろって MD5 が合ってから入れ替えるので、途中で失敗しても元のまま。
 */
class NetworkUpdater(
    private val ghostDir: File,
    private val fetcher: HttpFetcher,
) {
    /** @param onReady 落とすファイル数が決まったとき（0 件なら呼ばない） */
    suspend fun update(homeUrl: String, onReady: suspend (count: Int) -> Unit = {}): UpdateResult = withContext(Dispatchers.IO) {
        val base = normalizeBase(homeUrl) ?: return@withContext UpdateResult.Failure("invalid homeurl")

        val manifestText = when (val r = fetchManifest(base)) {
            is FetchResult.Ok -> TextCharsets.decode(r.bytes)
            else -> return@withContext failure(r)
        }
        val entries = UpdateManifest.parse(manifestText)
        if (entries.isEmpty()) return@withContext UpdateResult.Failure("invalid updates list")

        val root = ghostDir.canonicalFile
        val targets = entries.filter { e ->
            val file = safeFile(root, e.path) ?: return@filter false
            !file.isFile || md5(file.readBytes()) != e.md5
        }
        if (targets.isEmpty()) return@withContext UpdateResult.NoChange
        onReady(targets.size)

        // いったん作業用ディレクトリに落とし、すべて MD5 が合ってから入れ替える
        val staging = File(root, "pono/update-staging").apply { deleteRecursively(); mkdirs() }
        try {
            for (e in targets) {
                val bytes = when (val r = fetcher.fetch(base + encodePath(e.path))) {
                    is FetchResult.Ok -> r.bytes
                    else -> return@withContext failure(r)
                }
                if (md5(bytes) != e.md5) return@withContext UpdateResult.Failure("md5 miss")
                File(staging, e.path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            }
            for (e in targets) {
                val dest = safeFile(root, e.path)!!
                dest.parentFile?.mkdirs()
                File(staging, e.path).copyTo(dest, overwrite = true)
            }
        } catch (e: IOException) {
            return@withContext UpdateResult.Failure("fileio")
        } finally {
            staging.deleteRecursively()
        }
        UpdateResult.Changed(targets.map { it.path })
    }

    private fun fetchManifest(base: String): FetchResult {
        val r = fetcher.fetch(base + "updates2.dau")
        return if (r is FetchResult.HttpError && r.code == 404) fetcher.fetch(base + "updates.txt") else r
    }

    private fun failure(r: FetchResult) = UpdateResult.Failure(
        when (r) {
            is FetchResult.HttpError -> r.code.toString()
            FetchResult.Timeout -> "timeout"
            is FetchResult.Failed -> r.message
            is FetchResult.Ok -> "unknown"
        },
    )

    companion object {
        fun normalizeBase(homeUrl: String): String? {
            val url = homeUrl.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            return if (url.endsWith("/")) url else "$url/"
        }

        /** ゴーストのディレクトリの外や管理用の pono/ を指すパスは受け付けない。 */
        fun safeFile(root: File, path: String): File? {
            if (path.startsWith("/") || path.split('/').any { it == ".." } || path.startsWith("pono/")) return null
            val file = File(root, path).canonicalFile
            return file.takeIf { it.path.startsWith(root.path + File.separator) }
        }

        private fun encodePath(path: String) =
            path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
